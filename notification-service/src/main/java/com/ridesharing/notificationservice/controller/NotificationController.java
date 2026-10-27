package com.ridesharing.notificationservice.controller;

import com.ridesharing.notificationservice.dto.NotificationRequest;
import com.ridesharing.notificationservice.dto.NotificationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    // Health check / status
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "notification-service"));
    }

    // Manual send notification (admin/internal use)
    @PostMapping("/send")
    public ResponseEntity<NotificationResponse> sendNotification(@RequestBody NotificationRequest req) {
        log.info("Manual notification -> [{}] {}: {}", req.getUserType(), req.getUserId(), req.getMessage());
        // Production: integrate FCM/Twilio here
        return ResponseEntity.ok(NotificationResponse.builder()
            .userId(req.getUserId())
            .message(req.getMessage())
            .status("SENT")
            .sentAt(LocalDateTime.now())
            .build());
    }
}
