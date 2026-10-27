package com.ridesharing.driverservice.service;

import com.ridesharing.driverservice.event.RideEvent;
import com.ridesharing.driverservice.model.Driver;
import com.ridesharing.driverservice.repository.DriverRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Service @RequiredArgsConstructor @Slf4j
public class RideEventConsumer {

    private final DriverLocationService locationService;
    private final DriverRepository driverRepository;

    /**
     * Listens to ride.requested events.
     * Finds nearby ONLINE drivers and logs (in production: push notification via FCM).
     */
    @KafkaListener(topics = "ride.requested", groupId = "driver-service",
                   containerFactory = "rideEventKafkaListenerContainerFactory")
    public void handleRideRequested(RideEvent event) {
        log.info("Ride requested [{}] from ({}, {})", event.getRideId(), event.getPickupLat(), event.getPickupLon());

        List<String> nearbyDriverIds = locationService.findNearbyDrivers(
            event.getPickupLat(), event.getPickupLon(), 5.0);

        if (nearbyDriverIds.isEmpty()) {
            log.warn("No drivers found near ride {}", event.getRideId());
            return;
        }

        // Notify nearby ONLINE drivers (in production: FCM push notification)
        nearbyDriverIds.forEach(driverId ->
            log.info("[NOTIFY DRIVER {}] New ride request {} nearby — fare: {}",
                driverId, event.getRideId(), event.getFare())
        );
    }

    /**
     * When a ride is accepted, mark driver as ON_RIDE.
     */
    @KafkaListener(topics = "ride.accepted", groupId = "driver-service",
                   containerFactory = "rideEventKafkaListenerContainerFactory")
    public void handleRideAccepted(RideEvent event) {
        if (event.getDriverId() == null) return;
        driverRepository.findById(event.getDriverId()).ifPresent(driver -> {
            driver.setStatus(Driver.DriverStatus.ON_RIDE);
            driverRepository.save(driver);
            log.info("Driver {} status -> ON_RIDE for ride {}", event.getDriverId(), event.getRideId());
        });
    }

    /**
     * When a ride is completed or cancelled, mark driver back as ONLINE.
     */
    @KafkaListener(topics = {"ride.completed", "ride.cancelled"}, groupId = "driver-service",
                   containerFactory = "rideEventKafkaListenerContainerFactory")
    public void handleRideCompleted(RideEvent event) {
        if (event.getDriverId() == null) return;
        driverRepository.findById(event.getDriverId()).ifPresent(driver -> {
            driver.setStatus(Driver.DriverStatus.ONLINE);
            driverRepository.save(driver);
            log.info("Driver {} status -> ONLINE after ride {}", event.getDriverId(), event.getRideId());
        });
    }
}
