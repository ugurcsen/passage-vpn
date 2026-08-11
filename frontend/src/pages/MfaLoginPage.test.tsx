import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@mui/material/styles";
import { QueryClientProvider } from "@tanstack/react-query";
import { darkTheme } from "@/theme";
import { queryClient } from "@/lib/queryClient";
import { MfaLoginPage } from "@/pages/MfaLoginPage";
import { ToastProvider } from "@/hooks/useToast";
import { AuthProvider } from "@/hooks/useAuth";

function renderMfa(preAuthToken = "tok-1") {
  return render(
    <ThemeProvider theme={darkTheme}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <ToastProvider>
            <MemoryRouter initialEntries={[{ pathname: "/login/mfa", state: { username: "alice", preAuthToken } }]}>
              <Routes>
                <Route path="/login/mfa" element={<MfaLoginPage />} />
                <Route path="/" element={<div>home</div>} />
              </Routes>
            </MemoryRouter>
          </ToastProvider>
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("MfaLoginPage", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("labels the verification code control with id/name (a11y)", () => {
    renderMfa();

    const code = screen.getByLabelText(/verification code/i);
    expect(code).toHaveAttribute("id", "code");
    expect(code).toHaveAttribute("name", "code");
    expect(screen.getByRole("button", { name: /verify/i })).toBeInTheDocument();
  });
});
