package com.ridesharing.driverservice.repository;
import com.ridesharing.driverservice.model.Driver;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface DriverRepository extends JpaRepository<Driver, String> {
    Optional<Driver> findByEmail(String email);
}
