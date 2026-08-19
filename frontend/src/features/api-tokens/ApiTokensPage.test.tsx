import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ToastProvider } from "@/hooks/ToastContext";
import { ApiTokensPage } from "./ApiTokensPage";

const token = {
  id: "t1",
  label: "ci-deploy",
  prefix: "passage_3f2a9b…",
  role: "ADMIN",
  expiresAt: null,
  createdAt: "2026-08-13T10:00:00Z",
  lastUsedAt: null,
  createdBy: "admin",
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
          <ApiTokensPage />
        </ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("ApiTokensPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json([token])));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("lists existing tokens", async () => {
    renderPage();
    expect(await screen.findByText("ci-deploy")).toBeInTheDocument();
    expect(screen.getByText("passage_3f2a9b…")).toBeInTheDocument();
    expect(screen.getByText("ADMIN")).toBeInTheDocument();
  });

  it("creates a token and shows the one-time plaintext", async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes("/api-tokens") && init?.method === "POST") {
        return Promise.resolve(json({ token, rawToken: "passage_secretonce" }));
      }
      return Promise.resolve(json([token]));
    });
    vi.stubGlobal("fetch", fetchMock);
    renderPage();

    await screen.findByText("ci-deploy");
    await userEvent.click(screen.getByRole("button", { name: "New token" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText(/^label\s*\*?$/i), "nightly-report");
    await userEvent.click(within(dialog).getByRole("button", { name: "Create" }));

    expect(await screen.findByText(/passage_secretonce/)).toBeInTheDocument();
    const createCall = fetchMock.mock.calls.find(
      (c) => String(c[0]).includes("/api-tokens") && c[1]?.method === "POST",
    );
    expect(createCall).toBeTruthy();
    expect(String(createCall?.[1]?.method)).toBe("POST");
    expect(String(createCall?.[1]?.body)).toContain("nightly-report");
  });

  it("requires a label before creating", async () => {
    renderPage();
    await screen.findByText("ci-deploy");
    await userEvent.click(screen.getByRole("button", { name: "New token" }));
    const create = screen.getByRole("button", { name: "Create" });
    expect(create).toBeDisabled();
  });

  it("revokes a token after confirmation", async () => {
    const fetchMock = vi.mocked(fetch);
    renderPage();
    await screen.findByText("ci-deploy");

    await userEvent.click(
      within(screen.getByRole("grid")).getByRole("button", { name: "Revoke" }),
    );
    await userEvent.click(screen.getByRole("button", { name: "Revoke" }));

    await waitFor(() => {
      const deleteCall = fetchMock.mock.calls.find(
        (c) => String(c[0]).includes("/api-tokens/t1") && c[1]?.method === "DELETE",
      );
      expect(deleteCall).toBeTruthy();
    });
  });
});
