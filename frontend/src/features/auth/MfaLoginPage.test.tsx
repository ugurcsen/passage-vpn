import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@mui/material/styles";
import { QueryClientProvider } from "@tanstack/react-query";
import { darkTheme } from "@/theme";
import { queryClient } from "@/lib/queryClient";
import { MfaLoginPage } from "./MfaLoginPage";
import { ToastProvider } from "@/hooks/useToast";
import { AuthProvider } from "@/hooks/useAuth";

const me = {
  id: "u1",
  username: "alice",
  role: "ADMIN",
  mfaEnabled: true,
  banned: false,
  mustChangePassword: false,
  groups: [],
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function renderMfa(preAuthToken?: string) {
  return render(
    <ThemeProvider theme={darkTheme}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <ToastProvider>
            <MemoryRouter
              initialEntries={[{ pathname: "/login/mfa", state: { username: "alice", preAuthToken } }]}
            >
              <Routes>
                <Route path="/login/mfa" element={<MfaLoginPage />} />
                <Route path="/login" element={<div>login-screen</div>} />
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

  it("verifies the code and navigates home on success", async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === "/api/auth/mfa") {
        return Promise.resolve(
          json({ accessToken: "acc-1", refreshToken: "ref-1", mfaRequired: false }),
        );
      }
      if (url === "/api/auth/me") {
        return Promise.resolve(json(me));
      }
      return Promise.resolve(json({}));
    });
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();

    renderMfa("tok-1");
    await user.type(screen.getByLabelText(/verification code/i), "123456");
    await user.click(screen.getByRole("button", { name: /verify/i }));

    expect(await screen.findByText("home")).toBeInTheDocument();
    const mfaCall = fetchMock.mock.calls.find(([url]) => url === "/api/auth/mfa");
    expect(mfaCall?.[1]).toMatchObject({
      method: "POST",
      body: JSON.stringify({ preAuthToken: "tok-1", code: "123456" }),
    });
  });

  it("shows a toast and stays on the page when verification fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((url: string) => {
        if (url === "/api/auth/mfa") {
          return Promise.resolve(
            json({ message: "Invalid or expired code" }, 401),
          );
        }
        return Promise.resolve(json({}));
      }),
    );
    const user = userEvent.setup();

    renderMfa("tok-1");
    await user.type(screen.getByLabelText(/verification code/i), "000000");
    await user.click(screen.getByRole("button", { name: /verify/i }));

    expect(await screen.findByText(/invalid or expired code/i)).toBeInTheDocument();
    expect(screen.queryByText("home")).not.toBeInTheDocument();
  });

  it("redirects to login when the pre-auth token is missing", async () => {
    const user = userEvent.setup();

    renderMfa(undefined);
    await user.type(screen.getByLabelText(/verification code/i), "123456");
    await user.click(screen.getByRole("button", { name: /verify/i }));

    expect(await screen.findByText(/session expired/i)).toBeInTheDocument();
    expect(await screen.findByText("login-screen")).toBeInTheDocument();
  });
});
