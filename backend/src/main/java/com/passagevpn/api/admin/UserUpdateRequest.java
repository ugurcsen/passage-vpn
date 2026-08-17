package com.passagevpn.api.admin;

import com.passagevpn.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Payload for updating a user. Null password keeps the current one. */
public record UserUpdateRequest(
    @Size(max = 128) String fullName,
    @Email @Size(max = 128) String email,
    User.Role role,
    Boolean banned,
    @Size(min = 8, max = 128) String password,
    List<String> groupIds,
    List<String> adminGroupIds) {}
