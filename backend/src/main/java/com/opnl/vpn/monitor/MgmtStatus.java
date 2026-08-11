package com.opnl.vpn.monitor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Parsed result of a {@code status 3} query against the OpenVPN management interface.
 *
 * <p>The management protocol is plain text: {@code status 3} returns a block of comma-separated
 * lines terminated by {@code END}. The {@code TITLE} line announces the OpenVPN version and
 * whether data channel offload ({@code [DCO]}) is active.
 */
public record MgmtStatus(
    Instant at, String title, int numClients, List<MgmtClientStatus> clients, boolean dco) {

  /** One {@code CLIENT_LIST} row: a live tunnel as reported by the daemon. */
  public record MgmtClientStatus(
      String commonName,
      String realAddress,
      String virtualAddress,
      long bytesIn,
      long bytesOut,
      Instant connectedSince,
      long clientId) {}

  /** Parses the non-{@code END} lines of a {@code status 3} response. */
  public static MgmtStatus parse(List<String> lines, Instant at) {
    String title = null;
    int numClients = 0;
    boolean dco = false;
    List<MgmtClientStatus> clients = new ArrayList<>();
    for (String line : lines) {
      if (line.startsWith("TITLE,")) {
        title = line.substring("TITLE,".length());
        dco = title.contains("[DCO]");
      } else if (line.startsWith("TIME,")) {
        String[] parts = line.split(",", -1);
        if (parts.length >= 4) {
          try {
            numClients = Integer.parseInt(parts[3]);
          } catch (NumberFormatException ignored) {
            // keep default
          }
        }
      } else if (line.startsWith("CLIENT_LIST,")) {
        MgmtClientStatus client = parseClient(line);
        if (client != null) {
          clients.add(client);
        }
      }
    }
    return new MgmtStatus(at, title, numClients, List.copyOf(clients), dco);
  }

  private static MgmtClientStatus parseClient(String line) {
    // CLIENT_LIST,cn,real,vaddr,v6addr,brecv,bsent,since,sincetime,username,cid,peerid,cipher
    String[] parts = line.split(",", -1);
    if (parts.length < 7) {
      return null;
    }
    Instant since = null;
    if (parts.length > 7 && !parts[7].isBlank()) {
      try {
        since = Instant.ofEpochSecond(Long.parseLong(parts[7]));
      } catch (NumberFormatException ignored) {
        // keep null
      }
    }
    long clientId = -1;
    if (parts.length > 10 && !parts[10].isBlank()) {
      try {
        clientId = Long.parseLong(parts[10]);
      } catch (NumberFormatException ignored) {
        // keep -1
      }
    }
    return new MgmtClientStatus(
        parts[1], parts[2], parts[3], parseLong(parts[5]), parseLong(parts[6]), since, clientId);
  }

  private static long parseLong(String value) {
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
