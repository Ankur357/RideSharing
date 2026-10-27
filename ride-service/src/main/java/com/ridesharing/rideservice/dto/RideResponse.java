package com.ridesharing.rideservice.dto;
import com.ridesharing.rideservice.model.Ride;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @AllArgsConstructor @NoArgsConstructor @Builder
public class RideResponse {
    private String id;
    private String riderId;
    private String driverId;
    private String pickupAddress;
    private String dropAddress;
    private Ride.RideStatus status;
    private BigDecimal estimatedFare;
    private BigDecimal finalFare;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public static RideResponse from(Ride ride) {
        return RideResponse.builder()
            .id(ride.getId()).riderId(ride.getRiderId()).driverId(ride.getDriverId())
            .pickupAddress(ride.getPickupAddress()).dropAddress(ride.getDropAddress())
            .status(ride.getStatus()).estimatedFare(ride.getEstimatedFare())
            .finalFare(ride.getFinalFare()).createdAt(ride.getCreatedAt())
            .startedAt(ride.getStartedAt()).completedAt(ride.getCompletedAt())
            .build();
    }
}
