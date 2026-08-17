import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import App from "@/App";
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

const profileTypes = [
  { type: "USER_LOCKED", label: "User locked", locked: false },
  { type: "AUTO_LOGIN", label: "Auto login", locked: false },
];

const dashboardStats = {
  users: 3,
  groups: 2,
  activeCertificates: 5,
  activeConnections: 1,
  runningDaemons: 1,
  totalDaemons: 1,
  recentConnections: [],
};

let role: Role = "USER";

function stubFetch() {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation((url: string) => {
      if (url.startsWith("/api/auth/me")) return Promise.resolve(json(meBody(role)));
      if (url.startsWith("/api/public/brand")) {
        return Promise.resolve(json({ name: "PassageVPN", primaryColor: "#4f8cff", footer: "", logoUrl: null }));
      }
      if (url.startsWith("/api/portal/profiles")) return Promise.resolve(json(profileTypes));
      if (url.startsWith("/api/admin/users")) return Promise.resolve(json([]));
      if (url.startsWith("/api/admin/groups")) return Promise.resolve(json([]));
      if (url.startsWith("/api/admin/dashboard")) return Promise.resolve(json(dashboardStats));
      if (url.startsWith("/api/admin/monitor")) return Promise.resolve(json({ at: "", connections: [], daemons: [], bytesInPerSec: 0, bytesOutPerSec: 0, activeConnections: 0, history: [], system: { cpuLoadPercent: 0, totalMemory: 0, freeMemory: 0, diskTotal: 0, diskFree: 0, availableProcessors: 1 } }));
      return Promise.resolve(json({}));
    }),
  );
}

function setPath(path: string) {
  window.history.pushState({}, "", path);
}

describe("role-based route guarding", () => {
  beforeEach(() => {
    role = "USER";
    localStorage.clear();
    localStorage.setItem("passage.access", "test-token");
    setPath("/");
    stubFetch();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("redirects a plain user from the admin dashboard to the portal", async () => {
    render(<App />);

    expect(
      await screen.findByText(/signed in as alice/i, undefined, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(screen.queryByText(/recent connections/i)).not.toBeInTheDocument();
  });

  it("redirects a plain user away from an admin route", async () => {
    setPath("/settings");
    render(<App />);

    expect(
      await screen.findByText(/signed in as alice/i, undefined, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(screen.queryByText(/server settings/i)).not.toBeInTheDocument();
  });

  it("redirects a group admin from the dashboard to the users page", async () => {
    role = "GROUP_ADMIN";
    setPath("/");
    render(<App />);

    expect(
      await screen.findByRole("button", { name: /new user/i }, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(screen.queryByText(/recent connections/i)).not.toBeInTheDocument();
  });

  it("lets an admin view the dashboard", async () => {
    role = "ADMIN";
    setPath("/");
    render(<App />);

    expect(
      await screen.findByText(/recent connections/i, undefined, { timeout: 5000 }),
    ).toBeInTheDocument();
  });
});
