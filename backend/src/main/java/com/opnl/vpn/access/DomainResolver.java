package com.opnl.vpn.access;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves a domain name to its current IPv4 addresses. Used by the access-rule engine at connect
 * time and by the dnsmasq config renderer, so the firewall matches exactly the addresses clients
 * get from the pinned DNS. Resolution runs on a short timeout so a slow or failing DNS lookup never
 * stalls a VPN connect.
 */
@Slf4j
@Component
public class DomainResolver {

  private static final long TIMEOUT_SECONDS = 2;

  private final ExecutorService executor = Executors.newCachedThreadPool();

  /**
   * Returns the domain's IPv4 addresses, or an empty set when the name cannot be resolved (the
   * caller then skips the rule). IPv6 literals are excluded because the per-client iptables rules
   * are IPv4-only.
   */
  public Set<String> resolve(String domain) {
    if (domain == null || domain.isBlank()) {
      return Set.of();
    }
    Future<Set<String>> future = executor.submit(() -> lookup(domain));
    try {
      return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      log.warn("DNS lookup for {} timed out after {}s; rule skipped", domain, TIMEOUT_SECONDS);
      return Set.of();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Set.of();
    } catch (ExecutionException e) {
      log.warn("Cannot resolve domain {}: {}", domain, e.getCause() == null ? e : e.getCause());
      return Set.of();
    }
  }

  private Set<String> lookup(String domain) throws UnknownHostException {
    Set<String> ips = new LinkedHashSet<>();
    for (InetAddress address : InetAddress.getAllByName(domain)) {
      String host = address.getHostAddress();
      if (host != null && !host.contains(":")) {
        ips.add(host);
      }
    }
    if (ips.isEmpty()) {
      log.debug("No IPv4 addresses for domain {}", domain);
    }
    return ips;
  }

  /** Resolves several domains in one call, keeping order; unresolvable names map to empty sets. */
  public java.util.Map<String, Set<String>> resolveAll(Set<String> domains) {
    java.util.LinkedHashMap<String, Set<String>> resolved = new java.util.LinkedHashMap<>();
    for (String domain : domains) {
      resolved.put(domain, resolve(domain));
    }
    return resolved;
  }
}
