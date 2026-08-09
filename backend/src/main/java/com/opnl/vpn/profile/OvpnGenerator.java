package com.opnl.vpn.profile;

import com.opnl.vpn.network.ServerConfig;
import com.opnl.vpn.network.ServerConfig.Protocol;
import org.springframework.stereotype.Component;

/** Renders OpenVPN client profile text (.ovpn) for the four Access Server-style profile types. */
@Component
public class OvpnGenerator {

  private static final String CLIENT_PREAMBLE =
      """
      client
      dev tun
      proto __PROTO__
      remote __HOST__ __PORT__
      resolv-retry infinite
      nobind
      persist-key
      persist-tun
      remote-cert-tls server
      cipher AES-256-GCM
      data-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305
      auth SHA256
      __AUTH_USER_PASS__
      <tls-crypt>
      __TLS_CRYPT__
      </tls-crypt>
      __CERT_BLOCK__
      verb 3
      """;

  /**
   * Generates the profile. Locked types embed the client certificate and key; GENERIC uses
   * username/password against a client-cert-not-required daemon.
   */
  public String render(
      ProfileType type, ServerConfig config, String caCert, String taKey, String cert, String key) {
    String host =
        config.adminHost() == null || config.adminHost().isBlank()
            ? "vpn.example.com"
            : config.adminHost();
    int port = config.port();
    Protocol proto = config.proto();

    String authUserPass = type == ProfileType.AUTO_LOGIN ? "" : "auth-user-pass";
    String certBlock =
        switch (type) {
          case AUTO_LOGIN, USER_LOCKED, SERVER_LOCKED ->
              block("ca", caCert) + block("cert", cert) + block("key", key);
          case GENERIC -> block("ca", caCert);
        };

    return CLIENT_PREAMBLE
        .replace("__PROTO__", proto.name())
        .replace("__HOST__", host)
        .replace("__PORT__", String.valueOf(port))
        .replace("__AUTH_USER_PASS__", authUserPass)
        .replace("__TLS_CRYPT__", taKey.trim())
        .replace("__CERT_BLOCK__", certBlock)
        .replace("\n\n\n", "\n\n");
  }

  private String block(String tag, String content) {
    return "<" + tag + ">\n" + content.trim() + "\n</" + tag + ">\n";
  }
}
