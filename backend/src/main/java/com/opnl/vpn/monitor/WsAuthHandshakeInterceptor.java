package com.opnl.vpn.monitor;

import com.opnl.vpn.security.JwtService;
import com.opnl.vpn.user.User;
import io.jsonwebtoken.Claims;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Authenticates the status WebSocket handshake via a {@code ?token=} access-token query parameter
 * (the frontend cannot send Authorization headers on WebSocket connects). Rejects non-admin or
 * invalid tokens with a 401 before the socket is established.
 */
public class WsAuthHandshakeInterceptor implements HandshakeInterceptor {

  private final JwtService jwtService;

  public WsAuthHandshakeInterceptor(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Map<String, Object> attributes) {
    String token = tokenFrom(request.getURI());
    Claims claims = token == null ? null : jwtService.parse(token);
    boolean allowed =
        claims != null
            && !jwtService.isMfaChallenge(claims)
            && User.Role.ADMIN.name().equals(claims.get("role"));
    if (!allowed) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
    }
    return allowed;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Exception exception) {
    // nothing to do
  }

  private static String tokenFrom(URI uri) {
    String query = uri.getRawQuery();
    if (query == null || query.isBlank()) {
      return null;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && "token".equals(pair.substring(0, eq))) {
        return uriDecode(pair.substring(eq + 1));
      }
    }
    return null;
  }

  private static String uriDecode(String value) {
    try {
      return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
