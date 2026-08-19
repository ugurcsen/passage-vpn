import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@mui/material/styles";
import { QueryClientProvider } from "@tanstack/react-query";
import { darkTheme } from "@/theme";
import { queryClient } from "@/lib/queryClient";
import { MfaEnrollPage } from "./MfaEnrollPage";
import { ToastProvider } from "@/hooks/useToast";
import { AuthProvider } from "@/hooks/useAuth";

const QR = "data:image/png;base64,AAAA";
const SECRET = "JBSWY3DPEHPK3PXP";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function renderEnroll(preAuthToken = "tok-1") {
  return render(
    <ThemeProvider theme={darkTheme}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <ToastProvider>
            <MemoryRouter initialEntries={[{ pathname: "/login/enroll", state: { username: "alice", preAuthToken } }]}>
              <Routes>
                <Route path="/login/enroll" element={<MfaEnrollPage />} />
                <Route path="/login" element={<div>login page</div>} />
                <Route path="/" element={<div>home</div>} />
              </Routes>
            </MemoryRouter>
          </ToastProvider>
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("MfaEnrollPage", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("loads the QR setup and enables MFA after a valid code", async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === "/api/auth/mfa/enroll") {
        return Promise.resolve(json({ secret: SECRET, otpAuthUrl: "otpauth://totp/alice", qrDataUrl: QR }));
      }
      if (url === "/api/auth/mfa/enroll/confirm") {
        return Promise.resolve(json({ accessToken: "access", refreshToken: "refresh" }));
      }
      if (url === "/api/auth/me") {
        return Promise.resolve(json({ id: "u1", username: "alice", role: "USER", mfaEnabled: true, banned: false, mustChangePassword: false, groups: [] }));
      }
      return Promise.resolve(json({}, 404));
    });
    vi.stubGlobal("fetch", fetchMock);

    renderEnroll();

    expect(await screen.findByText(/required for alice/i)).toBeInTheDocument();
    const img = screen.getByAltText("TOTP QR code") as HTMLImageElement;
    expect(img.src).toContain("base64,AAAA");
    expect(screen.getByDisplayValue(SECRET)).toBeInTheDocument();

    const code = screen.getByLabelText(/verification code/i);
    await userEvent.type(code, "123456");
    await userEvent.click(screen.getByRole("button", { name: /enable and sign in/i }));

    expect(await screen.findByText("home")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/auth/mfa/enroll/confirm",
      expect.objectContaining({ method: "POST" }),
    );
  });

  it("redirects to login when no preAuthToken is present", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() => Promise.resolve(json({ state: "COMPLETE" }))),
    );

    renderEnroll("");

    expect(await screen.findByText("login page")).toBeInTheDocument();
  });
});
