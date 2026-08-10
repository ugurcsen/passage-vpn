import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
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
        <ToastProvider>
          <UsersPage />
        </ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("UsersPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal("fetch", vi.fn().mockImplementation((url: string) => {
      if (url.startsWith("/api/admin/groups")) return Promise.resolve(json(groups));
      return Promise.resolve(json(users));
    }));
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
});
