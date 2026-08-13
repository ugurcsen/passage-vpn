import { Box, CircularProgress, ThemeProvider } from "@mui/material";
import { useState } from "react";
import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { AuthProvider, useAuth } from "@/hooks/useAuth";
import { ToastProvider } from "@/hooks/useToast";
import { queryClient } from "@/lib/queryClient";
import { darkTheme, lightTheme } from "@/theme";
import { AppLayout } from "@/components/AppLayout";
import { LoginPage } from "@/pages/LoginPage";
import { MfaLoginPage } from "@/pages/MfaLoginPage";
import { SetupWizardPage } from "@/pages/SetupWizardPage";
import { DashboardPage } from "@/pages/DashboardPage";
import { UsersPage } from "@/pages/UsersPage";
import { GroupsPage } from "@/pages/GroupsPage";
import { CertsPage } from "@/pages/CertsPage";
import { AccessRulesPage } from "@/pages/AccessRulesPage";
import { ProfilesPage } from "@/pages/ProfilesPage";
import { DaemonsPage } from "@/pages/DaemonsPage";
import { StatusPage } from "@/pages/StatusPage";
import { SettingsPage } from "@/pages/SettingsPage";
import { AuditLogsPage } from "@/pages/AuditLogsPage";
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
  const [darkMode, setDarkMode] = useState<boolean>(loadDarkMode);

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

  const theme = darkMode ? darkTheme : lightTheme;

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
                  <Route path="/setup" element={<SetupWizardPage />} />
                  <Route path="/share/:token" element={<SharePage />} />
                  <Route element={<AppLayout darkMode={darkMode} onToggleDarkMode={toggleDarkMode} />}>
                    <Route path="/" element={<DashboardPage />} />
                    <Route path="/users" element={<UsersPage />} />
                    <Route path="/groups" element={<GroupsPage />} />
                    <Route
                      path="/certs"
                      element={<CertsPage />}
                    />
                    <Route
                      path="/rules"
                      element={<AccessRulesPage />}
                    />
                    <Route
                      path="/profiles"
                      element={<ProfilesPage />}
                    />
                    <Route
                      path="/daemons"
                      element={<DaemonsPage />}
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
                      element={<StatusPage />}
                    />
                    <Route
                      path="/settings"
                      element={<SettingsPage />}
                    />
                    <Route
                      path="/audit-logs"
                      element={<AuditLogsPage />}
                    />
                  </Route>
                  <Route path="*" element={<Navigate to="/" replace />} />
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
function AuthGate({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <Box sx={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center" }}>
        <CircularProgress />
      </Box>
    );
  }

  if (user) {
    if (window.location.pathname === "/login" || window.location.pathname === "/login/mfa") {
      return <Navigate to="/" replace />;
    }
    return <>{children}</>;
  }

  // Not authenticated: allow the wizard and auth pages through.
  const path = window.location.pathname;
  if (path === "/login" || path === "/login/mfa" || path === "/setup") {
    return <>{children}</>;
  }
  return <Navigate to="/login" replace />;
}

/** Suspense fallback for lazy-loaded routes. */
export function PageLoading() {
  return (
    <Box sx={{ display: "flex", justifyContent: "center", py: 8 }}>
      <CircularProgress />
    </Box>
  );
}
