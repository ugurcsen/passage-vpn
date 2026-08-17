package com.passagevpn.monitor;

import java.util.concurrent.CopyOnWriteArraySet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Registry of connected status WebSocket clients plus a broadcast helper. Deliberately dependency
 * free so {@link MonitorService} can push snapshots without creating a bean cycle.
 */
@Slf4j
@Component
public class MonitorBroadcaster {

  private final CopyOnWriteArraySet<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

  public void add(WebSocketSession session) {
    sessions.add(session);
  }

  public void remove(WebSocketSession session) {
    sessions.remove(session);
  }

  public int sessionCount() {
    return sessions.size();
  }

  /** Sends the payload to every connected client; a failing session is dropped. */
  public void broadcast(String payload) {
    for (WebSocketSession session : sessions) {
      try {
        synchronized (session) {
          session.sendMessage(new TextMessage(payload));
        }
      } catch (Exception e) {
        log.debug("Dropping status WebSocket session {}: {}", session.getId(), e.getMessage());
        sessions.remove(session);
      }
    }
  }
}
