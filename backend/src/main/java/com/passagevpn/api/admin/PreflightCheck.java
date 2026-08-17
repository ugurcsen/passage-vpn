package com.passagevpn.api.admin;

/** One result line of a preflight run. */
public record PreflightCheck(String name, Status status, String detail) {
  public enum Status {
    PASS,
    WARN,
    FAIL
  }
}
