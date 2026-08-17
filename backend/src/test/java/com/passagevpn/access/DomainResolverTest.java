package com.passagevpn.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DomainResolverTest {

  private final DomainResolver resolver = new DomainResolver();

  @Test
  void resolveReturnsEmptySetForBlankDomain() {
    assertThat(resolver.resolve(null)).isEmpty();
    assertThat(resolver.resolve("")).isEmpty();
    assertThat(resolver.resolve("   ")).isEmpty();
  }

  @Test
  void resolveReturnsIpv4LiteralAddresses() {
    assertThat(resolver.resolve("8.8.8.8")).containsExactly("8.8.8.8");
  }

  @Test
  void resolveSkipsIpv6Addresses() {
    assertThat(resolver.resolve("::1")).isEmpty();
  }

  @Test
  void resolveReturnsEmptySetWhenLookupFails() {
    assertThat(resolver.resolve("does-not-exist.invalid")).isEmpty();
  }

  @Test
  void resolveIpv6ReturnsGlobalUnicastAddresses() throws Exception {
    Set<String> result = resolver.resolveIpv6("2606:4700:4700::1111");
    assertThat(result).hasSize(1);
    String address = result.iterator().next();
    // getHostAddress() textual form differs across platforms, so compare numerically.
    assertThat(InetAddress.getByName(address))
        .isEqualTo(InetAddress.getByName("2606:4700:4700::1111"));
  }

  @Test
  void resolveIpv6SkipsNonGlobalUnicastAddresses() {
    assertThat(resolver.resolveIpv6("::1")).isEmpty();
    assertThat(resolver.resolveIpv6("fe80::1")).isEmpty();
    assertThat(resolver.resolveIpv6("fd00::1")).isEmpty();
  }

  @Test
  void resolveIpv6SkipsIpv4Addresses() {
    assertThat(resolver.resolveIpv6("8.8.8.8")).isEmpty();
  }

  @Test
  void resolveAllKeepsEveryInputDomainAndMapsFailuresToEmpty() {
    Set<String> domains = Set.of("8.8.8.8", "does-not-exist.invalid", "8.8.4.4");

    Map<String, Set<String>> result = resolver.resolveAll(domains);

    assertThat(result.keySet()).containsExactlyInAnyOrderElementsOf(domains);
    assertThat(result.get("8.8.8.8")).containsExactly("8.8.8.8");
    assertThat(result.get("8.8.4.4")).containsExactly("8.8.4.4");
    assertThat(result.get("does-not-exist.invalid")).isEmpty();
  }
}
