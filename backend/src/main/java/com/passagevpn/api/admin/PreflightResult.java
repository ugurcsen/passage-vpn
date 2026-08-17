package com.passagevpn.api.admin;

import java.util.List;

/** Result of a preflight run: passed only when every check is green (FAIL blocks actions). */
public record PreflightResult(boolean passed, List<PreflightCheck> checks) {}
