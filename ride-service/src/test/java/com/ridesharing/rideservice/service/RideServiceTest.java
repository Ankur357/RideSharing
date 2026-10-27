package com.ridesharing.rideservice.service;

import com.ridesharing.rideservice.dto.RideRequest;
import com.ridesharing.rideservice.event.RideEvent;
import com.ridesharing.rideservice.model.Ride;
import com.ridesharing.rideservice.repository.RideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RideService Unit Tests")
class RideServiceTest {

    @Mock private RideRepository rideRepository;
    @Mock private KafkaTemplate<String, RideEvent> kafkaTemplate;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks private RideService rideService;

    private static final String RIDER_ID  = "rider-uuid-001";
    private static final String DRIVER_ID = "driver-uuid-001";
    private static final String RIDE_ID   = "ride-uuid-001";

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // requestRide tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("requestRide: should save ride, set Redis TTL, and publish Kafka event")
    void requestRide_shouldSaveAndPublishEvent() {
        // Arrange
        RideRequest req = buildRideRequest();
        Ride savedRide = buildRide(RIDE_ID, RIDER_ID, Ride.RideStatus.REQUESTED);
        when(rideRepository.save(any(Ride.class))).thenReturn(savedRide);

        // Act
        Ride result = rideService.requestRide(RIDER_ID, req);

        // Assert — ride saved
        verify(rideRepository).save(any(Ride.class));

        // Assert — Redis key set with 60s TTL
        verify(valueOperations).set(
            eq("ride:request:" + RIDE_ID),
            eq(RIDER_ID),
            eq(60L),
            eq(TimeUnit.SECONDS)
        );

        // Assert — Kafka event published to ride.requested
        ArgumentCaptor<RideEvent> eventCaptor = ArgumentCaptor.forClass(RideEvent.class);
        verify(kafkaTemplate).send(eq("ride.requested"), eq(RIDE_ID), eventCaptor.capture());
        RideEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo("ride.requested");
        assertThat(event.getRiderId()).isEqualTo(RIDER_ID);
        assertThat(event.getRideId()).isEqualTo(RIDE_ID);

        assertThat(result.getStatus()).isEqualTo(Ride.RideStatus.REQUESTED);
    }

    @Test
    @DisplayName("requestRide: fare should be at least base fare (₹30) for any distance")
    void requestRide_fareShouldIncludeBaseFare() {
        // Arrange — same pickup and drop (0km distance)
        RideRequest req = new RideRequest();
        req.setPickupLat(19.076);
        req.setPickupLon(72.877);
        req.setDropLat(19.076);   // same as pickup
        req.setDropLon(72.877);
        req.setPickupAddress("A");
        req.setDropAddress("B");

        ArgumentCaptor<Ride> rideCaptor = ArgumentCaptor.forClass(Ride.class);
        when(rideRepository.save(rideCaptor.capture())).thenAnswer(inv -> {
            Ride r = inv.getArgument(0);
            r = Ride.builder()
                .id(RIDE_ID).riderId(RIDER_ID)
                .estimatedFare(r.getEstimatedFare())
                .status(Ride.RideStatus.REQUESTED)
                .build();
            return r;
        });

        // Act
        rideService.requestRide(RIDER_ID, req);

        // Assert — estimated fare >= ₹30 (base fare)
        Ride capturedRide = rideCaptor.getValue();
        assertThat(capturedRide.getEstimatedFare()).isGreaterThanOrEqualTo(BigDecimal.valueOf(30));
    }

