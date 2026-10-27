package com.ridesharing.driverservice.controller;

import com.ridesharing.driverservice.dto.*;
import com.ridesharing.driverservice.service.DriverLocationService;
import com.ridesharing.driverservice.service.DriverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/drivers")
@RequiredArgsConstructor
@Tag(name = "Driver", description = "Driver registration, login, location tracking, and availability APIs")
public class DriverController {

    private final DriverService driverService;
    private final DriverLocationService locationService;

    @PostMapping("/register")
    @Operation(
        summary = "Register a new driver",
        description = "Creates a driver account with vehicle details. Returns a JWT token — use it for all subsequent requests."
    )
    @ApiResponse(responseCode = "200", description = "Driver registered — JWT token returned")
    @ApiResponse(responseCode = "400", description = "Validation error or email already exists")
    public ResponseEntity<DriverAuthResponse> register(@Valid @RequestBody RegisterDriverRequest req) {
        return ResponseEntity.ok(driverService.register(req));
    }

    @PostMapping("/login")
    @Operation(
        summary = "Driver login",
        description = "Authenticates a driver with email and password. Returns a JWT token valid for 24 hours. " +
                      "Use this token as: Authorization: Bearer <token>"
    )
    @ApiResponse(responseCode = "200", description = "Login successful — JWT token returned")
    @ApiResponse(responseCode = "401", description = "Invalid email or password")
    public ResponseEntity<DriverAuthResponse> login(@Valid @RequestBody DriverLoginRequest req) {
        return ResponseEntity.ok(driverService.login(req.getEmail(), req.getPassword()));
    }

    @GetMapping("/me")
    @Operation(summary = "Get driver profile", description = "Returns the authenticated driver's profile and current status")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<DriverResponse> getProfile(@RequestHeader("X-User-Id") String driverId) {
        return ResponseEntity.ok(driverService.getProfile(driverId));
    }

    @PostMapping("/location")
    @Operation(
        summary = "Update driver GPS location",
        description = "Updates driver's current lat/lon in Redis GeoSet. Should be called every 5 seconds by the driver app."
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "400", description = "Invalid coordinates (lat -90/90, lon -180/180)")
    public ResponseEntity<Void> updateLocation(
            @RequestHeader("X-User-Id") String driverId,
            @Valid @RequestBody LocationUpdateRequest req) {
        driverService.updateLocation(driverId, req);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/online")
    @Operation(
        summary = "Go online",
        description = "Sets driver status to ONLINE and registers their location in Redis GeoSet. Drivers must be ONLINE to receive ride requests."
    )
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<DriverResponse> goOnline(
            @RequestHeader("X-User-Id") String driverId,
            @Valid @RequestBody LocationUpdateRequest req) {
        return ResponseEntity.ok(driverService.goOnline(driverId, req.getLatitude(), req.getLongitude()));
    }

    @PostMapping("/offline")
    @Operation(
        summary = "Go offline",
        description = "Sets driver status to OFFLINE and removes them from Redis GeoSet. They will no longer appear in nearby driver searches."
    )
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<DriverResponse> goOffline(@RequestHeader("X-User-Id") String driverId) {
        return ResponseEntity.ok(driverService.goOffline(driverId));
    }

    @GetMapping("/nearby")
    @Operation(
        summary = "Find nearby online drivers",
        description = "Returns driver IDs within given radius (km) using Redis GEORADIUS. O(log N) complexity."
    )
    public ResponseEntity<List<String>> getNearbyDrivers(
            @RequestParam double lat,
            @RequestParam double lon,
            @RequestParam(defaultValue = "5.0") double radius) {
        return ResponseEntity.ok(locationService.findNearbyDrivers(lat, lon, radius));
    }
}
