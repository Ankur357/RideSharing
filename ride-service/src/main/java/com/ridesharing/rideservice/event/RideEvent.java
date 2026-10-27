package com.ridesharing.rideservice.event;
import lombok.*;
import java.math.BigDecimal;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RideEvent {
    private String eventType;   // ride.requested, ride.accepted, ride.started, ride.completed, ride.cancelled
    private String rideId;
    private String riderId;
    private String driverId;
    private double pickupLat;
    private double pickupLon;
    private double dropLat;
    private double dropLon;
    private BigDecimal fare;
    private long timestamp;
}
