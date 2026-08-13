import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ToastProvider } from "@/hooks/useToast";
import { DnsOverridesPage } from "@/pages/DnsOverridesPage";

const records = [
  {
    id: "d1",
    hostname: "git.internal",
    ipv4: "10.10.0.5",
    ipv6: "fd00:1::5",
    scope: "GLOBAL" as const,
    scopeId: null,
    scopeName: null,
    enabled: true,
    createdAt: "2026-08-13T00:00:00Z",
  },
  {
    id: "d2",
    hostname: "db.internal",
    ipv4: "10.10.0.6",
    ipv6: null,
    scope: "GROUP" as const,
    scopeId: "g1",
    scopeName: "devs",
    enabled: true,
    createdAt: "2026-08-13T00:00:00Z",
  },
];

const users = [{ id: "u1", username: "alice" }];
const groups = [{ id: "g1", name: "devs" }];

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
          <DnsOverridesPage />
        </ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("DnsOverridesPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((url: string) => {
        if (url.startsWith("/api/admin/groups")) return Promise.resolve(json(groups));
        if (url.startsWith("/api/admin/users")) return Promise.resolve(json(users));
        return Promise.resolve(json(records));
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("renders a global record row", async () => {
    renderPage();

    expect((await screen.findAllByText("All users")).length).toBeGreaterThan(0);
    expect(screen.getByText("git.internal")).toBeInTheDocument();
    expect(screen.getByText("10.10.0.5")).toBeInTheDocument();
  });

  it("renders a group-scoped scope column", async () => {
    renderPage();

    expect(await screen.findByText("Group: devs")).toBeInTheDocument();
  });

  it("creates a global override", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByText("All users");

    await user.click(screen.getByRole("button", { name: /new override/i }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).queryByLabelText(/^user$/i)).toBeNull();
    expect(within(dialog).queryByLabelText(/^group$/i)).toBeNull();

    fireEvent.change(within(dialog).getByLabelText(/hostname/i), {
      target: { value: "  NAS.Internal " },
    });
    fireEvent.change(within(dialog).getByLabelText(/ipv4 address/i), {
      target: { value: "10.10.0.9" },
    });
    await user.click(within(dialog).getByRole("button", { name: /^create$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/admin/dns-overrides" && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/admin/dns-overrides" && o?.method === "POST",
    )!;
    expect(JSON.parse(String(opts!.body))).toMatchObject({
      hostname: "nas.internal",
      ipv4: "10.10.0.9",
      scope: "GLOBAL",
      scopeId: null,
      enabled: true,
    });
  });

  it("creates a user-scoped override with the chosen target", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByText("All users");

    await user.click(screen.getByRole("button", { name: /new override/i }));
    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByRole("combobox", { name: /scope/i }));
    await user.click(await screen.findByRole("option", { name: /a specific user/i }));
    await user.click(within(dialog).getByRole("combobox", { name: /^user$/i }));
    await user.click(await screen.findByRole("option", { name: "alice" }));
    fireEvent.change(within(dialog).getByLabelText(/hostname/i), {
      target: { value: "db.internal" },
    });
    fireEvent.change(within(dialog).getByLabelText(/ipv4 address/i), {
      target: { value: "10.10.0.6" },
    });
    await user.click(within(dialog).getByRole("button", { name: /^create$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/admin/dns-overrides" && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/admin/dns-overrides" && o?.method === "POST",
    )!;
    expect(JSON.parse(String(opts!.body))).toMatchObject({
      hostname: "db.internal",
      scope: "USER",
      scopeId: "u1",
    });
  });

  it("toggles a record enabled state", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByText("All users");

    const switchEl = document.body.querySelector("input[type='checkbox']");
    expect(switchEl).not.toBeNull();
    await user.click(switchEl as Element);

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, opts]) =>
          url === "/api/admin/dns-overrides/d1/enabled" && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/admin/dns-overrides/d1/enabled" && o?.method === "POST",
    )!;
    expect(JSON.parse(String(opts!.body))).toBe(false);
  });
});
