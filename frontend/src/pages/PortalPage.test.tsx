import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { MemoryRouter } from "react-router-dom";
import { darkTheme } from "@/theme";
import { ToastProvider } from "@/hooks/useToast";
import { AuthProvider } from "@/hooks/useAuth";
import { PortalPage } from "@/pages/PortalPage";

const profileTypes = [
  { type: "USER_LOCKED", label: "User locked", locked: false },
  { type: "AUTO_LOGIN", label: "Auto login", locked: false },
];

const meBody = {
  id: "u1",
  username: "alice",
  role: "USER",
  mfaEnabled: false,
  banned: false,
  mustChangePassword: false,
  groups: [],
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
        <AuthProvider>
          <ToastProvider>
            <MemoryRouter>
              <PortalPage />
            </MemoryRouter>
          </ToastProvider>
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("PortalPage", () => {
  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem("opnl.access", "access");
    localStorage.setItem("opnl.refresh", "refresh");
    vi.stubGlobal("URL", {
      ...URL,
      createObjectURL: vi.fn().mockReturnValue("blob:stub"),
      revokeObjectURL: vi.fn(),
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((url: string) => {
        if (url === "/api/auth/me") return Promise.resolve(json(meBody));
        if (url.endsWith("/qr")) return Promise.resolve(json({ token: "qr-tok", expiresAt: null }));
        if (url.startsWith("/api/portal/profiles/"))
          return Promise.resolve(
            json({ filename: "user-locked-alice.ovpn", content: "client\nremote vpn.example.com" }),
          );
        return Promise.resolve(json(profileTypes));
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("renders the available profile types", async () => {
    renderPage();

    expect(await screen.findByText("USER LOCKED")).toBeInTheDocument();
    expect(screen.getByText("AUTO LOGIN")).toBeInTheDocument();
    expect(screen.getByText(/signed in as alice/i)).toBeInTheDocument();
  });

  it("downloads a profile via the portal endpoint", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("USER LOCKED");

    const downloadButtons = screen.getAllByRole("button", { name: /download/i });
    await user.click(downloadButtons[0]);

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([url]) => url === "/api/portal/profiles/USER_LOCKED/download",
        ),
      ).toBe(true);
    });
    expect(URL.createObjectURL).toHaveBeenCalled();
  });

  it("renders a compact QR payload via the /qr token endpoint", async () => {
    const user = userEvent.setup();
    const { container } = renderPage();
    await screen.findByText("USER LOCKED");

    const qrButtons = screen.getAllByRole("button", { name: /qr/i });
    await user.click(qrButtons[0]);

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([url]) => url === "/api/portal/profiles/USER_LOCKED/qr",
        ),
      ).toBe(true);
    });
    await waitFor(() => {
      expect(container.querySelector("svg")).not.toBeNull();
    });
  });
});
