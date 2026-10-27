package com.ridesharing.rideservice.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.math.BigDecimal;

@Entity @Table(name = "rides") @Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Ride {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    private String riderId;
    private String driverId;
    private double pickupLat;
    private double pickupLon;
    private double dropLat;
    private double dropLon;
    private String pickupAddress;
    private String dropAddress;
    @Enumerated(EnumType.STRING)
    @Builder.Default private RideStatus status = RideStatus.REQUESTED;
    private BigDecimal estimatedFare;
    private BigDecimal finalFare;
    @Builder.Default private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public enum RideStatus {
        REQUESTED,
        DRIVER_ASSIGNED,
        STARTED,
        COMPLETED,
        CANCELLED,
        PAYMENT_PENDING,
        PAYMENT_FAILED  // Saga compensating state — set when payment-service reports payment.failed
    }
}
