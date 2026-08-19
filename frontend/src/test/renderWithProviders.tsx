import type { ReactNode } from "react";
import { render, type RenderResult } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, type MemoryRouterProps } from "react-router-dom";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { AuthProvider } from "@/hooks/useAuth";
import { ToastProvider } from "@/hooks/useToast";
import { BrandProvider } from "@/hooks/useBrand";

function testQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
}

export interface RenderOptions {
  /** MemoryRouter entries (enables routing). */
  initialEntries?: MemoryRouterProps["initialEntries"];
  /** Include AuthProvider wrapper. Default: false. */
  withAuth?: boolean;
  /** Include BrandProvider wrapper. Default: false. */
  withBrand?: boolean;
  /** Provide a custom QueryClient (otherwise a fresh one is created). */
  queryClient?: QueryClient;
}

function buildWrapper(opts: RenderOptions) {
  const qc = opts.queryClient ?? testQueryClient();
  return function Wrapper({ children }: { children: ReactNode }) {
    let tree = (
      <ThemeProvider theme={darkTheme}>
        <QueryClientProvider client={qc}>{children}</QueryClientProvider>
      </ThemeProvider>
    );
    if (opts.withBrand) {
      tree = <BrandProvider>{tree}</BrandProvider>;
    }
    if (opts.withAuth) {
      tree = <AuthProvider>{tree}</AuthProvider>;
    }
    tree = <ToastProvider>{tree}</ToastProvider>;
    if (opts.initialEntries) {
      tree = (
        <MemoryRouter initialEntries={opts.initialEntries}>{tree}</MemoryRouter>
      );
    }
    return tree;
  };
}

/**
 * Render a component with the standard PassageVPN provider stack.
 *
 * Usage:
 * ```tsx
 * renderWithProviders(<MyPage />, { withAuth: true, initialEntries: ["/users"] });
 * ```
 */
export function renderWithProviders(
  ui: ReactNode,
  opts: RenderOptions = {},
): RenderResult & { queryClient: QueryClient } {
  const qc = opts.queryClient ?? testQueryClient();
  const result = render(ui, { wrapper: buildWrapper({ ...opts, queryClient: qc }) });
  return { ...result, queryClient: qc };
}
