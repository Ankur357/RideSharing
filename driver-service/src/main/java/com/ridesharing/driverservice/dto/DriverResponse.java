package com.ridesharing.driverservice.dto;
import com.ridesharing.driverservice.model.Driver;
import lombok.*;
@Data @AllArgsConstructor
public class DriverResponse {
    private String id;
    private String email;
    private String fullName;
    private String phone;
    private String vehicleNumber;
    private String vehicleType;
    private Driver.DriverStatus status;
}
