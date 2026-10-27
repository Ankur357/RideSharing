package com.ridesharing.notificationservice.dto;
import lombok.*;
import java.time.LocalDateTime;
@Data @AllArgsConstructor @Builder
public class NotificationResponse {
    private String userId;
    private String message;
    private String status; // SENT, FAILED
    private LocalDateTime sentAt;
}
