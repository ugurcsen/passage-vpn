package com.opnl.vpn.api.admin;

import java.time.Instant;

/** Metadata about a stored backup archive. */
public record BackupInfo(String name, long sizeBytes, Instant createdAt) {}
