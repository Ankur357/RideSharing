package com.ridesharing.driverservice.model;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name = "drivers") @Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Driver {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @Column(unique = true, nullable = false) private String email;
    @Column(nullable = false) private String password;
    private String fullName;
    private String phone;
    private String vehicleNumber;
    private String vehicleType;
    @Enumerated(EnumType.STRING)
    @Builder.Default private DriverStatus status = DriverStatus.OFFLINE;
    public enum DriverStatus { ONLINE, OFFLINE, ON_RIDE }
}
