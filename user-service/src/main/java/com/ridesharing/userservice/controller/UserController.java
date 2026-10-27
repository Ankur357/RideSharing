package com.ridesharing.userservice.controller;

import com.ridesharing.userservice.dto.*;
import com.ridesharing.userservice.model.User;
import com.ridesharing.userservice.repository.UserRepository;
import com.ridesharing.userservice.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "User", description = "User registration, login and profile APIs")
public class UserController {

    private final UserService userService;
    private final UserRepository userRepository;

    @PostMapping("/register")
    @Operation(summary = "Register a new rider", description = "Creates a new rider account and returns a JWT token")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Registered successfully"),
        @ApiResponse(responseCode = "400", description = "Validation error or email already exists")
    })
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.ok(userService.register(req));
    }

    @PostMapping("/login")
    @Operation(summary = "Login", description = "Authenticates a rider and returns a JWT token")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Login successful"),
        @ApiResponse(responseCode = "401", description = "Invalid credentials")
    })
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(userService.login(req.getEmail(), req.getPassword()));
    }

    @GetMapping("/me")
    @Operation(summary = "Get current user profile", description = "Returns the authenticated user's profile. Requires Authorization header.")
    @ApiResponse(responseCode = "200", description = "Profile returned successfully")
    public ResponseEntity<UserProfileResponse> getMe(@RequestHeader("X-User-Id") String userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        return ResponseEntity.ok(new UserProfileResponse(
            user.getId(), user.getEmail(), user.getFullName(), user.getPhone(), user.getRole()));
    }
}
