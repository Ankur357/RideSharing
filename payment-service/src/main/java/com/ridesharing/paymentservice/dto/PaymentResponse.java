package com.ridesharing.paymentservice.dto;
import com.ridesharing.paymentservice.model.Payment;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @AllArgsConstructor @NoArgsConstructor @Builder
public class PaymentResponse {
    private String id;
    private String rideId;
    private String riderId;
    private BigDecimal amount;
    private String currency;
    private Payment.PaymentStatus status;
    private String stripePaymentIntentId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String failureReason;

    public static PaymentResponse from(Payment p) {
        return PaymentResponse.builder()
            .id(p.getId()).rideId(p.getRideId()).riderId(p.getRiderId())
            .amount(p.getAmount()).currency(p.getCurrency())
            .status(p.getStatus()).stripePaymentIntentId(p.getStripePaymentIntentId())
            .createdAt(p.getCreatedAt()).updatedAt(p.getUpdatedAt())
            .failureReason(p.getFailureReason())
            .build();
    }
}
