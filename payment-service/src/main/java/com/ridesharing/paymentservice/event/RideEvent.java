package com.ridesharing.paymentservice.event;
import lombok.*;
import java.math.BigDecimal;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RideEvent {
    private String eventType;
    private String rideId;
    private String riderId;
    private String driverId;
    private BigDecimal fare;
    private long timestamp;
}
