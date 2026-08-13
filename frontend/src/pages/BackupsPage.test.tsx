import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ToastProvider } from "@/hooks/useToast";
import { BackupsPage } from "@/pages/BackupsPage";

const backup = {
  name: "opnl-backup-20260813-100000.zip",
  sizeBytes: 2048,
  createdAt: "2026-08-13T10:00:00Z",
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
          <BackupsPage />
        </ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("BackupsPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json([backup])));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("lists existing backups with size", async () => {
    renderPage();
    expect(await screen.findByText(backup.name)).toBeInTheDocument();
    expect(screen.getByText("2.0 KB")).toBeInTheDocument();
  });

  it("creates a backup", async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes("/admin/backups") && init?.method === "POST") {
        return Promise.resolve(json(backup));
      }
      return Promise.resolve(json([backup]));
    });
    vi.stubGlobal("fetch", fetchMock);
    renderPage();

    await screen.findByText(backup.name);
    await userEvent.click(screen.getByRole("button", { name: "Create backup" }));

    await waitFor(() => {
      const call = fetchMock.mock.calls.find((c) => c[1]?.method === "POST");
      expect(call).toBeTruthy();
    });
    expect(await screen.findByText(/Backup created/)).toBeInTheDocument();
  });

  it("restores after confirmation", async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes("/restore") && init?.method === "POST") {
        return Promise.resolve(json({ restartRequired: true, message: "Restored" }));
      }
      return Promise.resolve(json([backup]));
    });
    vi.stubGlobal("fetch", fetchMock);
    renderPage();
    await screen.findByText(backup.name);

    await userEvent.click(
      within(screen.getByRole("table")).getByRole("button", { name: `Restore ${backup.name}` }),
    );
    await userEvent.click(screen.getByRole("button", { name: "Restore" }));

    await waitFor(() => {
      const call = fetchMock.mock.calls.find((c) => String(c[0]).includes("/restore"));
      expect(call).toBeTruthy();
      expect(String(call?.[1]?.method)).toBe("POST");
    });
    expect(await screen.findByText(/must be restarted/i)).toBeInTheDocument();
  });
});
