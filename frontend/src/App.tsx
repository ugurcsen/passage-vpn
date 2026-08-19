import { Box, CircularProgress, ThemeProvider } from "@mui/material";
import { lazy, Suspense, useState, type ReactNode } from "react";
import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { AuthProvider } from "@/hooks/AuthContext";
import { useAuth, type Role } from "@/hooks/useAuth";
import { BrandProvider } from "@/hooks/BrandContext";
import { useBrand } from "@/hooks/useBrand";
import { ToastProvider } from "@/hooks/ToastContext";
import { queryClient } from "@/lib/queryClient";
import { buildTheme } from "@/theme";
import { canAccess, homePathFor } from "@/lib/roles";
import { AppLayout } from "@/components/AppLayout";
import { LoginPage } from "@/features/auth/LoginPage";

// Admin/portal pages are code-split per route so only the visited feature is loaded.
const MfaLoginPage = lazy(() => import("@/features/auth/MfaLoginPage").then((m) => ({ default: m.MfaLoginPage })));
const MfaEnrollPage = lazy(() => import("@/features/auth/MfaEnrollPage").then((m) => ({ default: m.MfaEnrollPage })));
const SetupWizardPage = lazy(() => import("@/features/wizard/SetupWizardPage").then((m) => ({ default: m.SetupWizardPage })));
const DashboardPage = lazy(() => import("@/features/dashboard/DashboardPage").then((m) => ({ default: m.DashboardPage })));
const UsersPage = lazy(() => import("@/features/users/UsersPage").then((m) => ({ default: m.UsersPage })));
const GroupsPage = lazy(() => import("@/features/groups/GroupsPage").then((m) => ({ default: m.GroupsPage })));
const CertsPage = lazy(() => import("@/features/certs/CertsPage").then((m) => ({ default: m.CertsPage })));
const AccessRulesPage = lazy(() => import("@/features/access-rules/AccessRulesPage").then((m) => ({ default: m.AccessRulesPage })));
const DnsOverridesPage = lazy(() => import("@/features/dns-overrides/DnsOverridesPage").then((m) => ({ default: m.DnsOverridesPage })));
const NodesPage = lazy(() => import("@/features/nodes/NodesPage").then((m) => ({ default: m.NodesPage })));
const ProfilesPage = lazy(() => import("@/features/profiles/ProfilesPage").then((m) => ({ default: m.ProfilesPage })));
const DaemonsPage = lazy(() => import("@/features/daemons/DaemonsPage").then((m) => ({ default: m.DaemonsPage })));
const StatusPage = lazy(() => import("@/features/dashboard/StatusPage").then((m) => ({ default: m.StatusPage })));
const SettingsPage = lazy(() => import("@/features/settings/SettingsPage").then((m) => ({ default: m.SettingsPage })));
const BrandingPage = lazy(() => import("@/features/branding/BrandingPage").then((m) => ({ default: m.BrandingPage })));
const ConfigReportPage = lazy(() => import("@/features/backup/ConfigReportPage").then((m) => ({ default: m.ConfigReportPage })));
const BackupsPage = lazy(() => import("@/features/backup/BackupsPage").then((m) => ({ default: m.BackupsPage })));
const MaintenancePage = lazy(() => import("@/features/backup/MaintenancePage").then((m) => ({ default: m.MaintenancePage })));
const AuditLogsPage = lazy(() => import("@/features/audit-log/AuditLogsPage").then((m) => ({ default: m.AuditLogsPage })));
const ApiTokensPage = lazy(() => import("@/features/api-tokens/ApiTokensPage").then((m) => ({ default: m.ApiTokensPage })));
const ConnectionLogsPage = lazy(() => import("@/features/connection-logs/ConnectionLogsPage").then((m) => ({ default: m.ConnectionLogsPage })));
const PortalPage = lazy(() => import("@/features/profiles/PortalPage").then((m) => ({ default: m.PortalPage })));
const AccountPage = lazy(() => import("@/features/profiles/AccountPage").then((m) => ({ default: m.AccountPage })));

const THEME_KEY = "passage.theme";

/** Dark mode is the default; the choice persists across reloads. */
function loadDarkMode(): boolean {
  try {
    return localStorage.getItem(THEME_KEY) !== "light";
  } catch {
    return true;
  }
}

export default function App() {
  return (
    <BrandProvider>
      <ThemedApp />
    </BrandProvider>
  );
}

