package com.passagevpn.profile;

import com.passagevpn.network.ServerConfig.Protocol;
import java.util.List;
import org.springframework.stereotype.Component;

/** Renders OpenVPN client profile text (.ovpn) for the four Access Server-style profile types. */
@Component
public class OvpnGenerator {

  private static final String CLIENT_PREAMBLE =
      """
      client
      dev tun
      __REMOTE_BLOCK__
      resolv-retry infinite
      nobind
      persist-key
      persist-tun
      remote-cert-tls server
      cipher AES-256-GCM
      data-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305
      auth SHA256
      __AUTH_USER_PASS__
      __IPV6__
      <tls-crypt>
      __TLS_CRYPT__
      </tls-crypt>
      __CERT_BLOCK__
      verb 3
      """;

  /** One endpoint a profile can connect to. */
  public record Endpoint(String host, int port, Protocol proto, boolean ipv6Enabled) {}

  /**
   * Generates the profile. Locked types embed the client certificate and key; GENERIC uses
   * username/password against a client-cert-not-required daemon.
   *
   * <p>When {@code multiRemote} is true and more than one endpoint is given, every endpoint is
   * emitted as a {@code remote HOST PORT PROTO} line and {@code remote-random} is added so clients
   * load-balance across all daemons serving the profile type. Otherwise only the first endpoint is
   * used and a single {@code proto} + {@code remote} pair is rendered.
   *
   * @param endpoints candidate endpoints in daemon-index order; the effective public host is
   *     already resolved by the caller.
   * @param mfaChallenge when true and the profile uses password auth, an interactive {@code
   *     static-challenge} prompt is added so the client can supply a TOTP code. A blank response is
   *     tolerated by the backend when MFA is not required.
   */
  public String render(
      ProfileType type,
      List<Endpoint> endpoints,
      String caCert,
      String taKey,
      String cert,
      String key,
      boolean mfaChallenge,
      boolean multiRemote) {
    if (endpoints == null || endpoints.isEmpty()) {
      throw new IllegalArgumentException("At least one remote endpoint is required");
    }
    List<Endpoint> effective =
        multiRemote && endpoints.size() > 1 ? endpoints : List.of(endpoints.get(0));

    String authUserPass =
        type == ProfileType.AUTO_LOGIN
            ? ""
            : mfaChallenge
                ? "auth-user-pass\nstatic-challenge \"Verification code\" 1"
                : "auth-user-pass";
    String certBlock =
        switch (type) {
          case AUTO_LOGIN, USER_LOCKED, SERVER_LOCKED ->
              block("ca", caCert) + block("cert", cert) + block("key", key);
          case GENERIC -> block("ca", caCert);
        };

    // Request an IPv6-capable tunnel and route all IPv6 traffic through it when
    // the server runs dual-stack. Server pushes alone are ignored by several
    // clients (notably OpenVPN Connect), so the directives are embedded in the
    // profile itself.
    String ipv6 = effective.get(0).ipv6Enabled() ? "tun-ipv6\nredirect-gateway ipv6" : "";

    return CLIENT_PREAMBLE
        .replace("__REMOTE_BLOCK__", remoteBlock(effective))
        .replace("__AUTH_USER_PASS__", authUserPass)
        .replace("__IPV6__", ipv6)
        .replace("__TLS_CRYPT__", taKey.trim())
        .replace("__CERT_BLOCK__", certBlock)
        .replace("\n\n\n", "\n\n");
  }

  private String remoteBlock(List<Endpoint> endpoints) {
    if (endpoints.size() == 1) {
      Endpoint only = endpoints.get(0);
      return "proto " + only.proto().name() + "\nremote " + only.host() + " " + only.port();
    }
    StringBuilder sb = new StringBuilder();
    for (Endpoint e : endpoints) {
      sb.append("remote ")
          .append(e.host())
          .append(" ")
          .append(e.port())
          .append(" ")
          .append(e.proto().name())
          .append("\n");
    }
    sb.append("remote-random");
    return sb.toString();
  }

  private String block(String tag, String content) {
    return "<" + tag + ">\n" + content.trim() + "\n</" + tag + ">\n";
  }
}
