import { Box, CircularProgress, ThemeProvider } from "@mui/material";
import { useState, type ReactNode } from "react";
import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { AuthProvider, useAuth, type Role } from "@/hooks/useAuth";
import { BrandProvider, useBrand } from "@/hooks/useBrand";
import { ToastProvider } from "@/hooks/useToast";
import { queryClient } from "@/lib/queryClient";
import { buildTheme } from "@/theme";
import { canAccess, homePathFor } from "@/lib/roles";
import { AppLayout } from "@/components/AppLayout";
import { LoginPage } from "@/pages/LoginPage";
import { MfaLoginPage } from "@/pages/MfaLoginPage";
import { MfaEnrollPage } from "@/pages/MfaEnrollPage";
import { SetupWizardPage } from "@/pages/SetupWizardPage";
import { DashboardPage } from "@/pages/DashboardPage";
import { UsersPage } from "@/pages/UsersPage";
import { GroupsPage } from "@/pages/GroupsPage";
import { CertsPage } from "@/pages/CertsPage";
import { AccessRulesPage } from "@/pages/AccessRulesPage";
import { DnsOverridesPage } from "@/pages/DnsOverridesPage";
import { NodesPage } from "@/pages/NodesPage";
import { ProfilesPage } from "@/pages/ProfilesPage";
import { DaemonsPage } from "@/pages/DaemonsPage";
import { StatusPage } from "@/pages/StatusPage";
import { SettingsPage } from "@/pages/SettingsPage";
import { BrandingPage } from "@/pages/BrandingPage";
import { ConfigReportPage } from "@/pages/ConfigReportPage";
import { BackupsPage } from "@/pages/BackupsPage";
import { MaintenancePage } from "@/pages/MaintenancePage";
import { AuditLogsPage } from "@/pages/AuditLogsPage";
import { ApiTokensPage } from "@/pages/ApiTokensPage";
import { PortalPage } from "@/pages/PortalPage";
import { AccountPage } from "@/pages/AccountPage";
import { SharePage } from "@/pages/SharePage";

const THEME_KEY = "opnl.theme";

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
                <Routes>
                  <Route path="/login" element={<LoginPage />} />
                  <Route path="/login/mfa" element={<MfaLoginPage />} />
                  <Route path="/login/enroll" element={<MfaEnrollPage />} />
                  <Route path="/setup" element={<SetupWizardPage />} />
                  <Route path="/share/:token" element={<SharePage />} />
                  <Route element={<AppLayout darkMode={darkMode} onToggleDarkMode={toggleDarkMode} />}>
                    <Route path="/" element={<RoleRoute roles={["ADMIN"]}><DashboardPage /></RoleRoute>} />
                    <Route path="/users" element={<RoleRoute roles={["ADMIN", "RESELLER"]}><UsersPage /></RoleRoute>} />
                    <Route
                      path="/groups"
                      element={<RoleRoute roles={["ADMIN"]}><GroupsPage /></RoleRoute>}
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
