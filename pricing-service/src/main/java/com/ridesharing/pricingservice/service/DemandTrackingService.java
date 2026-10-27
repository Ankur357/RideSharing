package com.ridesharing.pricingservice.service;

import com.ridesharing.pricingservice.event.RideEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Listens to ride events and updates demand/supply counters in Redis
 * so SurgePricingService can compute surge multipliers.
 */
@Service @RequiredArgsConstructor @Slf4j
public class DemandTrackingService {

    private final SurgePricingService surgePricingService;

    // Simple zone resolver — in production use H3 geospatial index
    private String resolveZone(double lat, double lon) {
        if (lat > 28.7) return "zone_north";
        if (lat < 28.5) return "zone_south";
        if (lon > 77.3) return "zone_east";
        if (lon < 77.1) return "zone_west";
        return "zone_center";
    }

    @KafkaListener(topics = "ride.requested", groupId = "pricing-service",
                   containerFactory = "rideEventKafkaListenerContainerFactory")
    public void onRideRequested(RideEvent event) {
        String zone = resolveZone(event.getPickupLat(), event.getPickupLon());
        surgePricingService.incrementDemand(zone);
        log.info("Demand++ for zone {} (ride {})", zone, event.getRideId());
    }
}
