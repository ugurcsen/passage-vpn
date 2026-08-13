package com.opnl.vpn.access;

import java.net.Inet6Address;
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
 * Resolves a domain name to its current IPv4 and IPv6 addresses. Used by the access-rule engine at
 * connect time and by the dnsmasq config renderer, so the firewall matches exactly the addresses
 * clients get from the pinned DNS. Resolution runs on a short timeout so a slow or failing DNS
 * lookup never stalls a VPN connect.
 */
@Slf4j
@Component
public class DomainResolver {

  private static final long TIMEOUT_SECONDS = 2;

  private final ExecutorService executor = Executors.newCachedThreadPool();

  /**
   * Returns the domain's IPv4 addresses, or an empty set when the name cannot be resolved (the
   * caller then skips the rule). IPv6 literals are excluded because the caller only builds IPv4
   * iptables rules.
   */
  public Set<String> resolve(String domain) {
    return lookup(domain, false);
  }

  /**
   * Returns the domain's IPv6 addresses (global unicast only; link-local, unspecified and
   * documentation addresses are ignored), or an empty set when the name has no usable IPv6 record.
   * Used for the per-client ip6tables rules and dnsmasq AAAA pins when dual-stack is enabled.
   */
  public Set<String> resolveIpv6(String domain) {
    return lookup(domain, true);
  }

  private Set<String> lookup(String domain, boolean ipv6) {
    if (domain == null || domain.isBlank()) {
      return Set.of();
    }
    Future<Set<String>> future = executor.submit(() -> lookupNow(domain, ipv6));
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

  private Set<String> lookupNow(String domain, boolean ipv6) throws UnknownHostException {
    Set<String> ips = new LinkedHashSet<>();
    for (InetAddress address : InetAddress.getAllByName(domain)) {
      if (ipv6 != (address instanceof Inet6Address)) {
        continue;
      }
      String host = address.getHostAddress();
      if (host == null || host.contains("%")) {
        continue;
      }
      if (ipv6 && unusableIpv6(address, host)) {
        continue;
      }
      ips.add(host);
    }
    if (ips.isEmpty()) {
      log.debug("No {} addresses for domain {}", ipv6 ? "IPv6" : "IPv4", domain);
    }
    return ips;
  }

  private boolean unusableIpv6(InetAddress address, String host) {
    if (address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
      return true;
    }
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isMulticastAddress()) {
      return true;
    }
    return host.equalsIgnoreCase("::")
        || host.regionMatches(true, 0, "2001:db8", 0, 9)
        || host.regionMatches(true, 0, "fd", 0, 2)
        || host.regionMatches(true, 0, "fe80", 0, 4);
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
