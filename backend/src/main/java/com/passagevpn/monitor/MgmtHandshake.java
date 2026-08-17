package com.passagevpn.monitor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Implements the OpenVPN management interface greeting and password handshake. When the daemon has
 * {@code management <ip> <port> <passwordfile>} configured it emits {@code ENTER PASSWORD:} (with
 * no trailing newline) as its greeting; the client must answer with the bare password on a line.
 * Without a password file the daemon greets with a {@code >INFO:...} line instead.
 *
 * <p>Because a daemon without a password never emits a prompt, the handshake uses a short greeting
 * window: if no prompt arrives within it, the connection is treated as password-less (and a
 * configured-but-unneeded password is treated as a hard failure so misconfiguration cannot be
 * silently accepted).
 */
public final class MgmtHandshake {

  private static final int GREETING_TIMEOUT_MS = 2_000;
  private static final int NORMAL_TIMEOUT_MS = 5_000;

  /** Thrown when the daemon rejected or the protocol did not match expectations. */
  public static final class AuthException extends RuntimeException {
    public AuthException(String message) {
      super(message);
    }
  }

  private MgmtHandshake() {}

  /**
   * Consumes the greeting and authenticates when requested. On success the socket read timeout is
   * left at the normal operating value.
   *
   * @param password the management password to present, or {@code null} when none configured
   * @throws AuthException when the password was rejected, missing, or wrongly configured
   */
  public static void authenticate(
      Socket socket, BufferedReader reader, BufferedWriter writer, String password)
      throws IOException {
    try {
      socket.setSoTimeout(GREETING_TIMEOUT_MS);
      String line = nextSignificantLine(reader);
      if (line == null) {
        // EOF or the greeting window elapsed without a prompt.
        if (password != null && !password.isBlank()) {
          throw new AuthException(
              "A management password is configured but the daemon did not require one");
        }
        return;
      }
      if ("ENTER PASSWORD:".equalsIgnoreCase(line)) {
        if (password == null || password.isBlank()) {
          throw new AuthException(
              "Management interface requires a password but none is configured");
        }
        writer.write(password + "\n");
        writer.flush();
        String response = reader.readLine();
        if (response == null) {
          throw new AuthException("Management closed the connection during password exchange");
        }
        String trimmed = response.trim();
        if (!trimmed.startsWith("SUCCESS:")) {
          throw new AuthException("Management authentication failed: " + trimmed);
        }
      } else if (password != null && !password.isBlank()) {
        throw new AuthException(
            "A management password is configured but the daemon did not require one");
      }
    } catch (SocketTimeoutException e) {
      // No password prompt arrived within the greeting window.
      if (password != null && !password.isBlank()) {
        throw new AuthException(
            "A management password is configured but the daemon did not require one");
      }
    } finally {
      socket.setSoTimeout(NORMAL_TIMEOUT_MS);
    }
  }

  /**
   * Returns the first significant greeting element: the {@code ENTER PASSWORD:} prompt (which the
   * real daemon sends without a trailing newline) or the first non-empty, non-INFO line. Empty
   * lines and {@code *INFO:}/{@code >INFO:} banner lines are skipped. Returns {@code null} when the
   * connection closes before a complete greeting; the {@code GREETING_TIMEOUT_MS} socket timeout is
   * left to the caller.
   */
  private static String nextSignificantLine(BufferedReader reader) throws IOException {
    StringBuilder sb = new StringBuilder();
    int c;
    while ((c = reader.read()) != -1) {
      sb.append((char) c);
      String s = sb.toString();
      String trimmed = s.trim();
      if ("ENTER PASSWORD:".equalsIgnoreCase(trimmed)) {
        return trimmed;
      }
      if (s.endsWith("\n")) {
        if (trimmed.isEmpty() || trimmed.startsWith(">INFO:") || trimmed.startsWith("*INFO:")) {
          sb.setLength(0);
          continue;
        }
        return trimmed;
      }
    }
    return sb.toString().trim().isEmpty() ? null : sb.toString().trim();
  }
}
