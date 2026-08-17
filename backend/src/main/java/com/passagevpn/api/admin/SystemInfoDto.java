package com.passagevpn.api.admin;

/** Host resource usage for the dashboard system card. */
public record SystemInfoDto(
    double cpuLoadPercent,
    long totalMemory,
    long freeMemory,
    long diskTotal,
    long diskFree,
    int availableProcessors) {}
