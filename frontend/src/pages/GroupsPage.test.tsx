import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { AuthProvider } from "@/hooks/useAuth";
import { ToastProvider } from "@/hooks/useToast";
import { GroupsPage } from "@/pages/GroupsPage";

const groups = [
  { id: "g1", name: "devs", description: "Engineering", parentId: null, memberCount: 1 },
  { id: "g2", name: "ops", description: null, parentId: "g1", memberCount: 0 },
];

const users = [{ id: "u1", username: "alice" }];

const me = {
  id: "admin1",
  username: "admin",
  role: "ADMIN",
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

function rowByName(name: string) {
  const cell = screen
    .getAllByRole("gridcell", { name })
    .find((c) => c.getAttribute("data-field") === "name");
  return cell!.closest('[role="row"]') as HTMLElement;
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <ThemeProvider theme={darkTheme}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <ToastProvider>
            <GroupsPage />
          </ToastProvider>
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("GroupsPage", () => {
  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem("passage.access", "test-token");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((url: string, opts?: RequestInit) => {
        if (url.startsWith("/api/auth/me")) return Promise.resolve(json(me));
        if (url === "/api/admin/users") return Promise.resolve(json(users));
        if (url.endsWith("/members")) {
          if (opts?.method === "PUT") return Promise.resolve(json({ ok: true }));
          return Promise.resolve(json(["u1"]));
        }
        if (url === "/api/admin/groups") {
          if (opts?.method === "POST") return Promise.resolve(json({ id: "g3" }));
          return Promise.resolve(json(groups));
        }
        if (url.endsWith("/settings")) return Promise.resolve(json({}));
        return Promise.resolve(json([]));
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("renders groups with parents and member counts", async () => {
    renderPage();

    expect((await screen.findAllByText("devs")).length).toBeGreaterThan(0);
    expect(screen.getByText("ops")).toBeInTheDocument();
    expect(screen.getByText("Engineering")).toBeInTheDocument();

    const opsRow = rowByName("ops");
    expect(opsRow).toHaveTextContent("devs");
    expect(opsRow).not.toHaveTextContent("g1");
  });

  it("hides Delete on managed root groups for group admins but keeps it on subgroups", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((url: string) => {
        if (url.startsWith("/api/auth/me"))
          return Promise.resolve(
            json({ ...me, id: "ga1", username: "devops_lead", role: "GROUP_ADMIN" }),
          );
        if (url === "/api/admin/users") return Promise.resolve(json(users));
        if (url === "/api/admin/groups") return Promise.resolve(json(groups));
        if (url.endsWith("/members")) return Promise.resolve(json(["u1"]));
        if (url.endsWith("/settings")) return Promise.resolve(json({}));
        return Promise.resolve(json([]));
      }),
    );

    renderPage();
    await screen.findAllByText("devs");

    const rootRow = rowByName("devs");
    expect(
      within(rootRow).queryByRole("button", { name: /^delete$/i }),
    ).not.toBeInTheDocument();

    const subRow = rowByName("ops");
    expect(within(subRow).getByRole("button", { name: /^delete$/i })).toBeInTheDocument();
  });

  it("creates a group", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByText("devs");

    await user.click(screen.getByRole("button", { name: /new group/i }));
    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/name/i), {
      target: { value: "security" },
    });
    await waitFor(() =>
      expect(within(dialog).getByRole("button", { name: /^create$/i })).toBeEnabled(),
    );
    await user.click(within(dialog).getByRole("button", { name: /^create$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, o]) => url === "/api/admin/groups" && o?.method === "POST",
      );
      expect(post).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/admin/groups" && o?.method === "POST",
    )!;
    expect(JSON.parse(String(opts!.body))).toEqual({ name: "security", description: null, parentId: null });
  });

  it("edits members of a group", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByText("devs");

    const row = rowByName("devs");
    const actions = within(row).getAllByRole("button");
    await user.click(actions[0]);

    await screen.findByText(/members of devs/i);
    expect(screen.getByRole("checkbox", { name: /alice/i })).toBeChecked();

    await user.click(screen.getByRole("button", { name: /save members/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const put = fetchMock.mock.calls.find(
        ([url, o]) => url === "/api/admin/groups/g1/members" && o?.method === "PUT",
      );
      expect(put).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/admin/groups/g1/members" && o?.method === "PUT",
    )!;
    expect(JSON.parse(String(opts!.body))).toEqual({ userIds: ["u1"] });
  });

  it("sets a static IP pool for a group", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByText("devs");

    await user.click(screen.getByTestId("edit-pool-devs"));
    await screen.findByText(/static ip pool — devs/i);

    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/ip range/i), {
      target: { value: "10.8.0.100-10.8.0.199" },
    });
    await user.click(within(dialog).getByRole("button", { name: /save pool/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const put = fetchMock.mock.calls.find(
        ([url, o]) => url === "/api/admin/groups/g1/static-ip-pool" && o?.method === "PUT",
      );
      expect(put).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/admin/groups/g1/static-ip-pool" && o?.method === "PUT",
    )!;
    expect(JSON.parse(String(opts!.body))).toEqual({ pool: "10.8.0.100-10.8.0.199" });
  });

  it("sets a tunnel mode for a group", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByText("devs");

    const row = rowByName("devs");
    const actions = within(row).getAllByRole("button");
    await user.click(actions[3]);

    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByLabelText(/tunnel mode/i));
    await user.click(await screen.findByRole("option", { name: /split tunnel/i }));
    await waitFor(() =>
      expect(within(dialog).getByRole("button", { name: /^save$/i })).toBeEnabled(),
    );
    await user.click(within(dialog).getByRole("button", { name: /^save$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const put = fetchMock.mock.calls.find(
        ([url, o]) => url === "/api/admin/groups/g1/settings/tunnel_mode" && o?.method === "PUT",
      );
      expect(put).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/admin/groups/g1/settings/tunnel_mode" && o?.method === "PUT",
    )!;
    expect(JSON.parse(String(opts!.body))).toBe("split");
  });

  it("clears a group tunnel mode when left on inherit default", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByText("devs");

    const row = rowByName("devs");
    const actions = within(row).getAllByRole("button");
    await user.click(actions[3]);

    const dialog = screen.getByRole("dialog");
    await waitFor(() =>
      expect(within(dialog).getByRole("button", { name: /^save$/i })).toBeEnabled(),
    );
    await user.click(within(dialog).getByRole("button", { name: /^save$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const del = fetchMock.mock.calls.find(
        ([url, o]) => url === "/api/admin/groups/g1/settings/tunnel_mode" && o?.method === "DELETE",
      );
      expect(del).toBeDefined();
    });
  });
});
