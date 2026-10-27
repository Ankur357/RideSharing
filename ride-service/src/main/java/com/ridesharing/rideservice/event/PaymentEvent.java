package com.ridesharing.rideservice.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEvent {
    private String eventType;      // payment.success | payment.failed
    private String paymentId;
    private String rideId;
    private String riderId;
    private BigDecimal amount;
    private String failureReason;
    private long timestamp;
}
