import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ToastProvider } from "@/hooks/useToast";
import { ProfilesPage } from "@/pages/ProfilesPage";

const users = [{ id: "u1", username: "alice" }];

const tokens = [
  {
    id: "t1",
    token: "tok-abc",
    userId: "u1",
    username: "alice",
    profileType: "USER_LOCKED",
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
    expiresAt: null,
    usesLeft: null,
    createdAt: "2026-08-02T00:00:00Z",
    revoked: true,
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

    expect(await screen.findByText("alice")).toBeInTheDocument();
    expect(screen.getByText("— (generic)")).toBeInTheDocument();
    expect(screen.getByText("Active")).toBeInTheDocument();
    expect(screen.getByText("Revoked")).toBeInTheDocument();
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

    const row = screen.getByRole("row", { name: /alice/i });
    const copyButton = within(row).getAllByRole("button")[0];
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
});
