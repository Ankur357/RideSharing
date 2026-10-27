package com.ridesharing.notificationservice.event;
import lombok.*;
import java.math.BigDecimal;
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentEvent {
    private String eventType;
    private String paymentId;
    private String rideId;
    private String riderId;
    private BigDecimal amount;
    private String failureReason;
    private long timestamp;
}
