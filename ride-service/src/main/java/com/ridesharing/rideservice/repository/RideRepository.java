package com.ridesharing.rideservice.repository;
import com.ridesharing.rideservice.model.Ride;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface RideRepository extends JpaRepository<Ride, String> {
    List<Ride> findByRiderIdOrderByCreatedAtDesc(String riderId);
    List<Ride> findByDriverIdAndStatus(String driverId, Ride.RideStatus status);
}
