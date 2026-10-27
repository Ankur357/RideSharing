package com.ridesharing.notificationservice.dto;
import lombok.Data;
@Data
public class NotificationRequest {
    private String userId;
    private String userType; // RIDER or DRIVER
    private String title;
    private String message;
}
