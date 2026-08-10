import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ToastProvider } from "@/hooks/useToast";
import { AccessRulesPage } from "@/pages/AccessRulesPage";

const rules = [
  {
    id: "r1",
    targetType: "GLOBAL" as const,
    targetId: null,
    targetName: null,
    action: "ALLOW" as const,
    protocol: "TCP" as const,
    dstCidr: "10.0.0.0/8",
    dstPort: 443,
    enabled: true,
    priority: 1,
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
          <AccessRulesPage />
        </ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("AccessRulesPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((url: string) => {
        if (url.startsWith("/api/admin/groups")) return Promise.resolve(json(groups));
        if (url.startsWith("/api/admin/users")) return Promise.resolve(json(users));
        return Promise.resolve(json(rules));
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("renders a global rule row", async () => {
    renderPage();

    expect(await screen.findByText("All users")).toBeInTheDocument();
    expect(screen.getByText("ALLOW")).toBeInTheDocument();
    expect(screen.getByText("10.0.0.0/8:443")).toBeInTheDocument();
  });

  it("creates a global rule without a target select", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("All users");

    await user.click(screen.getByRole("button", { name: /new rule/i }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).queryByLabelText(/^user$/i)).toBeNull();
    expect(within(dialog).queryByLabelText(/^group$/i)).toBeNull();

    fireEvent.change(within(dialog).getByLabelText(/destination port/i), {
      target: { value: "8443" },
    });
    await user.click(within(dialog).getByRole("button", { name: /^create$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/admin/rules" && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/admin/rules" && o?.method === "POST",
    )!;
    expect(JSON.parse(String(opts!.body))).toMatchObject({
      targetType: "GLOBAL",
      targetId: null,
      action: "ALLOW",
      dstPort: 8443,
    });
  });

  it("creates a user-scoped rule with the chosen target", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("All users");

    await user.click(screen.getByRole("button", { name: /new rule/i }));
    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByRole("combobox", { name: /applies to/i }));
    await user.click(await screen.findByRole("option", { name: /a specific user/i }));
    await user.click(within(dialog).getByRole("combobox", { name: /^user$/i }));
    await user.click(await screen.findByRole("option", { name: "alice" }));
    await user.click(within(dialog).getByRole("button", { name: /^create$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/admin/rules" && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/admin/rules" && o?.method === "POST",
    )!;
    expect(JSON.parse(String(opts!.body))).toMatchObject({
      targetType: "USER",
      targetId: "u1",
    });
  });

  it("toggles a rule enabled state", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("All users");

    const switchEl = document.body.querySelector("input[type='checkbox']");
    expect(switchEl).not.toBeNull();
    await user.click(switchEl as Element);

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, opts]) =>
          url === "/api/admin/rules/r1/enabled?enabled=false" && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
  });
});
