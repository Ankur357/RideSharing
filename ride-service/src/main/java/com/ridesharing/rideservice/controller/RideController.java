package com.ridesharing.rideservice.controller;

import com.ridesharing.rideservice.dto.RideRequest;
import com.ridesharing.rideservice.dto.RideResponse;
import com.ridesharing.rideservice.model.Ride;
import com.ridesharing.rideservice.repository.RideRepository;
import com.ridesharing.rideservice.service.RideService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/rides")
@RequiredArgsConstructor
@Tag(name = "Ride", description = "Ride lifecycle management — request, accept, start, complete, cancel")
@SecurityRequirement(name = "bearerAuth")
public class RideController {

    private final RideService rideService;
    private final RideRepository rideRepository;

    @PostMapping("/request")
    @Operation(summary = "Request a ride", description = "Creates a ride request, stores in Redis with 60s TTL, publishes to Kafka")
    @ApiResponse(responseCode = "200", description = "Ride requested — awaiting driver")
    @ApiResponse(responseCode = "400", description = "Invalid coordinates or addresses")
    public ResponseEntity<RideResponse> requestRide(
            @RequestHeader("X-User-Id") String riderId,
            @Valid @RequestBody RideRequest req) {
        return ResponseEntity.ok(RideResponse.from(rideService.requestRide(riderId, req)));
    }

    @PostMapping("/{rideId}/accept")
    @Operation(summary = "Accept a ride (Driver)", description = "Driver accepts a ride. Fails if Redis TTL has expired (60s window).")
    @ApiResponse(responseCode = "400", description = "Ride already accepted or expired")
    public ResponseEntity<RideResponse> acceptRide(
            @PathVariable String rideId,
            @RequestHeader("X-User-Id") String driverId) {
        return ResponseEntity.ok(RideResponse.from(rideService.acceptRide(rideId, driverId)));
    }

    @PostMapping("/{rideId}/start")
    @Operation(summary = "Start a ride (Driver)", description = "Marks ride as STARTED. Only the assigned driver can start.")
    public ResponseEntity<RideResponse> startRide(
            @PathVariable String rideId,
            @RequestHeader("X-User-Id") String driverId) {
        return ResponseEntity.ok(RideResponse.from(rideService.startRide(rideId, driverId)));
    }

    @PostMapping("/{rideId}/complete")
    @Operation(summary = "Complete a ride (Driver)", description = "Marks ride COMPLETED, publishes ride.completed event which triggers payment")
    public ResponseEntity<RideResponse> completeRide(
            @PathVariable String rideId,
            @RequestHeader("X-User-Id") String driverId) {
        return ResponseEntity.ok(RideResponse.from(rideService.completeRide(rideId, driverId)));
    }

    @PostMapping("/{rideId}/cancel")
    @Operation(summary = "Cancel a ride", description = "Cancels a ride. Cannot cancel a ride that is already STARTED.")
    public ResponseEntity<RideResponse> cancelRide(
            @PathVariable String rideId,
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(RideResponse.from(rideService.cancelRide(rideId, userId)));
    }

    @GetMapping("/{rideId}")
    @Operation(summary = "Get ride details by ID")
    public ResponseEntity<RideResponse> getRide(@PathVariable String rideId) {
        return rideRepository.findById(rideId)
            .map(RideResponse::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/my-rides")
    @Operation(summary = "Get rider's ride history", description = "Returns all rides for the authenticated rider, newest first")
    public ResponseEntity<List<RideResponse>> getMyRides(@RequestHeader("X-User-Id") String riderId) {
        return ResponseEntity.ok(
            rideRepository.findByRiderIdOrderByCreatedAtDesc(riderId)
                .stream().map(RideResponse::from).collect(Collectors.toList()));
    }

    @GetMapping("/driver/active")
    @Operation(summary = "Get driver's active rides", description = "Returns rides in DRIVER_ASSIGNED status for the authenticated driver")
    public ResponseEntity<List<RideResponse>> getDriverActiveRides(@RequestHeader("X-User-Id") String driverId) {
        return ResponseEntity.ok(
            rideRepository.findByDriverIdAndStatus(driverId, Ride.RideStatus.DRIVER_ASSIGNED)
                .stream().map(RideResponse::from).collect(Collectors.toList()));
    }
}
