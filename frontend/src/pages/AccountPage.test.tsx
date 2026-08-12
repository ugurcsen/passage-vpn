import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { MemoryRouter } from "react-router-dom";
import { darkTheme } from "@/theme";
import { AuthProvider } from "@/hooks/useAuth";
import { ToastProvider } from "@/hooks/useToast";
import { AccountPage } from "@/pages/AccountPage";

let mfaEnabled = false;

function me() {
  return {
    id: "u1",
    username: "alice",
    fullName: "Alice Smith",
    email: "alice@example.com",
    role: "USER",
    mfaEnabled,
    banned: false,
    mustChangePassword: false,
    groups: [],
    createdAt: "2026-01-01T00:00:00Z",
    lastLoginAt: null,
  };
}

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
        <MemoryRouter initialEntries={["/portal/account"]}>
          <AuthProvider>
            <ToastProvider>
              <AccountPage />
            </ToastProvider>
          </AuthProvider>
        </MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("AccountPage", () => {
  beforeEach(() => {
    mfaEnabled = false;
    localStorage.clear();
    localStorage.setItem("opnl.access", "test-token");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((url: string) => {
        if (url.startsWith("/api/auth/me")) return Promise.resolve(json(me()));
        if (url.includes("/mfa/setup")) return Promise.resolve(json(mfaSetup));
        if (url.includes("/mfa/enable")) {
          mfaEnabled = true;
          return Promise.resolve(json(me()));
        }
        if (url.includes("/mfa/disable")) {
          mfaEnabled = false;
          return Promise.resolve(json(me()));
        }
        if (url.includes("/account/password")) return Promise.resolve(new Response(null, { status: 200 }));
        return Promise.resolve(json({}));
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("shows disabled MFA status and the password form", async () => {
    renderPage();

    expect(await screen.findByText(/two-factor authentication is disabled/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /set up mfa/i })).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: /change password/i }),
    ).toBeInTheDocument();
  });

  it("sets up MFA end-to-end (password -> QR -> code)", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText(/two-factor authentication is disabled/i);

    await user.click(screen.getByRole("button", { name: /set up mfa/i }));
    let dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/current password/i), {
      target: { value: "supersecret1" },
    });
    await user.click(within(dialog).getByRole("button", { name: /continue/i }));

    await waitFor(() => {
      dialog = screen.getByRole("dialog");
      expect(within(dialog).getByAltText(/totp qr code/i)).toBeInTheDocument();
    });
    fireEvent.change(within(dialog).getByLabelText(/verification code/i), {
      target: { value: "123456" },
    });
    await user.click(within(dialog).getByRole("button", { name: /^enable$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const setup = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/portal/account/mfa/setup" && opts?.method === "POST",
      );
      expect(setup).toBeDefined();
    });
    await waitFor(() => {
      const enable = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/portal/account/mfa/enable" && opts?.method === "POST",
      );
      expect(enable).toBeDefined();
    });
    await waitFor(() => {
      expect(screen.getByText(/two-factor authentication is enabled/i)).toBeInTheDocument();
    });
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
  });

  it("disables MFA after re-authentication", async () => {
    const user = userEvent.setup();
    mfaEnabled = true;
    renderPage();
    await screen.findByText(/two-factor authentication is enabled/i);

    await user.click(screen.getByRole("button", { name: /disable mfa/i }));
    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/current password/i), {
      target: { value: "supersecret1" },
    });
    await user.click(within(dialog).getByRole("button", { name: /^disable mfa$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const disable = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/portal/account/mfa/disable" && opts?.method === "POST",
      );
      expect(disable).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/portal/account/mfa/disable" && o?.method === "POST",
    )!;
    expect(JSON.parse(String(opts!.body))).toEqual({ currentPassword: "supersecret1" });
    await waitFor(() => {
      expect(screen.getByText(/two-factor authentication is disabled/i)).toBeInTheDocument();
    });
  });

  it("sends password change with current and new password", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText(/two-factor authentication is disabled/i);

    fireEvent.change(screen.getByLabelText(/current password/i), {
      target: { value: "supersecret1" },
    });
    fireEvent.change(screen.getByLabelText(/^new password/i), {
      target: { value: "brandnewpassword1" },
    });
    fireEvent.change(screen.getByLabelText(/confirm new password/i), {
      target: { value: "brandnewpassword1" },
    });
    await user.click(screen.getByRole("button", { name: /change password/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/portal/account/password" && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/portal/account/password" && o?.method === "POST",
    )!;
    expect(JSON.parse(String(opts!.body))).toEqual({
      currentPassword: "supersecret1",
      newPassword: "brandnewpassword1",
    });
  });
});
