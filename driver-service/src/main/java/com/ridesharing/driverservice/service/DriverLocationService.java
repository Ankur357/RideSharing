package com.ridesharing.driverservice.service;

import com.ridesharing.driverservice.model.Driver;
import com.ridesharing.driverservice.repository.DriverRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor @Slf4j
public class DriverLocationService {

    private final RedisTemplate<String, String> redisTemplate;
    private final DriverRepository driverRepository;

    private static final String DRIVER_GEO_KEY = "drivers:locations";

    // Called every ~5 seconds by driver's mobile app
    public void updateDriverLocation(String driverId, double lat, double lon) {
        GeoOperations<String, String> geoOps = redisTemplate.opsForGeo();
        geoOps.add(DRIVER_GEO_KEY, new Point(lon, lat), driverId);
        log.info("Updated location for driver {} -> ({}, {})", driverId, lat, lon);
    }

    // Find available drivers within radiusKm of pickup point
    public List<String> findNearbyDrivers(double lat, double lon, double radiusKm) {
        GeoOperations<String, String> geoOps = redisTemplate.opsForGeo();
        Circle circle = new Circle(new Point(lon, lat), new Distance(radiusKm, Metrics.KILOMETERS));
        RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs
            .newGeoRadiusArgs()
            .includeDistance()
            .sortAscending()
            .limit(10);
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = geoOps.radius(DRIVER_GEO_KEY, circle, args);
        if (results == null) return List.of();
        return results.getContent().stream()
            .map(r -> r.getContent().getName())
            .collect(Collectors.toList());
    }

    public void removeDriverLocation(String driverId) {
        redisTemplate.opsForGeo().remove(DRIVER_GEO_KEY, driverId);
    }
}
