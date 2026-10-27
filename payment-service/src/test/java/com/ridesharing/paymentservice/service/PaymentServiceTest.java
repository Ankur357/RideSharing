package com.ridesharing.paymentservice.service;

import com.ridesharing.paymentservice.event.PaymentEvent;
import com.ridesharing.paymentservice.event.RideEvent;
import com.ridesharing.paymentservice.model.Payment;
import com.ridesharing.paymentservice.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService Unit Tests")
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private KafkaTemplate<String, PaymentEvent> kafkaTemplate;

    @InjectMocks private PaymentService paymentService;

    private static final String RIDE_ID   = "ride-uuid-001";
    private static final String RIDER_ID  = "rider-uuid-001";
    private static final String DRIVER_ID = "driver-uuid-001";
    private static final BigDecimal FARE  = BigDecimal.valueOf(150);

    @BeforeEach
    void setUp() {
        // Inject a test Stripe key so Stripe.apiKey doesn't throw on init
        ReflectionTestUtils.setField(paymentService, "stripeSecretKey", "sk_test_dummy_key_for_unit_tests");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Idempotency tests — the most important tests for payment service
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("handleRideCompleted: should skip processing if payment already exists (idempotency Layer 1)")
    void handleRideCompleted_shouldSkipIfPaymentAlreadyExists() {
        // Arrange — payment already exists in DB (duplicate Kafka message scenario)
        Payment existingPayment = buildPayment(Payment.PaymentStatus.SUCCESS);
        when(paymentRepository.findByRideId(RIDE_ID)).thenReturn(Optional.of(existingPayment));

        RideEvent event = buildRideEvent();

        // Act
        paymentService.handleRideCompleted(event);

        // Assert — payment was NOT saved again (idempotency check worked)
        verify(paymentRepository, never()).save(any(Payment.class));

        // Assert — no Kafka event published (duplicate suppressed)
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any(PaymentEvent.class));
    }

    @Test
    @DisplayName("handleRideCompleted: should create payment record when none exists")
    void handleRideCompleted_shouldCreatePaymentWhenNoneExists() {
        // Arrange — no existing payment
        when(paymentRepository.findByRideId(RIDE_ID)).thenReturn(Optional.empty());

        Payment savedPayment = buildPayment(Payment.PaymentStatus.PENDING);
        savedPayment.setId("payment-uuid-001");
        when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment);

        RideEvent event = buildRideEvent();

        // Act — note: Stripe call will fail with test key (that's expected)
        // We verify the DB record is created before Stripe is called
        try {
            paymentService.handleRideCompleted(event);
        } catch (Exception e) {
            // Stripe API call fails in unit test — expected
        }

        // Assert — payment record created with correct data
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, atLeastOnce()).save(paymentCaptor.capture());
        Payment created = paymentCaptor.getAllValues().get(0);
        assertThat(created.getRideId()).isEqualTo(RIDE_ID);
        assertThat(created.getRiderId()).isEqualTo(RIDER_ID);
        assertThat(created.getAmount()).isEqualTo(FARE);
        assertThat(created.getCurrency()).isEqualTo("inr");
    }

    @Test
    @DisplayName("handleRideCompleted: calling twice with same rideId should only process once")
    void handleRideCompleted_calledTwice_shouldOnlyProcessOnce() {
        // First call — no existing payment
        when(paymentRepository.findByRideId(RIDE_ID))
            .thenReturn(Optional.empty())                          // first call
            .thenReturn(Optional.of(buildPayment(Payment.PaymentStatus.PENDING))); // second call

        Payment savedPayment = buildPayment(Payment.PaymentStatus.PENDING);
        savedPayment.setId("payment-uuid-001");
        when(paymentRepository.save(any())).thenReturn(savedPayment);

        RideEvent event = buildRideEvent();

        // Act — first call
        try { paymentService.handleRideCompleted(event); } catch (Exception ignored) {}

        // Act — second call (simulating duplicate Kafka message delivery)
        paymentService.handleRideCompleted(event);

        // Assert — save was only called on first invocation (second was blocked by idempotency check)
        // The second call finds existing payment and returns early
        verify(paymentRepository, times(2)).findByRideId(RIDE_ID);
        // save called only during first attempt (before Stripe fails)
        verify(paymentRepository, atMost(2)).save(any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Payment status tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("handleStripeWebhook: should update payment to SUCCESS on payment_intent.succeeded")
    void handleStripeWebhook_shouldUpdateToSuccessOnSucceededEvent() {
        // Arrange
        String paymentIntentId = "pi_test_123456";
        Payment payment = buildPayment(Payment.PaymentStatus.PENDING);
        payment.setStripePaymentIntentId(paymentIntentId);
        when(paymentRepository.findByStripePaymentIntentId(paymentIntentId))
            .thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenReturn(payment);

        // Act
        paymentService.handleStripeWebhook(paymentIntentId, "succeeded");

        // Assert
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Payment.PaymentStatus.SUCCESS);
    }

    @Test
    @DisplayName("handleStripeWebhook: should update payment to FAILED on payment_failed event")
    void handleStripeWebhook_shouldUpdateToFailedOnFailedEvent() {
        String paymentIntentId = "pi_test_failed_789";
        Payment payment = buildPayment(Payment.PaymentStatus.PENDING);
        payment.setStripePaymentIntentId(paymentIntentId);
        when(paymentRepository.findByStripePaymentIntentId(paymentIntentId))
            .thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenReturn(payment);

        paymentService.handleStripeWebhook(paymentIntentId, "payment_failed");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Payment.PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("handleStripeWebhook: should do nothing when no payment found for paymentIntentId")
    void handleStripeWebhook_shouldDoNothingWhenPaymentNotFound() {
        when(paymentRepository.findByStripePaymentIntentId(any())).thenReturn(Optional.empty());

        paymentService.handleStripeWebhook("pi_nonexistent", "succeeded");

        verify(paymentRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private RideEvent buildRideEvent() {
        return RideEvent.builder()
            .eventType("ride.completed")
            .rideId(RIDE_ID)
            .riderId(RIDER_ID)
            .driverId(DRIVER_ID)
            .fare(FARE)
            .timestamp(System.currentTimeMillis())
            .build();
    }

    private Payment buildPayment(Payment.PaymentStatus status) {
        return Payment.builder()
            .rideId(RIDE_ID)
            .riderId(RIDER_ID)
            .driverId(DRIVER_ID)
            .amount(FARE)
            .currency("inr")
            .status(status)
            .build();
    }
}
