import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@mui/material/styles";
import { QueryClientProvider } from "@tanstack/react-query";
import { darkTheme } from "@/theme";
import { queryClient } from "@/lib/queryClient";
import { LoginPage } from "@/pages/LoginPage";
import { ToastProvider } from "@/hooks/useToast";
import { AuthProvider } from "@/hooks/useAuth";

function renderLogin() {
  return render(
    <ThemeProvider theme={darkTheme}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <ToastProvider>
            <MemoryRouter initialEntries={["/login"]}>
              <Routes>
                <Route path="/login" element={<LoginPage />} />
                <Route path="/setup" element={<div>wizard page</div>} />
              </Routes>
            </MemoryRouter>
          </ToastProvider>
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("LoginPage", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("renders the sign-in form when setup is complete", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() =>
        Promise.resolve(
          new Response(JSON.stringify({ state: "COMPLETE" }), {
            status: 200,
            headers: { "Content-Type": "application/json" },
          }),
        ),
      ),
    );

    renderLogin();
    expect(screen.getByLabelText(/username/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /sign in/i })).toBeInTheDocument();
  });

  it("redirects to the setup wizard while setup is incomplete", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() =>
        Promise.resolve(
          new Response(JSON.stringify({ state: "ADMIN_DONE", adminStepRequired: true }), {
            status: 200,
            headers: { "Content-Type": "application/json" },
          }),
        ),
      ),
    );

    renderLogin();
    expect(await screen.findByText("wizard page")).toBeInTheDocument();
  });

  it("labels all login form controls with id/name (a11y)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() =>
        Promise.resolve(
          new Response(JSON.stringify({ state: "COMPLETE" }), {
            status: 200,
            headers: { "Content-Type": "application/json" },
          }),
        ),
      ),
    );

    renderLogin();

    const username = screen.getByLabelText(/username/i);
    expect(username).toHaveAttribute("id", "username");
    expect(username).toHaveAttribute("name", "username");

    const password = screen.getByLabelText(/password/i);
    expect(password).toHaveAttribute("id", "password");
    expect(password).toHaveAttribute("name", "password");
  });
});
