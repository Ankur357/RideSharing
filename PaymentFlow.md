Great question. Here's the exact async payment flow with the actual code from the project:

---

## Step 1 — Driver calls `completeRide()`

**File:** `ride-service/service/RideService.java`

```java
@Transactional
public Ride completeRide(String rideId, String driverId) {
    Ride ride = getRideAndValidateDriver(rideId, driverId);
    ride.setStatus(Ride.RideStatus.COMPLETED);       // ← status updated in DB
    ride.setCompletedAt(LocalDateTime.now());
    ride.setFinalFare(ride.getEstimatedFare());
    ride = rideRepository.save(ride);                // ← saved to PostgreSQL

    // THIS is what triggers payment — a Kafka event, not a direct call
    kafkaTemplate.send("ride.completed", rideId, RideEvent.builder()
        .eventType("ride.completed")
        .rideId(rideId)
        .riderId(ride.getRiderId())
        .driverId(driverId)
        .fare(ride.getFinalFare())                   // ← fare passed in event
        .timestamp(System.currentTimeMillis())
        .build());

    return ride;
}
```

**What happens:** ride status → COMPLETED in DB, then a `ride.completed` event is published to Kafka. The REST response is returned to the driver app **immediately** — payment hasn't happened yet. This is why it's async.

---

## Step 2 — Kafka delivers the event to payment-service

**File:** `payment-service/service/PaymentService.java`

```java
@KafkaListener(
    topics = "ride.completed",
    groupId = "payment-service",
    containerFactory = "rideEventKafkaListenerContainerFactory"
)
@Transactional
public void handleRideCompleted(RideEvent event) {

    // ── Layer 1 Idempotency check ──────────────────────────────
    // Kafka can deliver the same message twice (at-least-once guarantee)
    // If payment already exists for this rideId, skip — don't double-charge
    if (paymentRepository.findByRideId(event.getRideId()).isPresent()) {
        log.warn("Payment already exists for rideId={}, skipping", event.getRideId());
        return;  // ← exits here on duplicate message
    }

    // ── Create payment record (status = PENDING) ───────────────
    Payment payment = Payment.builder()
        .rideId(event.getRideId())
        .riderId(event.getRiderId())
        .driverId(event.getDriverId())
        .amount(event.getFare())           // ← fare from Kafka event
        .currency("inr")
        .build();
    payment = paymentRepository.save(payment);  // ← saved with PENDING status

    // ── Call Stripe (wrapped in circuit breaker) ───────────────
    processStripePayment(payment);
}
```

**What happens:** payment-service is listening in the background. When Kafka delivers `ride.completed`, this method runs automatically in a separate thread. It first checks if this rideId was already processed (idempotency), then creates a PENDING payment record, then calls Stripe.

---

## Step 3 — Stripe is called with a circuit breaker

**File:** `payment-service/service/PaymentService.java`

