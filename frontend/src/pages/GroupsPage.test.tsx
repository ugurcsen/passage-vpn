import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ToastProvider } from "@/hooks/useToast";
import { GroupsPage } from "@/pages/GroupsPage";

const groups = [
  { id: "g1", name: "devs", description: "Engineering", parentId: null, memberCount: 1 },
  { id: "g2", name: "ops", description: null, parentId: "g1", memberCount: 0 },
];

const users = [{ id: "u1", username: "alice" }];

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
          <GroupsPage />
        </ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("GroupsPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((url: string, opts?: RequestInit) => {
        if (url === "/api/admin/users") return Promise.resolve(json(users));
        if (url.endsWith("/members")) {
          if (opts?.method === "PUT") return Promise.resolve(json({ ok: true }));
          return Promise.resolve(json(["u1"]));
        }
        if (url === "/api/admin/groups") {
          if (opts?.method === "POST") return Promise.resolve(json({ id: "g3" }));
          return Promise.resolve(json(groups));
        }
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

    expect(await screen.findByText("devs")).toBeInTheDocument();
    expect(screen.getByText("ops")).toBeInTheDocument();
    expect(screen.getByText("Engineering")).toBeInTheDocument();
  });

  it("creates a group", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("devs");

    await user.click(screen.getByRole("button", { name: /new group/i }));
    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/name/i), {
      target: { value: "security" },
    });
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
    await screen.findByText("devs");

    const row = screen.getByRole("row", { name: /devs/i });
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
});