/** App shell that derives the theme from the loaded brand color. */
function ThemedApp() {
  const [darkMode, setDarkMode] = useState<boolean>(loadDarkMode);
  const brand = useBrand();

  const toggleDarkMode = () => {
    setDarkMode((current) => {
      const next = !current;
      try {
        localStorage.setItem(THEME_KEY, next ? "dark" : "light");
      } catch {
        // Persistence is best-effort (e.g. private browsing).
      }
      return next;
    });
  };

  const theme = buildTheme(darkMode, brand.primaryColor);

  return (
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <ToastProvider>
            <BrowserRouter>
              <AuthGate>
                <Suspense fallback={<PageLoading />}>
                  <Routes>
                  <Route path="/login" element={<LoginPage />} />
                  <Route path="/login/mfa" element={<MfaLoginPage />} />
                  <Route path="/login/enroll" element={<MfaEnrollPage />} />
                  <Route path="/setup" element={<SetupWizardPage />} />
                  <Route element={<AppLayout darkMode={darkMode} onToggleDarkMode={toggleDarkMode} />}>
                    <Route path="/" element={<RoleRoute roles={["ADMIN"]}><DashboardPage /></RoleRoute>} />
                    <Route path="/users" element={<RoleRoute roles={["ADMIN", "GROUP_ADMIN"]}><UsersPage /></RoleRoute>} />
                    <Route
                      path="/groups"
                      element={<RoleRoute roles={["ADMIN", "GROUP_ADMIN"]}><GroupsPage /></RoleRoute>}
                    />
                    <Route
                      path="/certs"
                      element={<RoleRoute roles={["ADMIN"]}><CertsPage /></RoleRoute>}
                    />
                    <Route
                      path="/rules"
                      element={<RoleRoute roles={["ADMIN"]}><AccessRulesPage /></RoleRoute>}
                    />
                    <Route
                      path="/dns"
                      element={<RoleRoute roles={["ADMIN"]}><DnsOverridesPage /></RoleRoute>}
                    />
                    <Route
                      path="/nodes"
                      element={<RoleRoute roles={["ADMIN"]}><NodesPage /></RoleRoute>}
                    />
                    <Route
                      path="/profiles"
                      element={<RoleRoute roles={["ADMIN"]}><ProfilesPage /></RoleRoute>}
                    />
                    <Route
                      path="/daemons"
                      element={<RoleRoute roles={["ADMIN"]}><DaemonsPage /></RoleRoute>}
                    />
                    <Route
                      path="/portal"
                      element={<PortalPage />}
                    />
                    <Route
                      path="/portal/account"
                      element={<AccountPage />}
                    />
                    <Route
                      path="/status"
                      element={<RoleRoute roles={["ADMIN"]}><StatusPage /></RoleRoute>}
                    />
                    <Route
                      path="/connection-logs"
                      element={<RoleRoute roles={["ADMIN", "GROUP_ADMIN"]}><ConnectionLogsPage /></RoleRoute>}
                    />
                    <Route
                      path="/settings"
                      element={<RoleRoute roles={["ADMIN"]}><SettingsPage /></RoleRoute>}
                    />
                    <Route
                      path="/branding"
                      element={<RoleRoute roles={["ADMIN"]}><BrandingPage /></RoleRoute>}
                    />
                    <Route
                      path="/config-report"
                      element={<RoleRoute roles={["ADMIN"]}><ConfigReportPage /></RoleRoute>}
                    />
                    <Route
                      path="/backups"
                      element={<RoleRoute roles={["ADMIN"]}><BackupsPage /></RoleRoute>}
                    />
                    <Route
                      path="/maintenance"
                      element={<RoleRoute roles={["ADMIN"]}><MaintenancePage /></RoleRoute>}
                    />
                    <Route
                      path="/audit-logs"
                      element={<RoleRoute roles={["ADMIN"]}><AuditLogsPage /></RoleRoute>}
                    />
                    <Route
                      path="/api-tokens"
                      element={<RoleRoute roles={["ADMIN"]}><ApiTokensPage /></RoleRoute>}
                    />
                  </Route>
                  <Route path="*" element={<RedirectToHome />} />
                </Routes>
                </Suspense>
              </AuthGate>
            </BrowserRouter>
          </ToastProvider>
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );
}

/** Redirects between login and the authenticated app based on session state. */
function AuthGate({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <Box sx={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center" }}>
        <CircularProgress />
      </Box>
    );
  }

  if (user) {
    if (
      window.location.pathname === "/login" ||
      window.location.pathname === "/login/mfa" ||
      window.location.pathname === "/login/enroll"
    ) {
      return <Navigate to={homePathFor(user.role)} replace />;
    }
    return <>{children}</>;
  }

  // Not authenticated: allow the wizard and auth pages through.
  const path = window.location.pathname;
  if (
    path === "/login" ||
    path === "/login/mfa" ||
    path === "/login/enroll" ||
    path === "/setup"
  ) {
    return <>{children}</>;
  }
  return <Navigate to="/login" replace />;
}

/** Guards a route by role; redirects unauthorized users to their own home page. */
function RoleRoute({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const { user } = useAuth();
  if (!user) return <Navigate to="/login" replace />;
  if (!canAccess(roles, user.role)) return <Navigate to={homePathFor(user.role)} replace />;
  return <>{children}</>;
}

/** Catch-all: logged-in users go to their role home, guests to login. */
function RedirectToHome() {
  const { user } = useAuth();
  if (!user) return <Navigate to="/login" replace />;
  return <Navigate to={homePathFor(user.role)} replace />;
}

/** Suspense fallback for lazy-loaded routes. */
export function PageLoading() {
  return (
    <Box sx={{ display: "flex", justifyContent: "center", py: 8 }}>
      <CircularProgress />
    </Box>
  );
}
