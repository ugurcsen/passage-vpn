import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ToastProvider } from "@/hooks/useToast";
import { NodesPage } from "@/pages/NodesPage";

const nodes = [
  {
    id: "n1",
    name: "edge-eu",
    mgmtHost: "vpn-eu.example.com",
    mgmtPortBase: 7505,
    adminIp: "10.0.0.5",
    enabled: true,
    createdAt: "2026-08-13T00:00:00Z",
    lastSeenAt: "2026-08-13T00:00:00Z",
    online: true,
  },
  {
    id: "n2",
    name: "edge-us",
    mgmtHost: "vpn-us.example.com",
    mgmtPortBase: 7505,
    adminIp: null,
    enabled: false,
    createdAt: "2026-08-13T00:00:00Z",
    lastSeenAt: null,
    online: false,
  },
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
          <NodesPage />
        </ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("NodesPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal("fetch", vi.fn().mockImplementation(() => Promise.resolve(json(nodes))));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("renders node rows with host and status", async () => {
    renderPage();

    expect(await screen.findByText("edge-eu")).toBeInTheDocument();
    expect(screen.getByText("vpn-eu.example.com")).toBeInTheDocument();
    expect(screen.getByText("Online")).toBeInTheDocument();
    expect(screen.getByText("Disabled")).toBeInTheDocument();
  });

  it("registers a node with normalized payload", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("edge-eu");

    await user.click(screen.getByRole("button", { name: /register node/i }));
    const dialog = screen.getByRole("dialog");

    fireEvent.change(within(dialog).getByLabelText(/^name/i), {
      target: { value: "  Edge-AP " },
    });
    fireEvent.change(within(dialog).getByLabelText(/^management host/i), {
      target: { value: " vpn-ap.example.com " },
    });
    fireEvent.change(within(dialog).getByLabelText(/^management port base/i), {
      target: { value: "7506" },
    });
    fireEvent.change(within(dialog).getByLabelText(/^admin ip/i), {
      target: { value: "10.0.0.9" },
    });
    await user.click(within(dialog).getByRole("button", { name: /^register$/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, opts]) => url === "/api/admin/nodes" && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
    const [, opts] = fetchMock.mock.calls.find(
      ([url, o]) => url === "/api/admin/nodes" && o?.method === "POST",
    )!;
    expect(JSON.parse(String(opts!.body))).toMatchObject({
      name: "edge-ap",
      mgmtHost: "vpn-ap.example.com",
      mgmtPortBase: 7506,
      adminIp: "10.0.0.9",
      enabled: true,
    });
  });

  it("rejects an out-of-range port in the form", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("edge-eu");

    await user.click(screen.getByRole("button", { name: /register node/i }));
    const dialog = screen.getByRole("dialog");
    const registerBtn = within(dialog).getByRole("button", { name: /^register$/i });

    fireEvent.change(within(dialog).getByLabelText(/^name/i), {
      target: { value: "edge-ap" },
    });
    fireEvent.change(within(dialog).getByLabelText(/^management host/i), {
      target: { value: "vpn-ap.example.com" },
    });
    fireEvent.change(within(dialog).getByLabelText(/^management port base/i), {
      target: { value: "70000" },
    });

    expect(registerBtn).toBeDisabled();
  });

  it("toggles a node enabled state", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("edge-eu");

    const switchEl = document.body.querySelector("input[type='checkbox']");
    expect(switchEl).not.toBeNull();
    await user.click(switchEl as Element);

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, opts]) =>
          url === "/api/admin/nodes/n1/enabled?enabled=false" && opts?.method === "POST",
      );
      expect(post).toBeDefined();
    });
  });
});
