package com.passagevpn.api.admin;

/**
 * Result of a restore operation. The backend must be restarted for a replaced database to take
 * effect because the running connection pool keeps the previous file handle open.
 */
public record RestoreResult(boolean restartRequired, String message) {}
