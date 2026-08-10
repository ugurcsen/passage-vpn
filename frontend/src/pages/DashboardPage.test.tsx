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
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(dashboard)));
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
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: "boom" }), {
          status: 500,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );
    renderPage();

    expect(await screen.findByText("boom")).toBeInTheDocument();
  });
});
