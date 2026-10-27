package com.ridesharing.userservice.service;
import com.ridesharing.userservice.dto.*;
import com.ridesharing.userservice.model.User;
import com.ridesharing.userservice.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

@Service @RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    @Value("${jwt.secret}") private String jwtSecret;

    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.getEmail()))
            throw new RuntimeException("Email already registered");
        User user = User.builder()
            .email(req.getEmail())
            .password(passwordEncoder.encode(req.getPassword()))
            .fullName(req.getFullName())
            .phone(req.getPhone())
            .role(User.Role.RIDER)
            .build();
        user = userRepository.save(user);
        return new AuthResponse(generateToken(user), user.getId(), user.getEmail());
    }

    public AuthResponse login(String email, String password) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("Invalid credentials"));
        if (!passwordEncoder.matches(password, user.getPassword()))
            throw new RuntimeException("Invalid credentials");
        return new AuthResponse(generateToken(user), user.getId(), user.getEmail());
    }

    private String generateToken(User user) {
        return Jwts.builder()
            .setSubject(user.getId())
            .addClaims(Map.of("role", user.getRole().name(), "email", user.getEmail()))
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + 86400000))
            .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
            .compact();
    }
}
