import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
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
  {
    id: "c3",
    commonName: "expiring",
    userId: "u2",
    username: "bob",
    status: "VALID" as const,
    issuedAt: "2026-07-01T00:00:00Z",
    expiresAt: new Date(Date.now() + 10 * 24 * 60 * 60 * 1000).toISOString(),
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
        if (url.includes("/reconcile")) return Promise.resolve(json({ created: 1, updated: 0, skipped: 0 }));
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
    expect(screen.getAllByText("VALID").length).toBeGreaterThan(0);
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

  it("revokes a valid certificate after confirmation", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("old-cert");

    await user.click(screen.getAllByRole("button", { name: "Revoke certificate" })[0]);
    await user.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: /^revoke$/i }),
    );

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
         ([url, opts]) => typeof url === "string" && url.endsWith("/revoke") && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
  });

  it("restores a revoked certificate", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("old-cert");

    await user.click(screen.getByRole("button", { name: "Restore certificate" }));
    await user.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: /^restore$/i }),
    );

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
         ([url, opts]) => typeof url === "string" && url.endsWith("/restore") && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
  });

  it("rotates a valid certificate", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("old-cert");

    await user.click(screen.getAllByRole("button", { name: "Rotate certificate" })[0]);
    await user.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: /^rotate$/i }),
    );

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
         ([url, opts]) => typeof url === "string" && url.endsWith("/rotate") && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
  });

  it("shows an expiry badge for certificates expiring within 30 days", async () => {
    renderPage();
    await screen.findByText("expiring");

    const badges = screen.getAllByText("Expiring");
    expect(badges.length).toBeGreaterThan(0);
  });

  it("syncs the certificate list with the PKI on demand", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("old-cert");

    await user.click(screen.getByRole("button", { name: /sync with pki/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/admin/certs/reconcile" && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
  });
});
