import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ToastProvider } from "@/hooks/ToastContext";
import { ProfilesPage } from "./ProfilesPage";

const users = [{ id: "u1", username: "alice" }];

const tokens = [
  {
    id: "t1",
    token: "tok-abc",
    userId: "u1",
    username: "alice",
    profileType: "USER_LOCKED",
    daemonIndex: null,
    source: "ADMIN",
    expiresAt: "2030-01-01T00:00:00Z",
    usesLeft: 3,
    createdAt: "2026-08-01T00:00:00Z",
    revoked: false,
  },
  {
    id: "t2",
    token: "tok-xyz",
    userId: null,
    username: null,
    profileType: "GENERIC",
    daemonIndex: null,
    source: "ADMIN",
    expiresAt: null,
    usesLeft: null,
    createdAt: "2026-08-02T00:00:00Z",
    revoked: true,
  },
  {
    id: "t3",
    token: "tok-qr",
    userId: "u1",
    username: "alice",
    profileType: "USER_LOCKED",
    daemonIndex: null,
    source: "PORTAL",
    expiresAt: "2030-02-01T00:00:00Z",
    usesLeft: 0,
    createdAt: "2026-08-03T00:00:00Z",
    revoked: false,
  },
  {
    id: "t4",
    token: "tok-old",
    userId: "u1",
    username: "alice",
    profileType: "AUTO_LOGIN",
    daemonIndex: null,
    source: "ADMIN",
    expiresAt: "2020-01-01T00:00:00Z",
    usesLeft: 5,
    createdAt: "2026-08-04T00:00:00Z",
    revoked: false,
  },
];

const daemons = [
  {
    id: "d0",
    daemonIndex: 0,
    name: "Full tunnel",
    port: 1194,
    proto: "udp",
    subnet: "10.8.0.0",
    subnetMask: "255.255.255.0",
    dnsServers: [],
    domain: null,
    extraRoutes: [],
    fullTunnel: true,
    clientCertNotRequired: false,
    authUserPass: true,
    adminHost: null,
    nodeId: null,
    ipv6Enabled: false,
    ipv6Subnet: null,
    enabled: true,
    primary: true,
    createdAt: "2026-08-01T00:00:00Z",
  },
  {
    id: "d1",
    daemonIndex: 1,
    name: "Split tunnel",
    port: 1195,
    proto: "udp",
    subnet: "10.9.0.0",
    subnetMask: "255.255.255.0",
    dnsServers: [],
    domain: null,
    extraRoutes: ["192.168.50.0/24"],
    fullTunnel: false,
    clientCertNotRequired: false,
    authUserPass: true,
    adminHost: null,
    nodeId: null,
    ipv6Enabled: false,
    ipv6Subnet: null,
    enabled: true,
    primary: false,
    createdAt: "2026-08-01T00:00:00Z",
  },
];

