import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ToastProvider } from "@/hooks/useToast";
import { SettingsPage } from "@/pages/SettingsPage";

const settings = { brand: "OpenVPN Panel", max_conn: 5 };

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
          <SettingsPage />
        </ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("SettingsPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal(
      "fetch",
      vi.fn((_url: string, opts: RequestInit) => {
        if (opts?.method === "PUT") return Promise.resolve(json({ ...settings, theme: "dark" }));
        if (opts?.method === "DELETE") return Promise.resolve(new Response(null, { status: 204 }));
        return Promise.resolve(json(settings));
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("lists stored settings with JSON values", async () => {
    renderPage();

    expect(await screen.findByText("brand")).toBeInTheDocument();
    expect(screen.getByText('"OpenVPN Panel"')).toBeInTheDocument();
    expect(screen.getByText("max_conn")).toBeInTheDocument();
    expect(screen.getByText("5")).toBeInTheDocument();
  });

  it("saves a new setting via PUT", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("brand");

    fireEvent.change(screen.getByLabelText("Key"), { target: { value: "theme" } });
    fireEvent.change(screen.getByLabelText(/Value \(JSON\)/), { target: { value: '"dark"' } });
    await user.click(screen.getByRole("button", { name: /add/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const put = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/admin/settings/theme" && opts?.method === "PUT",
      );
      expect(put).toBeDefined();
    });
    const put = fetchMock.mock.calls.find(
      ([url, opts]) => url === "/api/admin/settings/theme" && opts?.method === "PUT",
    )!;
    expect(JSON.parse(String(put[1]!.body))).toEqual({ value: "dark" });
    expect(await screen.findByText("Setting saved")).toBeInTheDocument();
  });

  it("deletes a setting after confirmation", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("brand");

    await user.click(screen.getByLabelText("Delete brand"));
    await user.click(await screen.findByRole("button", { name: /delete/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const del = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/admin/settings/brand" && opts?.method === "DELETE",
      );
      expect(del).toBeDefined();
    });
    expect(await screen.findByText("Setting deleted")).toBeInTheDocument();
  });
});
