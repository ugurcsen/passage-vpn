import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
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
    localStorage.setItem("passage.access", "test-token");
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

  /** Click a group header to expand its children. Scoped to the visible drawer. */
  const expandGroup = (groupLabel: string) => {
    // Two drawers exist (permanent + temporary); target the visible permanent one.
    const drawer = screen.getAllByRole("navigation")[0];
    const header = within(drawer).getByText(groupLabel);
    // MUI v6 ListItemButton renders as <div role="button">, not <li>
    const btn = header.closest('[role="button"]');
    fireEvent.click(btn!);
  };

  it("shows every navigation item for admins", async () => {
    role = "ADMIN";
    renderLayout();
    await screen.findAllByText("My Profiles");

    // Portal group is expanded by default (active route is /portal)
    present("My Profiles");
    present("My Account");

    // Expand remaining groups
    expandGroup("Overview");
    present("Dashboard");

    expandGroup("User Management");
    present("Users");
    present("Groups");

    expandGroup("VPN");
    present("Certificates");
    present("Access Rules");
    present("Connection Profiles");
    present("VPN Daemons");
    present("VPN Nodes");

    expandGroup("Monitoring");
    present("Live Status");

    expandGroup("System");
    present("Settings");
    present("Branding");
    present("Config Report");
    present("Backups");
    present("Audit Log");
    present("API Tokens");
  });

  it("restricts group admins to user management, groups, connection logs and the portal", async () => {
    role = "GROUP_ADMIN";
    renderLayout();
    await screen.findAllByText("My Profiles");

    // Portal group is expanded by default (active route is /portal)
    present("My Profiles");
    present("My Account");

    expandGroup("User Management");
    present("Users");
    present("Groups");

    expandGroup("Monitoring");
    present("Connection Logs");

    absent("Dashboard");
    absent("Settings");
    absent("API Tokens");
    absent("Live Status");
    absent("VPN");
    absent("System");
    absent("Overview");
  });

  it("shows plain users only the self-service pages", async () => {
    role = "USER";
    renderLayout();
    await screen.findAllByText("My Profiles");

    // Portal group is expanded by default (active route is /portal)
    present("My Profiles");
    present("My Account");

    absent("Dashboard");
    absent("Users");
    absent("Groups");
    absent("Certificates");
    absent("Settings");
    absent("Branding");
    absent("Backups");
    absent("VPN");
    absent("System");
    absent("Overview");
  });
});
