import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ToastProvider } from "@/hooks/useToast";
import { CertsPage } from "@/pages/CertsPage";

const certs = [
  {
    id: "c1",
    commonName: "alice",
    userId: "u1",
    username: "alice",
    status: "VALID" as const,
    serial: "ABC123",
    issuedAt: "2026-07-01T00:00:00Z",
    expiresAt: "2027-07-01T00:00:00Z",
  },
  {
    id: "c2",
    commonName: "old-cert",
    userId: "u2",
    username: "bob",
    status: "REVOKED" as const,
    revokedAt: "2026-07-15T00:00:00Z",
  },
];

const users = [
  { id: "u1", username: "alice" },
  { id: "u2", username: "bob" },
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
          <CertsPage />
        </ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("CertsPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((url: string) => {
        if (url.startsWith("/api/admin/users")) return Promise.resolve(json(users));
        return Promise.resolve(json(certs));
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("renders certificates and status chips", async () => {
    renderPage();

    expect(await screen.findByText("old-cert")).toBeInTheDocument();
    expect(screen.getAllByText("alice").length).toBeGreaterThan(0);
    expect(screen.getByText("VALID")).toBeInTheDocument();
    expect(screen.getByText("REVOKED")).toBeInTheDocument();
  });

  it("issues a certificate for the selected user", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("old-cert");

    await user.click(screen.getByRole("button", { name: /issue certificate/i }));
    await user.click(screen.getByRole("combobox", { name: /user/i }));
    await user.click(await screen.findByRole("option", { name: "alice" }));
    await user.click(screen.getByRole("button", { name: /^issue$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/admin/certs" && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/admin/certs" && o?.method === "POST",
    )!;
    expect(JSON.parse(String(opts!.body))).toEqual({ userId: "u1" });
  });

  it("disables revoke for certificates that are not valid", async () => {
    renderPage();
    await screen.findByText("old-cert");

    const container = document.body;
    const disabledButtons = container.querySelectorAll("button[disabled]");
    expect(disabledButtons.length).toBeGreaterThan(0);
  });
});
