package com.passagevpn.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Status WebSocket endpoint at {@code /ws/status}. On connect the current monitoring snapshot is
 * sent immediately; afterwards {@link MonitorService} broadcasts fresh snapshots every poll cycle.
 * The client never sends meaningful messages (pings are handled by the container).
 */
@Slf4j
@Component
public class StatusWebSocketHandler extends TextWebSocketHandler {

  private final MonitorService monitorService;
  private final MonitorBroadcaster broadcaster;
  private final ObjectMapper objectMapper;

  public StatusWebSocketHandler(
      MonitorService monitorService, MonitorBroadcaster broadcaster, ObjectMapper objectMapper) {
    this.monitorService = monitorService;
    this.broadcaster = broadcaster;
    this.objectMapper = objectMapper;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    broadcaster.add(session);
    session.sendMessage(
        new TextMessage(objectMapper.writeValueAsString(monitorService.currentSnapshot())));
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    broadcaster.remove(session);
  }
}
