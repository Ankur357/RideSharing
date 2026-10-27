package com.ridesharing.rideservice.service;

import com.ridesharing.rideservice.event.PaymentEvent;
import com.ridesharing.rideservice.model.Ride;
import com.ridesharing.rideservice.repository.RideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes the Saga loop:
 *
 *  ride.completed  →  payment-service processes payment
 *      ├── payment.success  →  (no action needed — ride stays COMPLETED)
 *      └── payment.failed   →  THIS class sets ride to PAYMENT_FAILED
 *
 * Without this consumer, a ride would remain COMPLETED even when payment fails,
 * which is incorrect business logic. The Saga pattern requires a compensating
 * transaction — marking the ride as PAYMENT_FAILED is that compensating action.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {

    private final RideRepository rideRepository;

    /**
     * Listens for payment.failed events.
     * Updates the ride status to PAYMENT_FAILED as a Saga compensating transaction.
     */
    @KafkaListener(
        topics = "payment.failed",
        groupId = "ride-service-payment-consumer",
        containerFactory = "paymentEventKafkaListenerContainerFactory"
    )
    @Transactional
    public void handlePaymentFailed(PaymentEvent event) {
        log.warn("Payment FAILED for rideId={}, reason={}. Marking ride as PAYMENT_FAILED.",
            event.getRideId(), event.getFailureReason());

        rideRepository.findById(event.getRideId()).ifPresentOrElse(
            ride -> {
                // Only update if ride is in COMPLETED state — guard against duplicate events
                if (ride.getStatus() == Ride.RideStatus.COMPLETED) {
                    ride.setStatus(Ride.RideStatus.PAYMENT_FAILED);
                    rideRepository.save(ride);
                    log.info("Ride {} status updated to PAYMENT_FAILED", event.getRideId());
                } else {
                    log.warn("Ride {} is in status {} — skipping PAYMENT_FAILED update",
                        event.getRideId(), ride.getStatus());
                }
            },
            () -> log.error("Ride {} not found while handling payment.failed event", event.getRideId())
        );
    }

    /**
     * Listens for payment.success events.
     * No state change needed (ride stays COMPLETED), but we log for observability.
     */
    @KafkaListener(
        topics = "payment.success",
        groupId = "ride-service-payment-consumer",
        containerFactory = "paymentEventKafkaListenerContainerFactory"
    )
    public void handlePaymentSuccess(PaymentEvent event) {
        log.info("Payment SUCCESS confirmed for rideId={}. Ride lifecycle complete.", event.getRideId());
        // No state change needed — ride status remains COMPLETED
        // In a production system: trigger receipt generation, driver earnings update, rating request
    }
}
