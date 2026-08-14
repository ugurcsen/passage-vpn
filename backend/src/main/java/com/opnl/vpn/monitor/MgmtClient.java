package com.opnl.vpn.monitor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Persistent TCP connection to one OpenVPN daemon's management interface (plain-text protocol,
 * {@code management 0.0.0.0 <port>}). Async events (lines starting with {@code >}) are discarded;
 * monitoring state is gathered from periodic {@code status 3} polls instead, which is the single
 * authoritative source for live clients and their byte counters.
 *
 * <p>All socket I/O is synchronized per connection so commands cannot interleave with a poll.
 * Network failures are non-fatal: callers treat {@code null}/{@code false} results as "daemon
 * unreachable" and the connection is transparently re-established on the next use.
 */
@Slf4j
public class MgmtClient implements Closeable {

  private final String host;
  private final int port;
  private final int daemonIndex;

  private Socket socket;
  private BufferedReader reader;
  private BufferedWriter writer;

  public MgmtClient(String host, int port, int daemonIndex) {
    this.host = host;
    this.port = port;
    this.daemonIndex = daemonIndex;
  }

  public int daemonIndex() {
    return daemonIndex;
  }

  public boolean isConnected() {
    return socket != null && socket.isConnected() && !socket.isClosed();
  }

  /** Opens (or re-opens) the connection. Returns false when the daemon is unreachable. */
  public synchronized boolean connect() {
    closeQuietly();
    try {
      Socket fresh = new Socket();
      fresh.connect(new InetSocketAddress(host, port), 1_000);
      fresh.setSoTimeout(5_000);
      this.reader =
          new BufferedReader(new InputStreamReader(fresh.getInputStream(), StandardCharsets.UTF_8));
      this.writer =
          new BufferedWriter(
              new OutputStreamWriter(fresh.getOutputStream(), StandardCharsets.UTF_8));
      this.socket = fresh;
      log.info("Connected to management interface {}:{} (daemon {})", host, port, daemonIndex);
      return true;
    } catch (IOException e) {
      log.debug(
          "Management {}:{} unreachable (daemon {}): {}", host, port, daemonIndex, e.getMessage());
      return false;
    }
  }

  /** Polls {@code status 3} and returns the parsed snapshot, or {@code null} when unreachable. */
  public synchronized MgmtStatus status() {
    if (!isConnected() && !connect()) {
      return null;
    }
    try {
      writer.write("status 3\n");
      writer.flush();
      List<String> lines = new ArrayList<>();
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith(">")) {
          continue; // async event, not part of the status block
        }
        if (line.equals("END")) {
          break;
        }
        lines.add(line);
      }
      if (line == null) {
        // EOF: the daemon closed the connection (e.g. it restarted). Drop the
        // dead socket so the next poll transparently reconnects; a stale
        // "connected" socket would otherwise serve incomplete status forever.
        log.debug("Management connection closed by daemon {} (EOF)", daemonIndex);
        closeQuietly();
        return null;
      }
      return MgmtStatus.parse(lines, Instant.now());
    } catch (IOException e) {
      log.debug("Management read failed on daemon {}: {}", daemonIndex, e.getMessage());
      closeQuietly();
      return null;
    }
  }

  /**
   * Disconnects the client with the given common name via the management {@code kill} command.
   * Returns whether the daemon acknowledged the kill.
   */
  public synchronized boolean kill(String commonName) {
    if (!isConnected() && !connect()) {
      return false;
    }
    try {
      writer.write("kill " + commonName + "\n");
      writer.flush();
      String response = reader.readLine();
      if (response == null) {
        // EOF: daemon went away mid-command; drop the dead socket.
        closeQuietly();
        return false;
      }
      return response.startsWith("SUCCESS:");
    } catch (IOException e) {
      log.debug("Management kill failed on daemon {}: {}", daemonIndex, e.getMessage());
      closeQuietly();
      return false;
    }
  }

  /**
   * Sends the given signal (e.g. {@code SIGHUP}) to the daemon via the management {@code signal}
   * command. Returns whether the daemon acknowledged it.
   */
  public synchronized boolean signal(String signal) {
    if (!isConnected() && !connect()) {
      return false;
    }
    try {
      writer.write("signal " + signal + "\n");
      writer.flush();
      String response = reader.readLine();
      if (response == null) {
        // EOF: daemon went away mid-command; drop the dead socket.
        closeQuietly();
        return false;
      }
      return response.startsWith("SUCCESS:");
    } catch (IOException e) {
      log.debug("Management signal failed on daemon {}: {}", daemonIndex, e.getMessage());
      closeQuietly();
      return false;
    }
  }

  private void closeQuietly() {
    try {
      if (reader != null) {
        reader.close();
      }
    } catch (IOException ignored) {
      // already closing
    }
    try {
      if (writer != null) {
        writer.close();
      }
    } catch (IOException ignored) {
      // already closing
    }
    try {
      if (socket != null) {
        socket.close();
      }
    } catch (IOException ignored) {
      // already closing
    }
    socket = null;
    reader = null;
    writer = null;
  }

  @Override
  public synchronized void close() {
    closeQuietly();
  }
}
