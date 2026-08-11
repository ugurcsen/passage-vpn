package com.opnl.vpn.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.security.JwtService;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

/** Auth contract for the /ws/status handshake. */
class WsAuthHandshakeInterceptorTest {

  private JwtService jwtService;
  private WsAuthHandshakeInterceptor interceptor;
  private WebSocketHandler handler;

  @BeforeEach
  void setUp() {
    OpnlProperties properties =
        new OpnlProperties(
            "./data",
            "OpenVPN Panel",
            "internal-token",
            new OpnlProperties.Jwt("j".repeat(64), 900, 14),
            new OpnlProperties.Auth("local", 5, 300, 300, 20, 60),
            new OpnlProperties.OpenVpn(
                "openvpn",
                7505,
                "vpn.example.com",
                "pki",
                "ccd",
                "config",
                "scripts",
                "openvpn/scripts",
                "http://backend:8080",
                "easyrsa",
                "logs"));
    jwtService = new JwtService(properties);
    interceptor = new WsAuthHandshakeInterceptor(jwtService);
    handler = mock(WebSocketHandler.class);
  }

  private boolean handshake(String query) {
    MockHttpServletRequest servlet = new MockHttpServletRequest();
    servlet.setRequestURI("/ws/status");
    servlet.setQueryString(query);
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    boolean allowed =
        interceptor.beforeHandshake(
            new ServletServerHttpRequest(servlet),
            new ServletServerHttpResponse(servletResponse),
            handler,
            new HashMap<>());
    if (!allowed) {
      assertThat(servletResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }
    return allowed;
  }

  @Test
  void rejectsHandshakeWithoutToken() {
    assertThat(handshake(null)).isFalse();
    assertThat(handshake("other=1")).isFalse();
  }

  @Test
  void rejectsHandshakeWithInvalidToken() {
    assertThat(handshake("token=garbage")).isFalse();
  }

  @Test
  void rejectsHandshakeWithMfaChallengeToken() {
    String mfaToken = jwtService.issueMfaChallenge("u1");
    assertThat(handshake("token=" + mfaToken)).isFalse();
  }

  @Test
  void rejectsHandshakeForNonAdminRole() {
    String token = jwtService.issueAccessToken("u1", "alice", "USER");
    assertThat(handshake("token=" + token)).isFalse();
  }

  @Test
  void acceptsHandshakeForAdmin() {
    String token = jwtService.issueAccessToken("u1", "boss", "ADMIN");
    assertThat(handshake("token=" + token)).isTrue();
  }
}