    @Test
    @DisplayName("requestRide: fare for 10km should be ₹30 + ₹12*10 = ₹150 approximately")
    void requestRide_fareCalculation_10kmShouldBeApprox150() {
        // Mumbai coordinates approx 10km apart
        RideRequest req = new RideRequest();
        req.setPickupLat(19.0760);
        req.setPickupLon(72.8777);
        req.setDropLat(19.1600);  // ~10km north
        req.setDropLon(72.8777);
        req.setPickupAddress("Bandra");
        req.setDropAddress("Andheri");

        ArgumentCaptor<Ride> rideCaptor = ArgumentCaptor.forClass(Ride.class);
        when(rideRepository.save(rideCaptor.capture())).thenAnswer(inv -> {
            Ride r = inv.getArgument(0);
            return Ride.builder().id(RIDE_ID).riderId(RIDER_ID)
                .estimatedFare(r.getEstimatedFare()).status(Ride.RideStatus.REQUESTED).build();
        });

        rideService.requestRide(RIDER_ID, req);

        Ride captured = rideCaptor.getValue();
        // 10km * ₹12 + ₹30 = ₹150 ± 20 (haversine approximation)
        assertThat(captured.getEstimatedFare())
            .isBetween(BigDecimal.valueOf(120), BigDecimal.valueOf(180));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // acceptRide tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("acceptRide: should assign driver and publish ride.accepted event")
    void acceptRide_shouldAssignDriverAndPublishEvent() {
        // Arrange
        Ride ride = buildRide(RIDE_ID, RIDER_ID, Ride.RideStatus.REQUESTED);
        when(rideRepository.findById(RIDE_ID)).thenReturn(Optional.of(ride));
        when(redisTemplate.hasKey("ride:request:" + RIDE_ID)).thenReturn(true);
        when(rideRepository.save(any())).thenReturn(ride);

        // Act
        Ride result = rideService.acceptRide(RIDE_ID, DRIVER_ID);

        // Assert — driver assigned and status updated
        verify(rideRepository).save(argThat(r ->
            DRIVER_ID.equals(r.getDriverId()) && r.getStatus() == Ride.RideStatus.DRIVER_ASSIGNED
        ));

        // Assert — Redis key deleted (ride no longer pending)
        verify(redisTemplate).delete("ride:request:" + RIDE_ID);

        // Assert — Kafka event published
        ArgumentCaptor<RideEvent> captor = ArgumentCaptor.forClass(RideEvent.class);
        verify(kafkaTemplate).send(eq("ride.accepted"), eq(RIDE_ID), captor.capture());
        assertThat(captor.getValue().getDriverId()).isEqualTo(DRIVER_ID);
    }

    @Test
    @DisplayName("acceptRide: should throw exception when Redis TTL has expired (60s window missed)")
    void acceptRide_shouldThrowWhenRideExpired() {
        // Arrange — ride exists in DB but Redis key has expired
        Ride ride = buildRide(RIDE_ID, RIDER_ID, Ride.RideStatus.REQUESTED);
        when(rideRepository.findById(RIDE_ID)).thenReturn(Optional.of(ride));
        when(redisTemplate.hasKey("ride:request:" + RIDE_ID)).thenReturn(false); // TTL expired

        // Act & Assert
        assertThatThrownBy(() -> rideService.acceptRide(RIDE_ID, DRIVER_ID))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("expired");

        // Ensure no Kafka event was published
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any(RideEvent.class));
    }

    @Test
    @DisplayName("acceptRide: should throw exception when ride is no longer in REQUESTED status")
    void acceptRide_shouldThrowWhenAlreadyAccepted() {
        // Arrange — ride already accepted by another driver
        Ride ride = buildRide(RIDE_ID, RIDER_ID, Ride.RideStatus.DRIVER_ASSIGNED);
        when(rideRepository.findById(RIDE_ID)).thenReturn(Optional.of(ride));

        // Act & Assert
        assertThatThrownBy(() -> rideService.acceptRide(RIDE_ID, DRIVER_ID))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("no longer available");
    }

    @Test
    @DisplayName("acceptRide: should throw RuntimeException when ride not found")
    void acceptRide_shouldThrowWhenRideNotFound() {
        when(rideRepository.findById(RIDE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rideService.acceptRide(RIDE_ID, DRIVER_ID))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Ride not found");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // cancelRide tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancelRide: should cancel a REQUESTED ride and publish ride.cancelled")
    void cancelRide_shouldCancelRequestedRide() {
        Ride ride = buildRide(RIDE_ID, RIDER_ID, Ride.RideStatus.REQUESTED);
        when(rideRepository.findById(RIDE_ID)).thenReturn(Optional.of(ride));
        when(rideRepository.save(any())).thenReturn(ride);

        rideService.cancelRide(RIDE_ID, RIDER_ID);

        verify(rideRepository).save(argThat(r -> r.getStatus() == Ride.RideStatus.CANCELLED));
        verify(kafkaTemplate).send(eq("ride.cancelled"), eq(RIDE_ID), any(RideEvent.class));
    }

    @Test
    @DisplayName("cancelRide: should throw exception when ride is already STARTED")
    void cancelRide_shouldThrowWhenRideStarted() {
        Ride ride = buildRide(RIDE_ID, RIDER_ID, Ride.RideStatus.STARTED);
        when(rideRepository.findById(RIDE_ID)).thenReturn(Optional.of(ride));

        assertThatThrownBy(() -> rideService.cancelRide(RIDE_ID, RIDER_ID))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Cannot cancel");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private RideRequest buildRideRequest() {
        RideRequest req = new RideRequest();
        req.setPickupLat(19.0760);
        req.setPickupLon(72.8777);
        req.setDropLat(19.0880);
        req.setDropLon(72.8950);
        req.setPickupAddress("Bandra West");
        req.setDropAddress("Andheri East");
        return req;
    }

    private Ride buildRide(String id, String riderId, Ride.RideStatus status) {
        return Ride.builder()
            .id(id)
            .riderId(riderId)
            .driverId(status == Ride.RideStatus.DRIVER_ASSIGNED ? DRIVER_ID : null)
            .pickupLat(19.076).pickupLon(72.877)
            .dropLat(19.088).dropLon(72.895)
            .pickupAddress("Bandra").dropAddress("Andheri")
            .estimatedFare(BigDecimal.valueOf(150))
            .status(status)
            .build();
    }
}
