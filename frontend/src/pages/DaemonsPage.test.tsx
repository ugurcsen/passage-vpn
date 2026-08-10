import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ToastProvider } from "@/hooks/useToast";
import { DaemonsPage } from "@/pages/DaemonsPage";

const daemons = [
  {
    id: "d0",
    daemonIndex: 0,
    name: "Primary",
    port: 1194,
    proto: "udp" as const,
    subnet: "10.8.0.0",
    subnetMask: "255.255.255.0",
    dnsServers: ["1.1.1.1", "8.8.8.8"],
    domain: null,
    extraRoutes: [],
    fullTunnel: true,
    clientCertNotRequired: false,
    authUserPass: true,
    adminHost: "vpn.example.com",
    enabled: true,
    primary: true,
    createdAt: "2026-08-01T00:00:00Z",
  },
  {
    id: "d1",
    daemonIndex: 1,
    name: "Generic gateway",
    port: 1195,
    proto: "tcp" as const,
    subnet: "10.9.0.0",
    subnetMask: "255.255.255.0",
    dnsServers: ["1.1.1.1"],
    domain: null,
    extraRoutes: [],
    fullTunnel: true,
    clientCertNotRequired: true,
    authUserPass: true,
    adminHost: "vpn.example.com",
    enabled: true,
    primary: false,
    createdAt: "2026-08-02T00:00:00Z",
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
        <ToastProvider>
          <DaemonsPage />
        </ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("DaemonsPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(daemons)));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("renders daemon rows with their serving profile role", async () => {
    renderPage();

    expect((await screen.findAllByText("Primary")).length).toBeGreaterThan(0);
    expect(screen.getByText("Generic gateway")).toBeInTheDocument();
    expect(screen.getByText("UDP :1194")).toBeInTheDocument();
    expect(screen.getByText("TCP :1195")).toBeInTheDocument();
    expect(screen.getByText("Generic")).toBeInTheDocument();
  });

  it("creates a daemon with the submitted payload", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByText("Primary");

    await user.click(screen.getByRole("button", { name: /new daemon/i }));
    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/^name$/i), {
      target: { value: "Generic access" },
    });
    fireEvent.change(within(dialog).getByLabelText(/^port\s*\*?$/i), {
      target: { value: "1196" },
    });
    fireEvent.change(within(dialog).getByLabelText(/^subnet\s*\*?$/i), {
      target: { value: "10.10.0.0" },
    });
    await user.click(within(dialog).getByRole("button", { name: /^create$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/admin/daemons" && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/admin/daemons" && o?.method === "POST",
    )!;
    expect(JSON.parse(String(opts!.body))).toMatchObject({
      name: "Generic access",
      daemonIndex: 2,
      port: 1196,
      proto: "udp",
      subnet: "10.10.0.0",
      fullTunnel: true,
      enabled: true,
    });
  });

  it("toggles a daemon enabled state", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByText("Primary");

    const switchEl = document.body.querySelector("input[type='checkbox']");
    expect(switchEl).not.toBeNull();
    await user.click(switchEl as Element);

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, opts]) =>
          url === "/api/admin/daemons/d0/enabled?enabled=false" && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
  });

  it("disables delete for the primary daemon", async () => {
    renderPage();
    await screen.findAllByText("Primary");

    const deleteButtons = screen.getAllByRole("button", { name: /delete/i });
    expect(deleteButtons[0]).toBeDisabled();
    expect(deleteButtons[1]).not.toBeDisabled();
  });
});
