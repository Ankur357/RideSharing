package com.ridesharing.pricingservice.controller;

import com.ridesharing.pricingservice.dto.*;
import com.ridesharing.pricingservice.service.SurgePricingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/pricing")
@RequiredArgsConstructor
@Tag(name = "Pricing", description = "Fare estimation and real-time surge pricing per city zone")
public class PricingController {

    private final SurgePricingService surgePricingService;

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    @PostMapping("/estimate")
    @Operation(
        summary = "Estimate fare (POST)",
        description = "Calculates fare using Haversine distance + current surge multiplier for the zone. " +
                      "Base fare ₹30 + ₹12/km, multiplied by surge (1.0–3.0x)."
    )
    @ApiResponse(responseCode = "200", description = "Fare estimate returned")
    @ApiResponse(responseCode = "400", description = "Invalid coordinates")
    public ResponseEntity<FareEstimateResponse> estimateFare(@Valid @RequestBody FareEstimateRequest req) {
        String zone = req.getZone() != null ? req.getZone() : "zone_center";
        double distance = haversine(req.getPickupLat(), req.getPickupLon(), req.getDropLat(), req.getDropLon());
        double duration = distance * 3;
        BigDecimal fare = surgePricingService.estimateFare(distance, duration, zone);
        BigDecimal surge = surgePricingService.getSurgeMultiplier(zone);
        return ResponseEntity.ok(FareEstimateResponse.builder()
            .estimatedFare(fare).surgeMultiplier(surge)
            .distanceKm(Math.round(distance * 10.0) / 10.0)
            .durationMin(Math.round(duration * 10.0) / 10.0)
            .zone(zone).currency("INR")
            .build());
    }

    @GetMapping("/estimate")
    @Operation(
        summary = "Estimate fare (GET)",
        description = "Quick fare estimate by providing distance and duration directly"
    )
    public ResponseEntity<FareEstimateResponse> estimateFareGet(
            @RequestParam double distance,
            @RequestParam(defaultValue = "10") double duration,
            @RequestParam(defaultValue = "zone_center") String zone) {
        BigDecimal fare = surgePricingService.estimateFare(distance, duration, zone);
        BigDecimal surge = surgePricingService.getSurgeMultiplier(zone);
        return ResponseEntity.ok(FareEstimateResponse.builder()
            .estimatedFare(fare).surgeMultiplier(surge)
            .distanceKm(distance).durationMin(duration)
            .zone(zone).currency("INR")
            .build());
    }

    @GetMapping("/surge/{zone}")
    @Operation(
        summary = "Get current surge multiplier",
        description = "Returns the live surge multiplier for a zone. Updated every 30s by @Scheduled job."
    )
    public ResponseEntity<SurgeResponse> getSurge(@PathVariable String zone) {
        BigDecimal surge = surgePricingService.getSurgeMultiplier(zone);
        return ResponseEntity.ok(new SurgeResponse(zone, surge, surge.doubleValue() > 1.0));
    }
}
