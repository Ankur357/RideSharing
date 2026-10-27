package com.ridesharing.userservice.dto;
import com.ridesharing.userservice.model.User;
import lombok.*;
@Data @AllArgsConstructor
public class UserProfileResponse {
    private String id;
    private String email;
    private String fullName;
    private String phone;
    private User.Role role;
}
