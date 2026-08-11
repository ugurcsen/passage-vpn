package com.opnl.vpn.api.admin;

import java.time.Instant;

/** One aggregate traffic sample for the dashboard chart. */
public record TrafficPointDto(
    Instant at, long bytesInPerSec, long bytesOutPerSec, int activeConnections) {}
