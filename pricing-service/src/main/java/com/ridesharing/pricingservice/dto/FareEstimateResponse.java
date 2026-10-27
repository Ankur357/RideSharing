package com.ridesharing.pricingservice.dto;
import lombok.*;
import java.math.BigDecimal;
@Data @AllArgsConstructor @Builder
public class FareEstimateResponse {
    private BigDecimal estimatedFare;
    private BigDecimal surgeMultiplier;
    private double distanceKm;
    private double durationMin;
    private String zone;
    private String currency;
}
