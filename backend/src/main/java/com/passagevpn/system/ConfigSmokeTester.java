package com.passagevpn.system;

import java.nio.file.Path;

/** Smoke-tests an OpenVPN config so a reload cannot take down a daemon with a broken file. */
public interface ConfigSmokeTester {
  /**
   * Runs the daemon config through a real openvpn parse; never blocks longer than a few seconds.
   */
  Result test(Path configPath);

  /** Outcome of a single config smoke test. */
  record Result(Status status, String detail) {
    public enum Status {
      PASS,
      WARN,
      FAIL
    }
  }
}
