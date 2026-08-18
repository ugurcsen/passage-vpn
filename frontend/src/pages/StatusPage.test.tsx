import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { StatusPage } from "@/pages/StatusPage";

const status = {
  brand: "PassageVPN",
  version: "0.1.0-SNAPSHOT",
  uptimeSeconds: 3661,
  activeConnections: 1,
  daemons: [
    { index: 0, name: "Primary", port: 1194, proto: "udp", enabled: true, configPresent: true, mgmtReachable: true },
    { index: 1, name: null, port: 1195, proto: "tcp", enabled: false, configPresent: false, mgmtReachable: false },
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
        <StatusPage />
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("StatusPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(json(status))),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("renders panel info and per-daemon health chips", async () => {
    renderPage();

    expect(await screen.findByText("PassageVPN")).toBeInTheDocument();
    expect(screen.getByText("v0.1.0-SNAPSHOT")).toBeInTheDocument();
    expect(screen.getByText("Up 1h 1m")).toBeInTheDocument();
    expect(screen.getByText("UDP:1194")).toBeInTheDocument();
    expect(screen.getByText("TCP:1195")).toBeInTheDocument();
    expect(screen.getAllByText("Enabled").length).toBeGreaterThan(0);
    expect(screen.getByText("Disabled")).toBeInTheDocument();
    expect(screen.getByText("Present")).toBeInTheDocument();
    expect(screen.getByText("Missing")).toBeInTheDocument();
    expect(screen.getByText("Reachable")).toBeInTheDocument();
    expect(screen.getByText("Down")).toBeInTheDocument();
  });

  it("shows no active connections when WebSocket is not connected", async () => {
    renderPage();

    await screen.findByText("PassageVPN");
    expect(screen.getByText("No active connections right now.")).toBeInTheDocument();
  });

  it("shows the DCO data-channel state per daemon", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        Promise.resolve(
          json({
            ...status,
            daemons: [
              { ...status.daemons[0], dco: true },
              { ...status.daemons[1], dco: false },
            ],
          }),
        ),
      ),
    );
    renderPage();

    expect(await screen.findByText("DCO")).toBeInTheDocument();
    expect(screen.getByText("Userspace")).toBeInTheDocument();
  });

});
