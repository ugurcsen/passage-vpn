import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render } from "@testing-library/react";
import { useEffect } from "react";
import { useLiveStatus } from "@/hooks/useLiveStatus";
import type { MonitorSnapshot } from "@/lib/api";

const ACCESS_KEY = "opnl.access";

interface FakeWs {
  url: string;
  closed: boolean;
  onopen: (() => void) | null;
  onmessage: ((event: { data: string }) => void) | null;
  onclose: (() => void) | null;
  emitOpen(): void;
  emitMessage(data: string): void;
  emitClose(): void;
}

function makeFakeWebSocket() {
  const instances: FakeWs[] = [];
  class FakeWebSocket {
    static instances = instances;
    url: string;
    closed = false;
    onopen: (() => void) | null = null;
    onmessage: ((event: { data: string }) => void) | null = null;
    onclose: (() => void) | null = null;
    constructor(url: string) {
      this.url = url;
      instances.push(this);
    }
    close() {
      this.closed = true;
    }
    emitOpen() {
      this.onopen?.();
    }
    emitMessage(data: string) {
      this.onmessage?.({ data });
    }
    emitClose() {
      this.onclose?.();
    }
  }
  return FakeWebSocket;
}

interface LiveState {
  snapshot: MonitorSnapshot | null;
  connected: boolean;
  error: Error | null;
}

function Harness({ onState }: { onState: (s: LiveState) => void }) {
  const state = useLiveStatus();
  useEffect(() => {
    onState(state);
  });
  return null;
}

let latest: LiveState | null = null;

function renderHook() {
  latest = null;
  const utils = render(<Harness onState={(s) => { latest = s; }} />);
  return { ...utils, get: () => latest };
}

const snapshot = {
  at: "2026-08-15T12:00:00Z",
  connections: [],
} satisfies Partial<MonitorSnapshot>;

function json(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

describe("useLiveStatus", () => {
  let FakeWebSocket: ReturnType<typeof makeFakeWebSocket>;

  beforeEach(() => {
    localStorage.clear();
    FakeWebSocket = makeFakeWebSocket();
    vi.stubGlobal("WebSocket", FakeWebSocket);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(snapshot)));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("connects over WebSocket when a token is present and streams snapshots", () => {
    localStorage.setItem(ACCESS_KEY, "tok-1");
    const hook = renderHook();

    const ws = FakeWebSocket.instances[0] as unknown as FakeWs;
    expect(ws.url).toContain("/ws/status?token=tok-1");

    act(() => ws.emitOpen());
    expect(hook.get()?.connected).toBe(true);

    act(() => ws.emitMessage(JSON.stringify(snapshot)));
    expect(hook.get()?.snapshot).toEqual(snapshot);
    expect(hook.get()?.error).toBeNull();
  });

  it("surfaces an error for malformed WebSocket messages", () => {
    localStorage.setItem(ACCESS_KEY, "tok-1");
    const hook = renderHook();

    const ws = FakeWebSocket.instances[0] as unknown as FakeWs;
    act(() => ws.emitMessage("not-json"));
    expect(hook.get()?.error).toBeInstanceOf(Error);
  });

  it("falls back to REST polling when no token is present", async () => {
    const hook = renderHook();
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;

    await act(async () => {});
    expect(hook.get()?.snapshot).toEqual(snapshot);
    expect(fetchMock).toHaveBeenCalledWith("/api/admin/monitor", expect.anything());
  });

  it("falls back to REST polling when the WebSocket constructor throws", async () => {
    localStorage.setItem(ACCESS_KEY, "tok-1");
    vi.stubGlobal(
      "WebSocket",
      class {
        constructor() {
          throw new Error("ws unavailable");
        }
      },
    );
    const hook = renderHook();

    await act(async () => {});
    expect(hook.get()?.snapshot).toEqual(snapshot);
    expect(hook.get()?.error).toBeNull();
  });

  it("polls on the fallback interval and records poll errors", async () => {
    vi.useFakeTimers();
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(snapshot))
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ message: "down" }), {
          status: 500,
          headers: { "Content-Type": "application/json" },
        }),
      );
    vi.stubGlobal("fetch", fetchMock);
    const hook = renderHook();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(15_000);
    });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(hook.get()?.error).toBeInstanceOf(Error);
  });

  it("reconnects after the socket closes", async () => {
    vi.useFakeTimers();
    localStorage.setItem(ACCESS_KEY, "tok-1");
    const hook = renderHook();

    const ws = FakeWebSocket.instances[0] as unknown as FakeWs;
    act(() => ws.emitClose());
    expect(hook.get()?.connected).toBe(false);
    expect((globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(0);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
    });

    expect(FakeWebSocket.instances.length).toBeGreaterThanOrEqual(2);
  });

  it("closes the socket and clears timers on unmount", () => {
    localStorage.setItem(ACCESS_KEY, "tok-1");
    const hook = renderHook();

    const ws = FakeWebSocket.instances[0] as unknown as FakeWs;
    hook.unmount();
    expect(ws.closed).toBe(true);
  });
});
