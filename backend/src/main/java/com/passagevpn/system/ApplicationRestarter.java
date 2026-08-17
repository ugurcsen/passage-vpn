package com.passagevpn.system;

/** Schedules a graceful backend shutdown so the container restart policy brings it back up. */
@FunctionalInterface
public interface ApplicationRestarter {
  void scheduleRestart();
}
