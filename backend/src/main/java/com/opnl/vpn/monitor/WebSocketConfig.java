package com.opnl.vpn.monitor;

import com.opnl.vpn.security.JwtService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the status WebSocket endpoint with JWT handshake authentication. */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

  private final StatusWebSocketHandler handler;
  private final JwtService jwtService;

  public WebSocketConfig(StatusWebSocketHandler handler, JwtService jwtService) {
    this.handler = handler;
    this.jwtService = jwtService;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(handler, "/ws/status")
        .addInterceptors(new WsAuthHandshakeInterceptor(jwtService));
  }
}
