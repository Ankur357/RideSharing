package com.ridesharing.paymentservice.repository;
import com.ridesharing.paymentservice.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface PaymentRepository extends JpaRepository<Payment, String> {
    Optional<Payment> findByRideId(String rideId);
    Optional<Payment> findByStripePaymentIntentId(String intentId);
}
