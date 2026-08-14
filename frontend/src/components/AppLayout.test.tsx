import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@mui/material/styles";
import { MemoryRouter } from "react-router-dom";
import { darkTheme } from "@/theme";
import { AuthProvider } from "@/hooks/useAuth";
import { AppLayout } from "@/components/AppLayout";
import type { Role } from "@/hooks/useAuth";

function meBody(role: Role) {
  return {
    id: "u1",
    username: "alice",
    role,
    mfaEnabled: false,
    banned: false,
    mustChangePassword: false,
    groups: [],
  };
}

function json(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

let role: Role = "USER";

function renderLayout() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <ThemeProvider theme={darkTheme}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <MemoryRouter initialEntries={["/portal"]}>
            <AppLayout darkMode={false} onToggleDarkMode={() => undefined} />
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("AppLayout navigation by role", () => {
  beforeEach(() => {
    role = "USER";
    localStorage.clear();
    localStorage.setItem("opnl.access", "test-token");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((url: string) => {
        if (url.startsWith("/api/auth/me")) return Promise.resolve(json(meBody(role)));
        return Promise.resolve(json({}));
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  const present = (label: string) => {
    expect(screen.getAllByText(label).length).toBeGreaterThan(0);
  };

  const absent = (label: string) => {
    expect(screen.queryAllByText(label).length).toBe(0);
  };

  it("shows every navigation item for admins", async () => {
    role = "ADMIN";
    renderLayout();
    await screen.findAllByText("My Profiles");

    present("Dashboard");
    present("Users");
    present("Groups");
    present("Certificates");
    present("Access Rules");
    present("Connection Profiles");
    present("VPN Daemons");
    present("VPN Nodes");
    present("Live Status");
    present("Settings");
    present("Branding");
    present("Config Report");
    present("Backups");
    present("Audit Log");
    present("API Tokens");
  });

  it("restricts resellers to user management and the portal", async () => {
    role = "RESELLER";
    renderLayout();
    await screen.findAllByText("My Profiles");

    present("Users");
    present("My Account");
    absent("Dashboard");
    absent("Groups");
    absent("Settings");
    absent("API Tokens");
  });

  it("shows plain users only the self-service pages", async () => {
    role = "USER";
    renderLayout();
    await screen.findAllByText("My Profiles");

    present("My Account");
    absent("Dashboard");
    absent("Users");
    absent("Groups");
    absent("Certificates");
    absent("Settings");
    absent("Branding");
    absent("Backups");
  });
});
