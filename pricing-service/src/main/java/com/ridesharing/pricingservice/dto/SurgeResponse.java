package com.ridesharing.pricingservice.dto;
import lombok.*;
import java.math.BigDecimal;
@Data @AllArgsConstructor
public class SurgeResponse {
    private String zone;
    private BigDecimal surgeMultiplier;
    private boolean isSurgeActive;
}
