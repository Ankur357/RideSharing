package com.ridesharing.rideservice.service;

import com.ridesharing.rideservice.dto.RideRequest;
import com.ridesharing.rideservice.event.RideEvent;
import com.ridesharing.rideservice.model.Ride;
import com.ridesharing.rideservice.repository.RideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service @RequiredArgsConstructor @Slf4j
public class RideService {

    private final RideRepository rideRepository;
    private final KafkaTemplate<String, RideEvent> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String RIDE_REQUEST_PREFIX = "ride:request:";
    private static final int RIDE_TTL_SECONDS = 60; // expires if no driver accepts

    @Transactional
    public Ride requestRide(String riderId, RideRequest req) {
        Ride ride = Ride.builder()
            .riderId(riderId)
            .pickupLat(req.getPickupLat())
            .pickupLon(req.getPickupLon())
            .dropLat(req.getDropLat())
            .dropLon(req.getDropLon())
            .pickupAddress(req.getPickupAddress())
            .dropAddress(req.getDropAddress())
            .estimatedFare(estimateFare(req))
            .status(Ride.RideStatus.REQUESTED)
            .build();

        ride = rideRepository.save(ride);

        // Store in Redis with TTL so it auto-expires if no driver picks it up
        redisTemplate.opsForValue().set(
            RIDE_REQUEST_PREFIX + ride.getId(),
            riderId,
            RIDE_TTL_SECONDS, TimeUnit.SECONDS
        );

        // Publish to Kafka so Driver Service can notify nearby drivers
        RideEvent event = RideEvent.builder()
            .eventType("ride.requested")
            .rideId(ride.getId())
            .riderId(riderId)
            .pickupLat(req.getPickupLat())
            .pickupLon(req.getPickupLon())
            .dropLat(req.getDropLat())
            .dropLon(req.getDropLon())
            .fare(ride.getEstimatedFare())
            .timestamp(System.currentTimeMillis())
            .build();

        kafkaTemplate.send("ride.requested", ride.getId(), event);
        log.info("Ride {} requested by rider {}", ride.getId(), riderId);
        return ride;
    }

    @Transactional
    public Ride acceptRide(String rideId, String driverId) {
        Ride ride = rideRepository.findById(rideId)
            .orElseThrow(() -> new RuntimeException("Ride not found"));

        if (ride.getStatus() != Ride.RideStatus.REQUESTED)
            throw new RuntimeException("Ride is no longer available");

        // Check Redis — if TTL expired, ride is stale
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(RIDE_REQUEST_PREFIX + rideId)))
            throw new RuntimeException("Ride request has expired");

        ride.setDriverId(driverId);
        ride.setStatus(Ride.RideStatus.DRIVER_ASSIGNED);
        redisTemplate.delete(RIDE_REQUEST_PREFIX + rideId); // remove from pending
        ride = rideRepository.save(ride);

        kafkaTemplate.send("ride.accepted", rideId, RideEvent.builder()
            .eventType("ride.accepted")
            .rideId(rideId)
            .riderId(ride.getRiderId())
            .driverId(driverId)
            .timestamp(System.currentTimeMillis())
            .build());

        return ride;
    }

    @Transactional
    public Ride startRide(String rideId, String driverId) {
        Ride ride = getRideAndValidateDriver(rideId, driverId);
        ride.setStatus(Ride.RideStatus.STARTED);
        ride.setStartedAt(LocalDateTime.now());
        ride = rideRepository.save(ride);

        kafkaTemplate.send("ride.started", rideId, RideEvent.builder()
            .eventType("ride.started").rideId(rideId)
            .riderId(ride.getRiderId()).driverId(driverId)
            .timestamp(System.currentTimeMillis()).build());
        return ride;
    }

    @Transactional
    public Ride completeRide(String rideId, String driverId) {
        Ride ride = getRideAndValidateDriver(rideId, driverId);
        ride.setStatus(Ride.RideStatus.COMPLETED);
        ride.setCompletedAt(LocalDateTime.now());
        ride.setFinalFare(ride.getEstimatedFare());
        ride = rideRepository.save(ride);

        // Publish ride.completed -> Payment Service will charge the rider
        kafkaTemplate.send("ride.completed", rideId, RideEvent.builder()
            .eventType("ride.completed").rideId(rideId)
            .riderId(ride.getRiderId()).driverId(driverId)
            .fare(ride.getFinalFare())
            .timestamp(System.currentTimeMillis()).build());
        return ride;
    }

    @Transactional
    public Ride cancelRide(String rideId, String requesterId) {
        Ride ride = rideRepository.findById(rideId)
            .orElseThrow(() -> new RuntimeException("Ride not found"));
        if (ride.getStatus() == Ride.RideStatus.STARTED)
            throw new RuntimeException("Cannot cancel a ride in progress");
        ride.setStatus(Ride.RideStatus.CANCELLED);
        redisTemplate.delete(RIDE_REQUEST_PREFIX + rideId);
        ride = rideRepository.save(ride);

        kafkaTemplate.send("ride.cancelled", rideId, RideEvent.builder()
            .eventType("ride.cancelled").rideId(rideId)
            .riderId(ride.getRiderId()).driverId(ride.getDriverId())
            .timestamp(System.currentTimeMillis()).build());
        return ride;
    }

    private Ride getRideAndValidateDriver(String rideId, String driverId) {
        Ride ride = rideRepository.findById(rideId)
            .orElseThrow(() -> new RuntimeException("Ride not found"));
        if (!driverId.equals(ride.getDriverId()))
            throw new RuntimeException("Unauthorized");
        return ride;
    }

    // Basic fare estimate: base fare + per-km rate
    private BigDecimal estimateFare(RideRequest req) {
        double distanceKm = haversine(req.getPickupLat(), req.getPickupLon(), req.getDropLat(), req.getDropLon());
        double fare = 30.0 + (distanceKm * 12.0); // base ₹30 + ₹12/km
        return BigDecimal.valueOf(Math.round(fare));
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon/2) * Math.sin(dLon/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }
}
