package com.passagevpn.api.admin;

/** Result of a backend restart request. The response returns before the shutdown happens. */
public record RestartResult(String message) {}
