import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ConnectionLogsPage } from "@/pages/ConnectionLogsPage";
import type { ConnectionLog } from "@/lib/api";

const logs: ConnectionLog[] = [
  {
    username: "alice",
    commonName: "alice-client",
    virtualIp: "10.8.0.5",
    remoteIp: "203.0.113.1",
    daemonName: "udp-1194",
    nodeId: null,
    connectedAt: "2026-08-15T08:00:00Z",
    disconnectedAt: "2026-08-15T10:00:00Z",
    bytesIn: 2048,
    bytesOut: 1024 ** 3 * 1.5,
    durationSeconds: 7200,
  },
  {
    username: "",
    commonName: "bob-client",
    virtualIp: null,
    remoteIp: null,
    daemonName: "  ",
    nodeId: "node-1",
    connectedAt: "2026-08-15T11:00:00Z",
    disconnectedAt: null,
    bytesIn: -1,
    bytesOut: 0,
    durationSeconds: Number.NaN,
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
        <ConnectionLogsPage />
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("ConnectionLogsPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(logs)));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("renders connection rows with formatted cells", async () => {
    renderPage();

    expect(await screen.findByText("alice")).toBeInTheDocument();
    expect(screen.getByText("alice-client")).toBeInTheDocument();
    expect(screen.getByText("10.8.0.5")).toBeInTheDocument();
    expect(screen.getByText("udp-1194")).toBeInTheDocument();
    expect(screen.getByText("2h 0m")).toBeInTheDocument();
    expect(screen.getByText("2.0 KB")).toBeInTheDocument();
    expect(screen.getByText("1.50 GB")).toBeInTheDocument();
    expect(screen.getByText("Active")).toBeInTheDocument();
  });

  it("shows fallback markers for blank optional values", async () => {
    renderPage();

    expect(await screen.findByText("bob-client")).toBeInTheDocument();
    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
  });

  it("shows an empty-state message when there are no sessions", async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue(json([]));
    renderPage();

    expect(await screen.findByText(/no recorded sessions/i)).toBeInTheDocument();
  });

  it("renders an error alert when the request fails", async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      new Response(JSON.stringify({ message: "boom" }), {
        status: 500,
        headers: { "Content-Type": "application/json" },
      }),
    );
    renderPage();

    expect(await screen.findByText(/boom/)).toBeInTheDocument();
  });

  it("re-fetches when the refresh button is clicked", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(logs));
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();

    renderPage();
    await screen.findByText("alice");

    await user.click(screen.getByRole("button", { name: /refresh/i }));

    await waitFor(() => {
      const logCalls = fetchMock.mock.calls.filter(([url]) => url === "/api/admin/connection-logs");
      expect(logCalls.length).toBeGreaterThanOrEqual(2);
    });
  });
});
