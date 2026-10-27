package com.ridesharing.notificationservice.service;

import com.ridesharing.notificationservice.event.PaymentEvent;
import com.ridesharing.notificationservice.event.RideEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor @Slf4j
public class NotificationService {

    @KafkaListener(
        topics = {"ride.requested", "ride.accepted", "ride.started", "ride.completed", "ride.cancelled"},
        groupId = "notification-service",
        containerFactory = "rideKafkaListenerContainerFactory")
    public void handleRideEvents(RideEvent event) {
        switch (event.getEventType()) {
            case "ride.requested"  -> notifyRider(event.getRiderId(),
                "Looking for a driver near you...");
            case "ride.accepted"   -> {
                notifyRider(event.getRiderId(), "Driver found! Your driver is on the way.");
                notifyDriver(event.getDriverId(), "You accepted ride " + event.getRideId() + ". Head to pickup.");
            }
            case "ride.started"    -> notifyRider(event.getRiderId(),
                "Your ride has started. Enjoy your trip!");
            case "ride.completed"  -> notifyRider(event.getRiderId(),
                "Ride completed! Fare: Rs." + event.getFare() + ". Processing payment...");
            case "ride.cancelled"  -> {
                notifyRider(event.getRiderId(), "Your ride has been cancelled.");
                if (event.getDriverId() != null)
                    notifyDriver(event.getDriverId(), "Ride " + event.getRideId() + " was cancelled.");
            }
        }
    }

    @KafkaListener(
        topics = {"payment.success", "payment.failed"},
        groupId = "notification-service",
        containerFactory = "paymentKafkaListenerContainerFactory")
    public void handlePaymentEvents(PaymentEvent event) {
        switch (event.getEventType()) {
            case "payment.success" -> notifyRider(event.getRiderId(),
                "Payment of Rs." + event.getAmount() + " successful! Thank you for riding with us.");
            case "payment.failed"  -> notifyRider(event.getRiderId(),
                "Payment failed: " + event.getFailureReason() + ". Please update your payment method.");
        }
    }

    private void notifyRider(String riderId, String message) {
        log.info("[PUSH -> RIDER {}] {}", riderId, message);
        // Production: firebaseMessaging.send(Message.builder().setToken(fcmToken).setNotification(...).build())
    }

    private void notifyDriver(String driverId, String message) {
        log.info("[PUSH -> DRIVER {}] {}", driverId, message);
    }
}
