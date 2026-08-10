package com.opnl.vpn.api.admin;

import java.util.List;

/** Aggregate counts and recent activity for the dashboard. */
public record DashboardDto(
    long users,
    long groups,
    long activeCertificates,
    int activeConnections,
    long runningDaemons,
    long totalDaemons,
    List<ConnectionDto> recentConnections) {}
