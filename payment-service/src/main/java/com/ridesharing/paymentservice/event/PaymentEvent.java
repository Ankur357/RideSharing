package com.ridesharing.paymentservice.event;
import lombok.*;
import java.math.BigDecimal;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentEvent {
    private String eventType;  // payment.initiated, payment.success, payment.failed
    private String paymentId;
    private String rideId;
    private String riderId;
    private BigDecimal amount;
    private String failureReason;
    private long timestamp;
}
