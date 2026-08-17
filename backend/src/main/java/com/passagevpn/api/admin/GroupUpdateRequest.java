package com.passagevpn.api.admin;

import jakarta.validation.constraints.Size;

/** Payload for updating a group. */
public record GroupUpdateRequest(
    @Size(max = 64) String name, @Size(max = 256) String description) {}
