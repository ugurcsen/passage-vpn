import { useCallback, useEffect, useRef, useState } from "react";
import { api, endpoints, tokenStore, type MonitorSnapshot } from "@/lib/api";

const RECONNECT_DELAY_MS = 5_000;
const FALLBACK_POLL_MS = 15_000;

interface UseLiveStatus {
  /** Latest monitor snapshot, or null before the first payload arrives. */
  snapshot: MonitorSnapshot | null;
  /** True while the WebSocket transport is connected. */
  connected: boolean;
  /** Last transport/reporting error, if any. */
  error: Error | null;
}

/** Subscribes to the real-time monitor feed. Prefers the /ws/status WebSocket (auto-reconnecting)
 *  and falls back to REST polling of /admin/monitor while disconnected or when the token is absent.
 *  The access token is passed as a query parameter because the browser WebSocket API cannot set
 *  Authorization headers (the backend requires ADMIN; rejected handshakes return 401). */
export function useLiveStatus(): UseLiveStatus {
  const [snapshot, setSnapshot] = useState<MonitorSnapshot | null>(null);
  const [connected, setConnected] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectRef = useRef<number | null>(null);
  const fallbackRef = useRef<number | null>(null);
  const fallbackActiveRef = useRef(false);

  const stopWebSocket = useCallback(() => {
    if (wsRef.current) {
      wsRef.current.onclose = null;
      wsRef.current.close();
      wsRef.current = null;
    }
    if (reconnectRef.current !== null) {
      window.clearTimeout(reconnectRef.current);
      reconnectRef.current = null;
    }
  }, []);

  const stopFallback = useCallback(() => {
    fallbackActiveRef.current = false;
    if (fallbackRef.current !== null) {
      window.clearInterval(fallbackRef.current);
      fallbackRef.current = null;
    }
  }, []);

  const startFallback = useCallback(() => {
    if (fallbackActiveRef.current) return;
    fallbackActiveRef.current = true;
    const poll = async () => {
      try {
        const data = await api<MonitorSnapshot>(endpoints.monitor);
        setSnapshot(data);
        setError(null);
      } catch (e) {
        setError(e instanceof Error ? e : new Error(String(e)));
      }
    };
    void poll();
    fallbackRef.current = window.setInterval(() => void poll(), FALLBACK_POLL_MS);
  }, []);

  const connect = useCallback(() => {
    stopFallback();
    const token = tokenStore.access;
    if (!token || typeof WebSocket === "undefined") {
      startFallback();
      return;
    }
    const query = new URLSearchParams({ token });
    const scheme = window.location.protocol === "https:" ? "wss:" : "ws:";
    const url = `${scheme}//${window.location.host}/ws/status?${query.toString()}`;
    let ws: WebSocket;
    try {
      ws = new WebSocket(url);
    } catch (e) {
      setError(e instanceof Error ? e : new Error(String(e)));
      startFallback();
      return;
    }
    wsRef.current = ws;
    ws.onopen = () => {
      setConnected(true);
      setError(null);
    };
    ws.onmessage = (event) => {
      try {
        setSnapshot(JSON.parse(event.data as string) as MonitorSnapshot);
        setError(null);
      } catch (e) {
        setError(e instanceof Error ? e : new Error(String(e)));
      }
    };
    ws.onclose = () => {
      setConnected(false);
      wsRef.current = null;
      startFallback();
      reconnectRef.current = window.setTimeout(connect, RECONNECT_DELAY_MS);
    };
  }, [startFallback, stopFallback]);

  useEffect(() => {
    connect();
    return () => {
      stopWebSocket();
      stopFallback();
    };
  }, [connect, stopWebSocket, stopFallback]);

  return { snapshot, connected, error };
}
