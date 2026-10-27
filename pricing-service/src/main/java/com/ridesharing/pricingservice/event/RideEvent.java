package com.ridesharing.pricingservice.event;
import lombok.*;
import java.math.BigDecimal;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RideEvent {
    private String eventType;
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
