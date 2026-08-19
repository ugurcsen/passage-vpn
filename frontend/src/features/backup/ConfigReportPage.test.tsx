import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ConfigReportPage } from "./ConfigReportPage";

const report = {
  brand: "Acme VPN",
  version: "0.1.0-alpha.4",
  generatedAt: "2026-08-13T10:00:00Z",
  dbType: "sqlite",
  dataDirs: {
    pki: "/data/pki",
    ccd: "/data/ccd",
    config: "/data/config",
    logs: "/data/logs",
  },
  serverSettings: { max_connections: 3, tunnel_mode: "full" },
  daemons: [
    { index: 0, name: "main", port: 1194, proto: "udp", enabled: true },
  ],
  pki: { total: 5, valid: 4, revoked: 1, expired: 0, expiringSoon: 1 },
  users: 2,
  groups: 1,
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <ThemeProvider theme={darkTheme}>
      <QueryClientProvider client={queryClient}>
        <ConfigReportPage />
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("ConfigReportPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(report)));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows version, brand, dirs and PKI counts", async () => {
    renderPage();
    expect(await screen.findByText("0.1.0-alpha.4")).toBeInTheDocument();
    expect(screen.getByText("Acme VPN")).toBeInTheDocument();
    expect(screen.getByText("/data/pki")).toBeInTheDocument();
    expect(screen.getByText("main")).toBeInTheDocument();
    expect(screen.getByText("UDP")).toBeInTheDocument();
    expect(screen.getAllByText("Yes").length).toBeGreaterThan(0);
  });

  it("renders server settings as JSON", async () => {
    renderPage();
    await screen.findByText(/0\.1\.0-alpha\.4/);
    expect(screen.getByText(/"max_connections": 3/)).toBeInTheDocument();
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
});
