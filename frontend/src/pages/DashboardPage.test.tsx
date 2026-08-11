import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { DashboardPage } from "@/pages/DashboardPage";

const dashboard = {
  users: 12,
  groups: 3,
  activeCertificates: 9,
  activeConnections: 2,
  runningDaemons: 1,
  totalDaemons: 3,
  recentConnections: [
    {
      username: "alice",
      commonName: "alice",
      virtualIp: "10.8.0.2",
      remoteIp: "203.0.113.5",
      daemonName: "daemon-0",
      connectedAt: new Date(Date.now() - 60_000).toISOString(),
    },
  ],
};

function json(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <ThemeProvider theme={darkTheme}>
      <QueryClientProvider client={queryClient}>
        <DashboardPage />
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("DashboardPage", () => {
  beforeEach(() => {
    localStorage.clear();
    // jsdom has no WebSocket: the live hook falls back to REST polling, so fetch
    // stays the single source of truth in tests.
    vi.stubGlobal("WebSocket", undefined);
    // A fresh Response per call: bodies are one-shot streams and the monitor poll
    // performs a second fetch that would fail on an already-consumed body.
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(json(dashboard))));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("renders live stat cards from the dashboard endpoint", async () => {
    renderPage();

    expect(await screen.findByText("12")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
    expect(screen.getByText("9")).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();
    expect(screen.getByText("Active connections")).toBeInTheDocument();
  });

  it("shows the daemon running summary and recent connections", async () => {
    renderPage();

    expect(await screen.findByText(/Daemons 1 \/ 3 running/i)).toBeInTheDocument();
    expect(screen.getAllByText("alice").length).toBeGreaterThan(0);
    expect(screen.getByText("203.0.113.5")).toBeInTheDocument();
    expect(screen.getByText("10.8.0.2")).toBeInTheDocument();
  });

  it("renders an error alert when the fetch fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ message: "boom" }), {
            status: 500,
            headers: { "Content-Type": "application/json" },
          }),
        ),
      ),
    );
    renderPage();

    expect(await screen.findByText("boom")).toBeInTheDocument();
  });

  it("renders the live traffic chart and host system card from the monitor feed", async () => {
    const monitor = {
      at: new Date().toISOString(),
      connections: [],
      daemons: [
        {
          index: 0,
          name: "Primary",
          port: 1194,
          proto: "udp",
          enabled: true,
          configPresent: true,
          mgmtReachable: true,
          dco: false,
        },
      ],
      bytesInPerSec: 1024,
      bytesOutPerSec: 512,
      activeConnections: 0,
      history: [
        { at: new Date(Date.now() - 60_000).toISOString(), bytesInPerSec: 100, bytesOutPerSec: 50, activeConnections: 0 },
        { at: new Date().toISOString(), bytesInPerSec: 200, bytesOutPerSec: 80, activeConnections: 0 },
      ],
      system: {
        cpuLoadPercent: 12,
        totalMemory: 8 * 1024 ** 3,
        freeMemory: 3 * 1024 ** 3,
        diskTotal: 50 * 1024 ** 3,
        diskFree: 20 * 1024 ** 3,
        availableProcessors: 4,
      },
    };
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(json(monitor))));
    const { container } = renderPage();

    // Wait for the REST fallback poll to deliver a snapshot (slow CI boxes may
    // take a moment), then assert the live widgets rendered from it.
    expect(await screen.findByText("Polling", {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByText("CPU")).toBeInTheDocument();
    expect(screen.getByText("12%")).toBeInTheDocument();
    expect(container.querySelector('[data-testid="traffic-chart"]')).not.toBeNull();
  });
});
