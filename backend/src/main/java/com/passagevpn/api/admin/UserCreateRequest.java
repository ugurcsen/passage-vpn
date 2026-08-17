package com.passagevpn.api.admin;

import com.passagevpn.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Payload for creating a user. */
public record UserCreateRequest(
    @NotBlank @Size(max = 64) String username,
    @NotBlank @Size(min = 8, max = 128) String password,
    @Size(max = 128) String fullName,
    @Email @Size(max = 128) String email,
    User.Role role,
    List<String> groupIds,
    List<String> adminGroupIds) {}
