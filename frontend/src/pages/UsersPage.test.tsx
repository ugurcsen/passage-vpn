import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { AuthProvider } from "@/hooks/useAuth";
import { ToastProvider } from "@/hooks/useToast";
import { UsersPage } from "@/pages/UsersPage";

const users = [
  {
    id: "u1",
    username: "alice",
    fullName: "Alice Smith",
    email: "alice@example.com",
    role: "ADMIN",
    mfaEnabled: true,
    banned: false,
    mustChangePassword: false,
    groups: ["devs"],
    createdAt: "2026-01-01T00:00:00Z",
    lastLoginAt: "2026-08-01T10:00:00Z",
  },
  {
    id: "u2",
    username: "bob",
    fullName: "Bob Jones",
    email: "bob@example.com",
    role: "USER",
    mfaEnabled: false,
    banned: true,
    mustChangePassword: false,
    groups: [],
    createdAt: "2026-02-01T00:00:00Z",
    lastLoginAt: null,
  },
];

const groups = [{ id: "g1", name: "devs", memberCount: 1 }];

const currentUser = {
  id: "admin1",
  username: "admin",
  fullName: "Root Admin",
  email: null,
  role: "ADMIN",
  mfaEnabled: false,
  banned: false,
  mustChangePassword: false,
  groups: [],
  createdAt: "2026-01-01T00:00:00Z",
  lastLoginAt: null,
};

const mfaSetup = {
  secret: "JBSWY3DPEHPK3PXP",
  otpAuthUrl: "otpauth://totp/OpenVPN%20Panel:alice?secret=JBSWY3DPEHPK3PXP",
  qrDataUrl: "data:image/png;base64,QUJD",
};

function json(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <ThemeProvider theme={darkTheme}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <ToastProvider>
            <UsersPage />
          </ToastProvider>
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("UsersPage", () => {
  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem("opnl.access", "test-token");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((url: string) => {
        if (url.startsWith("/api/auth/me")) return Promise.resolve(json(currentUser));
        if (url.includes("/mfa/setup")) return Promise.resolve(json(mfaSetup));
        if (url.includes("/mfa/enable")) return Promise.resolve(json(users[0]));
        if (url.includes("/mfa/disable")) return Promise.resolve(json(users[0]));
        if (url.includes("/settings")) return Promise.resolve(json({}));
        if (url.includes("/static-ip/allocate")) return Promise.resolve(json({ ...users[0], staticIp: "10.8.0.100" }));
        if (url.startsWith("/api/admin/groups")) return Promise.resolve(json(groups));
        return Promise.resolve(json(users));
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("renders users from the API", async () => {
    renderPage();

    expect(await screen.findByText("alice")).toBeInTheDocument();
    expect(await screen.findByText("bob")).toBeInTheDocument();
    expect(screen.getByText("Alice Smith")).toBeInTheDocument();
    expect(screen.getByText("devs")).toBeInTheDocument();
  });

  it("opens the create dialog with New user", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("alice");

    await user.click(screen.getByRole("button", { name: /new user/i }));

    const dialog = screen.getByRole("dialog");
    expect(dialog).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/username/i)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: /^create$/i })).toBeInTheDocument();
  });

  it("filters rows by status", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("alice");

    await user.click(screen.getByRole("combobox", { name: /status/i }));
    await user.click(await screen.findByRole("option", { name: /disabled/i }));

    await waitFor(() => {
      expect(screen.queryByText("alice")).not.toBeInTheDocument();
    });
    expect(screen.getByText("bob")).toBeInTheDocument();
  });

  it("creates a user through the dialog", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("alice");

    await user.click(screen.getByRole("button", { name: /new user/i }));
    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/username/i), {
      target: { value: "carol" },
    });
    fireEvent.change(within(dialog).getByLabelText(/password/i), {
      target: { value: "supersecret1" },
    });
    await user.click(within(dialog).getByRole("button", { name: /^create$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/admin/users" && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/admin/users" && o?.method === "POST",
    )!;
    expect(JSON.parse(String(opts!.body))).toMatchObject({
      username: "carol",
      role: "USER",
    });
  });

  it("sets up MFA for a user via QR dialog", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("alice");

    await user.click(screen.getByTestId("manage-mfa-bob"));
    let dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText(/two-factor authentication is disabled/i)).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: /set up mfa/i }));

    await waitFor(() => {
      dialog = screen.getByRole("dialog");
      expect(within(dialog).getByLabelText("Secret", { exact: true })).toHaveValue(
        "JBSWY3DPEHPK3PXP",
      );
    });
    expect(within(dialog).getByAltText(/totp qr code/i)).toBeInTheDocument();

    fireEvent.change(within(dialog).getByLabelText(/verification code/i), {
      target: { value: "123456" },
    });
    await user.click(within(dialog).getByRole("button", { name: /^enable$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/admin/users/u2/mfa/enable" && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/admin/users/u2/mfa/enable" && o?.method === "POST",
    )!;
    expect(JSON.parse(String(opts!.body))).toEqual({ code: "123456" });
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
  });

  it("disables MFA for a user after confirmation", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("alice");

    await user.click(screen.getByTestId("manage-mfa-alice"));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText(/two-factor authentication is enabled/i)).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: /disable mfa/i }));
    await user.click(within(dialog).getByRole("button", { name: /^disable mfa$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/admin/users/u1/mfa/disable" && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
  });

  it("opens the CCD editor and saves per-user settings", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("alice");

    await user.click(screen.getByTestId("edit-ccd-alice"));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText(/static ip/i)).toBeInTheDocument();

    fireEvent.change(within(dialog).getByLabelText(/dns servers/i), {
      target: { value: "1.1.1.1, 8.8.8.8" },
    });
    fireEvent.change(within(dialog).getByLabelText(/route restriction/i), {
      target: { value: "10.0.0.0/8" },
    });
    await user.click(within(dialog).getByRole("button", { name: /^save$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const put = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/admin/users/u1/settings/dns_servers" && opts?.method === "PUT",
      );
      expect(put).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/admin/users/u1/settings/dns_servers" && o?.method === "PUT",
    )!;
    expect(JSON.parse(String(opts!.body))).toBe("1.1.1.1, 8.8.8.8");
  });

  it("saves the tunnel mode in the CCD editor", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("alice");

    await user.click(screen.getByTestId("edit-ccd-alice"));
    const dialog = screen.getByRole("dialog");

    await user.click(within(dialog).getByLabelText(/tunnel mode/i));
    await user.click(await screen.findByRole("option", { name: /full tunnel/i }));
    await user.click(within(dialog).getByRole("button", { name: /^save$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const put = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/admin/users/u1/settings/tunnel_mode" && opts?.method === "PUT",
      );
      expect(put).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/admin/users/u1/settings/tunnel_mode" && o?.method === "PUT",
    )!;
    expect(JSON.parse(String(opts!.body))).toBe("full");
  });

  it("allocates a static IP from the group pool", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("alice");

    await user.click(screen.getByTestId("edit-ccd-alice"));
    const dialog = screen.getByRole("dialog");

    await user.click(within(dialog).getByRole("button", { name: /^allocate$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/admin/users/u1/static-ip/allocate" && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
  });
});
