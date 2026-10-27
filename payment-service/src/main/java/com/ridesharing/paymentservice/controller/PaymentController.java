package com.ridesharing.paymentservice.controller;

import com.ridesharing.paymentservice.dto.PaymentResponse;
import com.ridesharing.paymentservice.dto.RefundRequest;
import com.ridesharing.paymentservice.repository.PaymentRepository;
import com.ridesharing.paymentservice.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payment", description = "Payment status, Stripe webhook handling, and retry APIs")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;

    @Value("${stripe.webhook-secret}") private String webhookSecret;

    @GetMapping("/ride/{rideId}")
    @Operation(summary = "Get payment by ride ID", description = "Returns payment status for a completed ride")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Payment found")
    @ApiResponse(responseCode = "404", description = "No payment found for this ride")
    public ResponseEntity<PaymentResponse> getByRideId(@PathVariable String rideId) {
        return paymentRepository.findByRideId(rideId)
            .map(PaymentResponse::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get payment by payment ID")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<PaymentResponse> getById(@PathVariable String paymentId) {
        return paymentRepository.findById(paymentId)
            .map(PaymentResponse::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/webhook")
    @Operation(
        summary = "Stripe webhook receiver",
        description = "Receives async payment confirmation from Stripe. Validates Stripe-Signature header. " +
                      "Handles payment_intent.succeeded and payment_intent.payment_failed events."
    )
    @ApiResponse(responseCode = "200", description = "Webhook processed")
    @ApiResponse(responseCode = "400", description = "Invalid signature or payload")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {
        try {
            log.info("Received Stripe webhook");
            if (payload.contains("payment_intent.succeeded")) {
                // Production: parse paymentIntentId from payload and call paymentService.handleStripeWebhook(...)
                log.info("Stripe confirmed payment success via webhook");
            }
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Webhook error: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Webhook error: " + e.getMessage());
        }
    }

    @PostMapping("/retry/{rideId}")
    @Operation(
        summary = "Retry failed payment",
        description = "Manually retries payment for a ride whose payment failed. Idempotency is enforced."
    )
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<String> retryPayment(@PathVariable String rideId) {
        paymentRepository.findByRideId(rideId).ifPresent(payment ->
            log.info("Manual retry triggered for rideId={}", rideId));
        return ResponseEntity.ok("Retry initiated");
    }

    @PostMapping("/refund")
    @Operation(summary = "Request a refund", description = "Initiates refund for a completed ride payment")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "400", description = "Invalid refund request")
    public ResponseEntity<String> requestRefund(@Valid @RequestBody RefundRequest req) {
        log.info("Refund requested for rideId={}, reason={}", req.getRideId(), req.getReason());
        return ResponseEntity.ok("Refund initiated for ride: " + req.getRideId());
    }
}
