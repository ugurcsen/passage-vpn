package com.passagevpn.api.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload for creating a group. */
public record GroupCreateRequest(
    @NotBlank @Size(max = 64) String name,
    @Size(max = 36) String parentId,
    @Size(max = 256) String description) {}