const ovpn = { filename: "user-locked-alice.ovpn", content: "client\nremote vpn.example.com" };

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
          <ProfilesPage />
        </ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("ProfilesPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal("URL", {
      ...URL,
      createObjectURL: vi.fn().mockReturnValue("blob:stub"),
      revokeObjectURL: vi.fn(),
    });
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((url: string, opts?: RequestInit) => {
        if (url === "/api/admin/users") return Promise.resolve(json(users));
        if (url.startsWith("/api/admin/profile-tokens")) {
          if (opts?.method === "POST") return Promise.resolve(json({ id: "t3" }));
          return Promise.resolve(json(tokens));
        }
        if (url.includes("/profiles/")) return Promise.resolve(json(ovpn));
        return Promise.resolve(json([]));
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("renders the share link table", async () => {
    renderPage();

    expect(await screen.findAllByText("alice")).toHaveLength(3);
    expect(screen.getByText("— (generic)")).toBeInTheDocument();
    expect(screen.getByText("Active")).toBeInTheDocument();
    expect(screen.getByText("Revoked")).toBeInTheDocument();
    expect(screen.getByText("Used up")).toBeInTheDocument();
    expect(screen.getByText("Expired")).toBeInTheDocument();
  });

  it("marks portal QR tokens and shows the exact expiry timestamp", async () => {
    renderPage();
    await screen.findByText("— (generic)");

    expect(screen.getAllByText("Portal QR")).toHaveLength(1);
    expect(screen.getAllByText("Admin")).toHaveLength(3);

    const expires = new Date("2030-01-01T00:00:00Z").toLocaleString();
    expect(screen.getByText(expires)).toBeInTheDocument();
    expect(expires).not.toBe(new Date("2030-01-01T00:00:00Z").toLocaleDateString());
  });

  it("disables copy for used-up and expired tokens", async () => {
    renderPage();
    await screen.findByText("— (generic)");

    const aliceRows = screen.getAllByRole("row", { name: /alice/i });
    const usedUpRow = aliceRows.find((r) => within(r).queryByText("Used up"));
    const expiredRow = aliceRows.find((r) => within(r).queryByText("Expired"));
    expect(usedUpRow).toBeDefined();
    expect(expiredRow).toBeDefined();
    expect(within(usedUpRow!).getAllByRole("button")[0]).toBeDisabled();
    expect(within(expiredRow!).getAllByRole("button")[0]).toBeDisabled();
  });

  it("filters tokens by status", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("— (generic)");
    expect(screen.getAllByRole("row", { name: /alice/i })).toHaveLength(3);

    await user.click(screen.getByRole("combobox", { name: /^status$/i }));
    await user.click(await screen.findByRole("option", { name: "Active" }));

    const rows = screen.getAllByRole("row", { name: /alice/i });
    expect(rows).toHaveLength(1);
    expect(within(rows[0]).getByText("Active")).toBeInTheDocument();
  });

  it("filters tokens by source", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("— (generic)");

    await user.click(screen.getByRole("combobox", { name: /^source$/i }));
    await user.click(await screen.findByRole("option", { name: "Portal QR" }));

    const rows = screen.getAllByRole("row", { name: /alice/i });
    expect(rows).toHaveLength(1);
    expect(within(rows[0]).getByText("Portal QR")).toBeInTheDocument();
  });

  it("creates a share link with the selected user and limits", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("— (generic)");

    await user.click(screen.getByRole("button", { name: /new share link/i }));
    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByRole("combobox", { name: /^user$/i }));
    await user.click(await screen.findByRole("option", { name: "alice" }));
    fireEvent.change(within(dialog).getByLabelText(/max uses/i), {
      target: { value: "3" },
    });
    await user.click(within(dialog).getByRole("button", { name: /^create$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, o]) => url === "/api/admin/profile-tokens" && o?.method === "POST",
      );
      expect(post).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/admin/profile-tokens" && o?.method === "POST",
    )!;
    const body = JSON.parse(String(opts!.body));
    expect(body).toMatchObject({
      userId: "u1",
      profileType: "USER_LOCKED",
      usesLeft: 3,
    });
    expect(new Date(body.expiresAt).getTime()).toBeGreaterThan(Date.now());
  });

  it("copies the share link to the clipboard", async () => {
    const user = userEvent.setup();
    const writeSpy = vi.spyOn(navigator.clipboard, "writeText");
    renderPage();
    await screen.findByText("— (generic)");

    const activeRow = screen
      .getAllByRole("row", { name: /alice/i })
      .find((r) => within(r).queryByText("Active"));
    expect(activeRow).toBeDefined();
    const copyButton = within(activeRow!).getAllByRole("button")[0];
    await user.click(copyButton);

    await waitFor(() => {
      expect(writeSpy).toHaveBeenCalledWith(`${window.location.origin}/share/tok-abc`);
    });
  });

  it("disables copy and revoke for revoked tokens", async () => {
    renderPage();
    await screen.findByText("— (generic)");

    const disabledButtons = document.body.querySelectorAll("button[disabled]");
    expect(disabledButtons.length).toBeGreaterThan(0);
  });

  it("pins a user profile download to a selected daemon", async () => {
    vi.mocked(fetch).mockImplementation((url: RequestInfo | URL) => {
      const u = String(url);
      if (u === "/api/admin/users") return Promise.resolve(json(users));
      if (u === "/api/admin/daemons") return Promise.resolve(json(daemons));
      if (u.startsWith("/api/admin/profile-tokens")) return Promise.resolve(json(tokens));
      if (u.includes("/profiles/")) return Promise.resolve(json(ovpn));
      return Promise.resolve(json([]));
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByText("alice");

    await user.click(screen.getByRole("combobox", { name: /^user$/i }));
    await user.click(await screen.findByRole("option", { name: "alice" }));

    const serverSelect = screen.getByRole("combobox", { name: /^server$/i });
    await user.click(serverSelect);
    await user.click(await screen.findByRole("option", { name: /split tunnel/i }));

    const downloadButton = screen.getByRole("button", { name: /download/i });
    await user.click(downloadButton);

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([url]) => url === "/api/admin/users/u1/profiles/USER_LOCKED/download?daemonIndex=1",
        ),
      ).toBe(true);
    });
    expect(URL.createObjectURL).toHaveBeenCalled();
  });
});
