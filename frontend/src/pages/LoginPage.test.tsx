import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
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
            <MemoryRouter>
              <LoginPage />
            </MemoryRouter>
          </ToastProvider>
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("LoginPage", () => {
  it("renders the sign-in form", () => {
    renderLogin();
    expect(screen.getByLabelText(/username/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /sign in/i })).toBeInTheDocument();
  });
});
