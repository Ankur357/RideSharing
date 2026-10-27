package com.ridesharing.driverservice.service;

import com.ridesharing.driverservice.dto.*;
import com.ridesharing.driverservice.model.Driver;
import com.ridesharing.driverservice.repository.DriverRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DriverService {

    private final DriverRepository driverRepository;
    private final DriverLocationService locationService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${jwt.secret}")
    private String jwtSecret;

    private static final long JWT_EXPIRY_MS = 24 * 60 * 60 * 1000L; // 24 hours

    // ── Register ──────────────────────────────────────────────────────────────

    public DriverAuthResponse register(RegisterDriverRequest req) {
        if (driverRepository.findByEmail(req.getEmail()).isPresent())
            throw new RuntimeException("Email already registered");

        Driver driver = Driver.builder()
            .email(req.getEmail())
            .password(passwordEncoder.encode(req.getPassword()))
            .fullName(req.getFullName())
            .phone(req.getPhone())
            .vehicleNumber(req.getVehicleNumber())
            .vehicleType(req.getVehicleType())
            .status(Driver.DriverStatus.OFFLINE)
            .build();

        driver = driverRepository.save(driver);
        log.info("Driver registered: {}", driver.getEmail());

        String token = generateToken(driver);
        return toAuthResponse(driver, token);
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    public DriverAuthResponse login(String email, String password) {
        Driver driver = driverRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("Invalid email or password"));

        if (!passwordEncoder.matches(password, driver.getPassword()))
            throw new RuntimeException("Invalid email or password");

        log.info("Driver logged in: {}", email);
        String token = generateToken(driver);
        return toAuthResponse(driver, token);
    }

    // ── Location & Status ─────────────────────────────────────────────────────

    public DriverResponse goOnline(String driverId, double lat, double lon) {
        Driver driver = findById(driverId);
        driver.setStatus(Driver.DriverStatus.ONLINE);
        locationService.updateDriverLocation(driverId, lat, lon);
        return toResponse(driverRepository.save(driver));
    }

    public DriverResponse goOffline(String driverId) {
        Driver driver = findById(driverId);
        driver.setStatus(Driver.DriverStatus.OFFLINE);
        locationService.removeDriverLocation(driverId);
        return toResponse(driverRepository.save(driver));
    }

    public void updateLocation(String driverId, LocationUpdateRequest req) {
        locationService.updateDriverLocation(driverId, req.getLatitude(), req.getLongitude());
    }

    public DriverResponse getProfile(String driverId) {
        return toResponse(findById(driverId));
    }

    // ── JWT ───────────────────────────────────────────────────────────────────

    private String generateToken(Driver driver) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", "DRIVER");
        claims.put("email", driver.getEmail());

        return Jwts.builder()
            .setClaims(claims)
            .setSubject(driver.getId())           // userId = driverId in subject
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + JWT_EXPIRY_MS))
            .signWith(getSigningKey(), SignatureAlgorithm.HS256)
            .compact();
    }

    private Key getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Driver findById(String id) {
        return driverRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Driver not found: " + id));
    }

    private DriverResponse toResponse(Driver d) {
        return new DriverResponse(d.getId(), d.getEmail(), d.getFullName(),
            d.getPhone(), d.getVehicleNumber(), d.getVehicleType(), d.getStatus());
    }

    private DriverAuthResponse toAuthResponse(Driver d, String token) {
        return new DriverAuthResponse(token, d.getId(), d.getEmail(),
            d.getFullName(), d.getVehicleNumber(), d.getVehicleType(), d.getStatus());
    }
}
