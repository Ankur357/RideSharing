package com.ridesharing.driverservice.dto;

import com.ridesharing.driverservice.model.Driver;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DriverAuthResponse {
    private String token;
    private String driverId;
    private String email;
    private String fullName;
    private String vehicleNumber;
    private String vehicleType;
    private Driver.DriverStatus status;
}
