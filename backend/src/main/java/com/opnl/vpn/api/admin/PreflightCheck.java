package com.opnl.vpn.api.admin;

/** One result line of a preflight run. */
public record PreflightCheck(String name, Status status, String detail) {
  public enum Status {
    PASS,
    WARN,
    FAIL
  }
}
