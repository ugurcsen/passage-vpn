package com.opnl.vpn.api.admin;

import java.time.Instant;

/** Admin-facing group representation. */
public record GroupDto(
    String id,
    String name,
    String parentId,
    String description,
    long memberCount,
    Instant createdAt) {}
