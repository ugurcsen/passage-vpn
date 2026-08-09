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
import { PlaceholderPage } from "@/pages/PlaceholderPage";

export default function App() {
  const [darkMode, setDarkMode] = useState(true);
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
                  <Route element={<AppLayout darkMode={darkMode} onToggleDarkMode={() => setDarkMode((d) => !d)} />}>
                    <Route path="/" element={<DashboardPage />} />
                    <Route path="/users" element={<UsersPage />} />
                    <Route path="/groups" element={<GroupsPage />} />
                    <Route
                      path="/certs"
                      element={<PlaceholderPage title="Certificates" description="PKI management lands in Phase 3." />}
                    />
                    <Route
                      path="/profiles"
                      element={
                        <PlaceholderPage
                          title="Connection profiles"
                          description="Profile generation, QR codes and token URLs land in Phase 3."
                        />
                      }
                    />
                    <Route
                      path="/status"
                      element={
                        <PlaceholderPage
                          title="Live status"
                          description="Real-time monitoring lands in Phase 4."
                        />
                      }
                    />
                    <Route
                      path="/settings"
                      element={
                        <PlaceholderPage title="Settings" description="Server settings, branding and backup land in Phase 4." />
                      }
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
