import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ToastProvider } from "@/hooks/useToast";
import { MaintenancePage } from "./MaintenancePage";

const preflightResult = {
  passed: true,
  checks: [
    { name: "database", status: "PASS", detail: "Integrity check passed" },
    { name: "settings", status: "PASS", detail: "2 server setting(s) valid" },
    { name: "daemon-0", status: "PASS", detail: "Config accepted (exit 0)" },
    { name: "pki", status: "PASS", detail: "PKI files present; server certificate valid" },
  ],
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <ThemeProvider theme={darkTheme}>
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <MaintenancePage />
        </ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("MaintenancePage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json([])));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("runs preflight and lists every check", async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes("/system/preflight") && init?.method === "POST") {
        return Promise.resolve(json(preflightResult));
      }
      return Promise.resolve(json({}));
    });
    vi.stubGlobal("fetch", fetchMock);
    renderPage();

    await userEvent.click(screen.getByRole("button", { name: "Run preflight" }));

    expect(await screen.findByText("database")).toBeInTheDocument();
    expect(screen.getByText("Integrity check passed")).toBeInTheDocument();
    expect(screen.getByText("Ready")).toBeInTheDocument();
    const call = fetchMock.mock.calls.find((c) => String(c[0]).includes("/system/preflight"));
    expect(call).toBeTruthy();
  });

  it("shows a blocker when a check fails", async () => {
    const failing = {
      passed: false,
      checks: [
        { name: "database", status: "PASS", detail: "ok" },
        { name: "daemon-0", status: "FAIL", detail: "Options error: unknown option" },
      ],
    };
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes("/system/preflight") && init?.method === "POST") {
        return Promise.resolve(json(failing));
      }
      return Promise.resolve(json({}));
    });
    vi.stubGlobal("fetch", fetchMock);
    renderPage();

    await userEvent.click(screen.getByRole("button", { name: "Run preflight" }));

    expect(await screen.findByText("Issues found")).toBeInTheDocument();
    expect(screen.getByText(/blocked until the failing check/i)).toBeInTheDocument();
  });

  it("restarts the backend after confirmation", async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes("/system/restart-backend") && init?.method === "POST") {
        return Promise.resolve(json({ message: "Backend is restarting" }));
      }
      return Promise.resolve(json({}));
    });
    vi.stubGlobal("fetch", fetchMock);
    renderPage();

    await userEvent.click(screen.getByRole("button", { name: "Restart backend" }));
    await userEvent.click(screen.getByRole("button", { name: "Restart" }));

    await waitFor(() => {
      const call = fetchMock.mock.calls.find((c) =>
        String(c[0]).includes("/system/restart-backend"),
      );
      expect(call).toBeTruthy();
      expect(String(call?.[1]?.method)).toBe("POST");
    });
    expect(await screen.findByText(/Backend is restarting/i)).toBeInTheDocument();
  });

  it("reloads daemons and reports unreachable ones", async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes("/system/reload-daemons") && init?.method === "POST") {
        return Promise.resolve(json({ signaled: 1, total: 2, failed: [1] }));
      }
      return Promise.resolve(json({}));
    });
    vi.stubGlobal("fetch", fetchMock);
    renderPage();

    await userEvent.click(screen.getByRole("button", { name: "Reload daemons" }));
    await userEvent.click(screen.getByRole("button", { name: "Reload" }));

    await waitFor(() => {
      const call = fetchMock.mock.calls.find((c) =>
        String(c[0]).includes("/system/reload-daemons"),
      );
      expect(call).toBeTruthy();
    });
    expect(await screen.findByText(/unreachable: 1/i)).toBeInTheDocument();
  });
});
