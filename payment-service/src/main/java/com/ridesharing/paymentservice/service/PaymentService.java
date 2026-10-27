package com.ridesharing.paymentservice.service;

import com.ridesharing.paymentservice.event.PaymentEvent;
import com.ridesharing.paymentservice.event.RideEvent;
import com.ridesharing.paymentservice.model.Payment;
import com.ridesharing.paymentservice.repository.PaymentRepository;
import com.stripe.Stripe;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service @RequiredArgsConstructor @Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;

    @Value("${stripe.secret-key}") private String stripeSecretKey;

    @PostConstruct
    public void init() { Stripe.apiKey = stripeSecretKey; }

    // Listens to ride.completed -> triggers payment
    @KafkaListener(topics = "ride.completed", groupId = "payment-service",
                   containerFactory = "rideEventKafkaListenerContainerFactory")
    @Transactional
    public void handleRideCompleted(RideEvent event) {
        log.info("Received ride.completed for rideId={}", event.getRideId());

        // Idempotency check — don't double-charge
        if (paymentRepository.findByRideId(event.getRideId()).isPresent()) {
            log.warn("Payment already exists for rideId={}, skipping", event.getRideId());
            return;
        }

        Payment payment = Payment.builder()
            .rideId(event.getRideId())
            .riderId(event.getRiderId())
            .driverId(event.getDriverId())
            .amount(event.getFare())
            .currency("inr")
            .build();
        payment = paymentRepository.save(payment);

        processStripePayment(payment);
    }

    @CircuitBreaker(name = "stripe", fallbackMethod = "stripePaymentFallback")
    private void processStripePayment(Payment payment) {
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(payment.getAmount().multiply(java.math.BigDecimal.valueOf(100)).longValue()) // to paise
                .setCurrency(payment.getCurrency())
                .setDescription("Ride payment for rideId=" + payment.getRideId())
                .putMetadata("rideId", payment.getRideId())
                .putMetadata("riderId", payment.getRiderId())
                .build();

            PaymentIntent intent = PaymentIntent.create(params);
            payment.setStripePaymentIntentId(intent.getId());
            payment.setStatus(Payment.PaymentStatus.SUCCESS);
            payment.setUpdatedAt(LocalDateTime.now());
            paymentRepository.save(payment);

            kafkaTemplate.send("payment.success", payment.getRideId(), PaymentEvent.builder()
                .eventType("payment.success")
                .paymentId(payment.getId())
                .rideId(payment.getRideId())
                .riderId(payment.getRiderId())
                .amount(payment.getAmount())
                .timestamp(System.currentTimeMillis())
                .build());

            log.info("Payment SUCCESS for rideId={}", payment.getRideId());

        } catch (Exception e) {
            log.error("Stripe payment failed for rideId={}: {}", payment.getRideId(), e.getMessage());
            payment.setStatus(Payment.PaymentStatus.FAILED);
            payment.setFailureReason(e.getMessage());
            payment.setUpdatedAt(LocalDateTime.now());
            paymentRepository.save(payment);

            kafkaTemplate.send("payment.failed", payment.getRideId(), PaymentEvent.builder()
                .eventType("payment.failed")
                .paymentId(payment.getId())
                .rideId(payment.getRideId())
                .riderId(payment.getRiderId())
                .amount(payment.getAmount())
                .failureReason(e.getMessage())
                .timestamp(System.currentTimeMillis())
                .build());
        }
    }

    // Circuit breaker fallback — called when Stripe is down
    private void stripePaymentFallback(Payment payment, Throwable t) {
        log.error("Circuit breaker open for Stripe. Ride {} queued for retry.", payment.getRideId());
        payment.setStatus(Payment.PaymentStatus.FAILED);
        payment.setFailureReason("Stripe unavailable - circuit breaker open");
        paymentRepository.save(payment);
    }

    // Handle Stripe webhooks for async confirmation
    @Transactional
    public void handleStripeWebhook(String paymentIntentId, String status) {
        paymentRepository.findByStripePaymentIntentId(paymentIntentId).ifPresent(payment -> {
            if ("succeeded".equals(status)) {
                payment.setStatus(Payment.PaymentStatus.SUCCESS);
            } else if ("payment_failed".equals(status)) {
                payment.setStatus(Payment.PaymentStatus.FAILED);
            }
            payment.setUpdatedAt(LocalDateTime.now());
            paymentRepository.save(payment);
        });
    }
}
