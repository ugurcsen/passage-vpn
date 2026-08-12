package com.opnl.vpn.api.admin;

/** Payload for setting a group's static IP pool (null/blank clears it). */
public record StaticIpPoolRequest(String pool) {}
