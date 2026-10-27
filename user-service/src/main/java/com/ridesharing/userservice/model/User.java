package com.ridesharing.userservice.model;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name = "users") @Data @NoArgsConstructor @AllArgsConstructor @Builder
public class User {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @Column(unique = true, nullable = false) private String email;
    @Column(nullable = false) private String password;
    private String fullName;
    private String phone;
    @Enumerated(EnumType.STRING) private Role role;
    public enum Role { RIDER, ADMIN }
}
