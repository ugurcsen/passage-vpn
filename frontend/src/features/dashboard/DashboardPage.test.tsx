import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ToastProvider } from "@/hooks/ToastContext";
import { DashboardPage } from "./DashboardPage";

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
        <ToastProvider>
          <DashboardPage />
        </ToastProvider>
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

  it("shows the traffic chart placeholder until the monitor delivers samples", async () => {
    renderPage();

    expect(await screen.findByText(/collecting traffic data/i)).toBeInTheDocument();
    expect(screen.getByText("Network traffic")).toBeInTheDocument();
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
    // When the SVG renders in jsdom, its axis ticks and legend must be readable
    // (unit-formatted rates + Download/Upload series).
    const svg = container.querySelector('[data-testid="traffic-chart"] svg');
    if (svg) {
      const text = svg.textContent ?? "";
      expect(text).toContain("Download");
      expect(text).toContain("Upload");
      expect(text).toMatch(/B\/s/);
    }
  });

  it("loads demo data through the confirmation dialog and shows a success toast", async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes("/admin/demo/seed") && init?.method === "POST") {
        return Promise.resolve(json({ users: 4 }));
      }
      return Promise.resolve(json(dashboard));
    });
    vi.stubGlobal("fetch", fetchMock);
    renderPage();

    await screen.findByText("12");
    fireEvent.click(screen.getByRole("button", { name: /load demo data/i }));
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Load" }));

    expect(await screen.findByText("Demo data loaded: 4 sample users")).toBeInTheDocument();
    await waitFor(() => {
      const seedCall = fetchMock.mock.calls.find(
        (c) => String(c[0]).includes("/admin/demo/seed") && c[1]?.method === "POST",
      );
      expect(seedCall).toBeTruthy();
    });
  });
});
