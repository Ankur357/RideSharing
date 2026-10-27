package com.ridesharing.pricingservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.TimeUnit;

@Service @RequiredArgsConstructor @Slf4j
public class SurgePricingService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String SURGE_KEY_PREFIX = "surge:zone:";
    private static final String DEMAND_KEY_PREFIX = "demand:zone:";
    private static final String SUPPLY_KEY_PREFIX = "supply:zone:";
    private static final int SURGE_TTL_SECONDS = 30;

    // Called when a ride is requested to increment demand counter
    public void incrementDemand(String zoneId) {
        redisTemplate.opsForValue().increment(DEMAND_KEY_PREFIX + zoneId);
        redisTemplate.expire(DEMAND_KEY_PREFIX + zoneId, 5, TimeUnit.MINUTES);
    }

    // Called when a driver goes online in a zone
    public void incrementSupply(String zoneId) {
        redisTemplate.opsForValue().increment(SUPPLY_KEY_PREFIX + zoneId);
        redisTemplate.expire(SUPPLY_KEY_PREFIX + zoneId, 5, TimeUnit.MINUTES);
    }

    // Recalculate surge every 30 seconds
    @Scheduled(fixedDelay = 30000)
    public void recalculateSurge() {
        // In production: iterate all active zone keys
        // For demo: just show how zone surge is computed
        String[] zones = {"zone_north", "zone_south", "zone_east", "zone_west", "zone_center"};
        for (String zone : zones) {
            String demandStr = redisTemplate.opsForValue().get(DEMAND_KEY_PREFIX + zone);
            String supplyStr = redisTemplate.opsForValue().get(SUPPLY_KEY_PREFIX + zone);
            long demand = demandStr != null ? Long.parseLong(demandStr) : 0;
            long supply = supplyStr != null ? Long.parseLong(supplyStr) : 1;
            double ratio = supply == 0 ? 3.0 : (double) demand / supply;
            double surge = Math.min(3.0, Math.max(1.0, ratio)); // cap at 3x
            redisTemplate.opsForValue().set(
                SURGE_KEY_PREFIX + zone,
                String.valueOf(surge),
                SURGE_TTL_SECONDS, TimeUnit.SECONDS
            );
            if (surge > 1.0) log.info("Surge {}x active for zone {}", surge, zone);
        }
    }

    public BigDecimal getSurgeMultiplier(String zoneId) {
        String val = redisTemplate.opsForValue().get(SURGE_KEY_PREFIX + zoneId);
        return val != null
            ? BigDecimal.valueOf(Double.parseDouble(val)).setScale(1, RoundingMode.HALF_UP)
            : BigDecimal.ONE;
    }

    public BigDecimal estimateFare(double distanceKm, double durationMin, String zoneId) {
        BigDecimal baseFare = BigDecimal.valueOf(30);
        BigDecimal perKm = BigDecimal.valueOf(12).multiply(BigDecimal.valueOf(distanceKm));
        BigDecimal perMin = BigDecimal.valueOf(2).multiply(BigDecimal.valueOf(durationMin));
        BigDecimal surge = getSurgeMultiplier(zoneId);
        return baseFare.add(perKm).add(perMin).multiply(surge).setScale(0, RoundingMode.HALF_UP);
    }
}
