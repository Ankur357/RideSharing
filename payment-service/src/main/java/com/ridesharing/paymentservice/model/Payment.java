package com.ridesharing.paymentservice.model;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity @Table(name = "payments") @Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    private String rideId;
    private String riderId;
    private String driverId;
    private BigDecimal amount;
    private String currency;
    private String stripePaymentIntentId;
    @Enumerated(EnumType.STRING)
    @Builder.Default private PaymentStatus status = PaymentStatus.PENDING;
    @Builder.Default private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt;
    private String failureReason;

    public enum PaymentStatus { PENDING, SUCCESS, FAILED, REFUNDED }
}
