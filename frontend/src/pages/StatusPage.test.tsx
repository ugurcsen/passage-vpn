import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { StatusPage } from "@/pages/StatusPage";

const status = {
  brand: "OpenVPN Panel",
  version: "0.1.0-SNAPSHOT",
  uptimeSeconds: 3661,
  activeConnections: 1,
  daemons: [
    { index: 0, name: "Primary", port: 1194, proto: "udp", enabled: true, configPresent: true, mgmtReachable: true },
    { index: 1, name: null, port: 1195, proto: "tcp", enabled: false, configPresent: false, mgmtReachable: false },
  ],
};

const connections = [
  {
    username: "alice",
    commonName: "alice",
    virtualIp: "10.8.0.2",
    remoteIp: "203.0.113.5",
    daemonName: "daemon-0",
    connectedAt: "2026-08-11T00:00:00Z",
  },
];

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
      vi.fn((url: string) =>
        url.includes("/connections") ? Promise.resolve(json(connections)) : Promise.resolve(json(status)),
      ),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("renders panel info and per-daemon health chips", async () => {
    renderPage();

    expect(await screen.findByText("OpenVPN Panel")).toBeInTheDocument();
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

  it("lists active connections", async () => {
    renderPage();

    expect((await screen.findAllByText("alice")).length).toBeGreaterThan(0);
    expect(screen.getByText("203.0.113.5")).toBeInTheDocument();
    expect(screen.getByText("10.8.0.2")).toBeInTheDocument();
  });

  it("renders recent sessions and falls back when the daemon name is empty", async () => {
    const logs = [
      {
        username: "carol",
        commonName: "carol",
        virtualIp: "10.8.0.9",
        remoteIp: "198.51.100.7",
        daemonName: "",
        connectedAt: "2026-08-11T01:00:00Z",
        disconnectedAt: "2026-08-11T02:00:00Z",
        durationSeconds: 3600,
        bytesIn: 0,
        bytesOut: 0,
      },
    ];
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) =>
        url.includes("/connection-logs")
          ? Promise.resolve(json(logs))
          : url.includes("/connections")
            ? Promise.resolve(json([]))
            : Promise.resolve(json(status)),
      ),
    );
    renderPage();

    const rows = await screen.findAllByText("carol");
    const row = rows[0].closest("tr")!;
    // Daemon cell (index 3) falls back to "—" for an empty daemonName.
    expect(row.querySelectorAll("td")[3].textContent).toBe("—");
  });
});
