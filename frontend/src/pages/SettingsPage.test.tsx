import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ToastProvider } from "@/hooks/useToast";
import { SettingsPage } from "@/pages/SettingsPage";
import type { ServerSettings } from "@/lib/api";

function json(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

/** Stateful fetch mock: PUT/DELETE mutate the in-memory store and return it. */
function makeFetch(initial: ServerSettings) {
  const store: ServerSettings = { ...initial };
  return vi.fn((url: string, opts?: RequestInit) => {
    const keyMatch = String(url).match(/\/admin\/settings\/([^/]+)$/);
    const key = keyMatch ? decodeURIComponent(keyMatch[1]) : undefined;
    if (opts?.method === "PUT" && key) {
      const body = JSON.parse(String(opts.body)) as { value: unknown };
      store[key] = body.value;
      return Promise.resolve(json(store));
    }
    if (opts?.method === "DELETE" && key) {
      delete store[key];
      return Promise.resolve(new Response(null, { status: 204 }));
    }
    return Promise.resolve(json(store));
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

function putCalls(fetchMock: ReturnType<typeof makeFetch>) {
  return vi
    .mocked(fetchMock)
    .mock.calls.filter(([, opts]) => opts?.method === "PUT")
    .map(([url, opts]) => ({ url: String(url), body: JSON.parse(String(opts!.body)) as { value: unknown } }));
}

describe("SettingsPage", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("renders known settings with friendly labels and typed controls", async () => {
    vi.stubGlobal("fetch", makeFetch({ max_connections: 5, require_mfa_on_connect: true, dns_servers: "1.1.1.1, 8.8.8.8" }));
    renderPage();

    expect(await screen.findByText("Max connections")).toBeInTheDocument();
    expect(screen.getByText("5")).toBeInTheDocument();
    expect(screen.getByText("1.1.1.1, 8.8.8.8")).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Require MFA on connect" })).toBeChecked();
    expect(screen.getByText("3 configured")).toBeInTheDocument();
  });

  it("shows an empty state when no server defaults are configured", async () => {
    vi.stubGlobal("fetch", makeFetch({}));
    renderPage();

    expect(await screen.findByText("No server defaults configured yet.")).toBeInTheDocument();
    expect(screen.getByText("0 configured")).toBeInTheDocument();
  });

  it("toggles a boolean setting via switch and saves it", async () => {
    const fetchMock = makeFetch({ require_mfa_on_connect: true });
    vi.stubGlobal("fetch", fetchMock);
    renderPage();

    const toggle = await screen.findByRole("checkbox", { name: "Require MFA on connect" });
    await userEvent.setup().click(toggle);

    await waitFor(() => {
      expect(putCalls(fetchMock)).toContainEqual({
        url: "/api/admin/settings/require_mfa_on_connect",
        body: { value: false },
      });
    });
  });

  it("edits a list setting through the dialog and serializes it as a comma string", async () => {
    const fetchMock = makeFetch({ dns_servers: "1.1.1.1, 8.8.8.8" });
    vi.stubGlobal("fetch", fetchMock);
    renderPage();

    await userEvent.setup().click(await screen.findByLabelText("Edit DNS servers"));
    const input = await screen.findByLabelText("Value");
    expect(input).toHaveValue("1.1.1.1, 8.8.8.8");
    fireEvent.change(input, { target: { value: "9.9.9.9, 1.1.1.1" } });
    await userEvent.setup().click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => {
      expect(putCalls(fetchMock)).toContainEqual({
        url: "/api/admin/settings/dns_servers",
        body: { value: "9.9.9.9, 1.1.1.1" },
      });
    });
  });

  it("rejects an invalid number and saves a valid one", async () => {
    const fetchMock = makeFetch({ max_connections: 5 });
    vi.stubGlobal("fetch", fetchMock);
    renderPage();

    await userEvent.setup().click(await screen.findByLabelText("Edit Max connections"));
    const input = await screen.findByLabelText("Value");
    fireEvent.change(input, { target: { value: "1.5" } });
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();

    fireEvent.change(input, { target: { value: "12" } });
    await userEvent.setup().click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => {
      expect(putCalls(fetchMock)).toContainEqual({
        url: "/api/admin/settings/max_connections",
        body: { value: 12 },
      });
    });
  });

  it("adds a default setting through the typed dialog", async () => {
    const fetchMock = makeFetch({});
    vi.stubGlobal("fetch", fetchMock);
    renderPage();

    await userEvent.setup().click(await screen.findByRole("button", { name: "Add default" }));
    await userEvent.setup().click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => {
      expect(putCalls(fetchMock)).toContainEqual({
        url: "/api/admin/settings/default_group",
        body: { value: "" },
      });
    });
  });

  it("keeps custom settings out of the defaults section and shows them in the advanced section", async () => {
    vi.stubGlobal("fetch", makeFetch({ brand: "OpenVPN Panel", max_conn: 5 }));
    renderPage();

    expect(await screen.findByText("No server defaults configured yet.")).toBeInTheDocument();
    expect(screen.queryByText("brand")).not.toBeInTheDocument();

    await userEvent.setup().click(screen.getByLabelText("Toggle advanced settings"));
    expect(screen.getByText("brand")).toBeInTheDocument();
    expect(screen.getByText('"OpenVPN Panel"')).toBeInTheDocument();
    expect(screen.getByText("5")).toBeInTheDocument();
  });

  it("adds a custom setting with a JSON value", async () => {
    const fetchMock = makeFetch({});
    vi.stubGlobal("fetch", fetchMock);
    renderPage();

    await userEvent.setup().click(await screen.findByRole("button", { name: "Add custom setting" }));
    fireEvent.change(screen.getByLabelText("Key"), { target: { value: "theme" } });
    fireEvent.change(screen.getByLabelText(/Value \(JSON\)/), { target: { value: '"dark"' } });
    await userEvent.setup().click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => {
      expect(putCalls(fetchMock)).toContainEqual({
        url: "/api/admin/settings/theme",
        body: { value: "dark" },
      });
    });
  });

  it("deletes a known setting after confirmation", async () => {
    const fetchMock = makeFetch({ max_connections: 5 });
    vi.stubGlobal("fetch", fetchMock);
    renderPage();

    await userEvent.setup().click(await screen.findByLabelText("Delete Max connections"));
    await userEvent.setup().click(await screen.findByRole("button", { name: /delete/i }));

    await waitFor(() => {
      const del = vi
        .mocked(fetchMock)
        .mock.calls.find(([url, opts]) => url === "/api/admin/settings/max_connections" && opts?.method === "DELETE");
      expect(del).toBeDefined();
    });
  });
});
