package com.passagevpn.profile;

/** The four Access Server-style connection profile types. */
public enum ProfileType {
  /** Locked to a user: embeds their client cert/key and prompts for password. */
  USER_LOCKED,
  /** Cert-only profile: embeds cert/key, no password prompt (cert-only daemon). */
  AUTO_LOGIN,
  /** Like USER_LOCKED but the remote endpoint is fixed to the configured host. */
  SERVER_LOCKED,
  /** No certificate; username/password only (runs on a cert-not-required daemon). */
  GENERIC
}
