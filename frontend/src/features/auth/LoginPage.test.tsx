import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@mui/material/styles";
import { QueryClientProvider } from "@tanstack/react-query";
import { darkTheme } from "@/theme";
import { queryClient } from "@/lib/queryClient";
import { LoginPage } from "./LoginPage";
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
                <Route path="/login/enroll" element={<div>enroll page</div>} />
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

  it("redirects to forced MFA enrollment when the account must enroll", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((url: string) => {
        if (url === "/api/auth/login") {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                mfaRequired: false,
                mustEnrollMfa: true,
                preAuthToken: "enroll-token",
                accessToken: null,
                refreshToken: null,
              }),
              { status: 200, headers: { "Content-Type": "application/json" } },
            ),
          );
        }
        return Promise.resolve(
          new Response(JSON.stringify({ state: "COMPLETE" }), {
            status: 200,
            headers: { "Content-Type": "application/json" },
          }),
        );
      }),
    );

    renderLogin();
    await user.type(screen.getByLabelText(/username/i), "alice");
    await user.type(screen.getByLabelText(/password/i), "supersecret1");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    expect(await screen.findByText("enroll page")).toBeInTheDocument();
  });
});
