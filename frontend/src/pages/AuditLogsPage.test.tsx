import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { AuditLogsPage } from "@/pages/AuditLogsPage";

const page = {
  content: [
    {
      id: "a1",
      actorId: "u1",
      actorName: "admin",
      action: "USER_CREATE",
      category: "USER",
      targetId: "u2",
      targetType: "user",
      detail: '{"username":"alice"}',
      ip: "10.0.0.1",
      createdAt: "2026-08-12T10:00:00Z",
    },
    {
      id: "a2",
      actorId: null,
      actorName: null,
      action: "LOGIN_SUCCESS",
      category: "AUTH",
      targetId: "u1",
      targetType: "user",
      detail: null,
      ip: "10.0.0.2",
      createdAt: "2026-08-12T09:30:00Z",
    },
  ],
  page: 0,
  size: 50,
  totalElements: 2,
  totalPages: 1,
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
        <AuditLogsPage />
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("AuditLogsPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(page)));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("renders audit entries in the grid", async () => {
    renderPage();
    expect(await screen.findByText("USER_CREATE")).toBeInTheDocument();
    expect(screen.getByText("LOGIN_SUCCESS")).toBeInTheDocument();
    expect(screen.getByText("admin")).toBeInTheDocument();
    expect(screen.getByText("10.0.0.1")).toBeInTheDocument();
  });

  it("requests the audit log endpoint with the default page params", async () => {
    renderPage();
    await screen.findByText("USER_CREATE");
    const fetchMock = vi.mocked(fetch);
    const url = fetchMock.mock.calls.find((c) => String(c[0]).includes("/admin/audit-logs"))?.[0];
    expect(String(url)).toContain("page=0");
    expect(String(url)).toContain("size=50");
  });

  it("applies filters on the Apply button", async () => {
    renderPage();
    const actionInput = (await screen.findByLabelText("Action")) as HTMLInputElement;
    await userEvent.type(actionInput, "USER_CREATE");
    const apply = screen.getByRole("button", { name: "Apply" });
    await userEvent.click(apply);
    await waitFor(() => {
      const fetchMock = vi.mocked(fetch);
      expect(
        fetchMock.mock.calls.some((c) => String(c[0]).includes("action=USER_CREATE")),
      ).toBe(true);
    });
  });

  it("shows a dash for missing actors and details", async () => {
    renderPage();
    const grid = await screen.findByRole("grid");
    await waitFor(() => expect(within(grid).getAllByText("—").length).toBeGreaterThan(0));
  });
});