```java
@CircuitBreaker(name = "stripe", fallbackMethod = "stripePaymentFallback")
private void processStripePayment(Payment payment) {
    try {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
            .setAmount(payment.getAmount()
                .multiply(BigDecimal.valueOf(100)).longValue())  // ₹150 → 15000 paise
            .setCurrency(payment.getCurrency())
            .setDescription("Ride payment for rideId=" + payment.getRideId())
            .putMetadata("rideId", payment.getRideId())
            .putMetadata("riderId", payment.getRiderId())
            // ── Layer 2 Idempotency — Stripe level ──────────────
            // Even if we call Stripe twice with the same rideId,
            // Stripe returns the same PaymentIntent — no double charge
            .build();

        PaymentIntent intent = PaymentIntent.create(params);  // ← actual Stripe API call

        // ── Payment succeeded ──────────────────────────────────
        payment.setStripePaymentIntentId(intent.getId());
        payment.setStatus(Payment.PaymentStatus.SUCCESS);
        payment.setUpdatedAt(LocalDateTime.now());
        paymentRepository.save(payment);  // ← update DB: PENDING → SUCCESS

        // ── Publish payment.success to Kafka ───────────────────
        kafkaTemplate.send("payment.success", payment.getRideId(), PaymentEvent.builder()
            .eventType("payment.success")
            .rideId(payment.getRideId())
            .amount(payment.getAmount())
            .timestamp(System.currentTimeMillis())
            .build());

    } catch (Exception e) {
        // ── Stripe threw an error ──────────────────────────────
        payment.setStatus(Payment.PaymentStatus.FAILED);
        payment.setFailureReason(e.getMessage());
        paymentRepository.save(payment);  // ← update DB: PENDING → FAILED

        // ── Publish payment.failed to Kafka ────────────────────
        kafkaTemplate.send("payment.failed", payment.getRideId(), PaymentEvent.builder()
            .eventType("payment.failed")
            .rideId(payment.getRideId())
            .failureReason(e.getMessage())
            .timestamp(System.currentTimeMillis())
            .build());
    }
}

// ── Circuit breaker fallback — called when Stripe is down ─────────────────
// After 50% failure rate, circuit opens for 30s
// All subsequent calls go here instead of hitting Stripe
private void stripePaymentFallback(Payment payment, Throwable t) {
    log.error("Circuit breaker OPEN. Stripe unavailable. Ride {} queued.", payment.getRideId());
    payment.setStatus(Payment.PaymentStatus.FAILED);
    payment.setFailureReason("Stripe unavailable - circuit breaker open");
    paymentRepository.save(payment);
    // In production: push to a retry queue here
}
```

**What happens:** Stripe is called. If it succeeds → payment goes to SUCCESS. If Stripe throws an error → payment goes to FAILED. If Stripe has been failing 50%+ of the time → circuit breaker opens, fallback method is called directly without even trying Stripe (prevents hammering a failing service).

---

## Step 4 — Saga: payment.failed closes the loop

**File:** `ride-service/service/PaymentEventConsumer.java`

```java
@KafkaListener(
    topics = "payment.failed",
    groupId = "ride-service-payment-consumer",
    containerFactory = "paymentEventKafkaListenerContainerFactory"
)
@Transactional
public void handlePaymentFailed(PaymentEvent event) {
    rideRepository.findById(event.getRideId()).ifPresent(ride -> {
        if (ride.getStatus() == Ride.RideStatus.COMPLETED) {
            ride.setStatus(Ride.RideStatus.PAYMENT_FAILED);  // ← compensating transaction
            rideRepository.save(ride);
            log.info("Ride {} → PAYMENT_FAILED", event.getRideId());
        }
    });
}
```

**Why this exists:** Without this, a ride would forever show `COMPLETED` in the DB even though the rider was never charged. The Saga pattern requires a compensating transaction — this is it.

---

## Full picture in one diagram

```
Driver calls POST /rides/{id}/complete
        │
        ▼
ride-service saves ride (COMPLETED) to PostgreSQL
        │
        ▼
kafkaTemplate.send("ride.completed")  ──► Kafka broker stores event
        │                                        │
        ▼                                        ▼
REST response returned              payment-service @KafkaListener wakes up
to driver app ✓                              │
(payment not done yet)               Idempotency check (DB)
                                             │
                                     Payment saved (PENDING)
                                             │
                                     Stripe API called
                                     (with circuit breaker)
                                        ├── SUCCESS
                                        │     ├── DB → SUCCESS
                                        │     └── Kafka: payment.success
                                        │              └── notification-service → rider notified
                                        └── FAILED
                                              ├── DB → FAILED
                                              └── Kafka: payment.failed
                                                         └── ride-service → ride → PAYMENT_FAILED
                                                         └── notification-service → rider notified
```

The key insight: `completeRide()` returns in milliseconds. The entire Stripe charge happens in the background, driven purely by Kafka events. The rider's app can poll `GET /api/payments/ride/{rideId}` to check if payment succeeded.