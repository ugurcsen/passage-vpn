package com.opnl.vpn.api.admin;

import java.util.List;

/** Result of a daemon reload: how many acknowledged the signal and which could not be verified. */
public record ReloadResult(int signaled, int total, List<Integer> failed) {}
