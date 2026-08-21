import { createContext, useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { apiPublic, endpoints, type Brand } from "@/lib/api";

/** Fallback brand used before the public endpoint responds (and in tests). */
export const defaultBrand: Brand = {
  name: "PassageVPN",
  primaryColor: "#4f8cff",
  footer: "",
  logoUrl: null,
};

export interface BrandContextValue {
  brand: Brand;
  refresh: () => Promise<void>;
}

export const BrandContext = createContext<BrandContextValue>({
  brand: defaultBrand,
  refresh: async () => undefined,
});

/** Loads the effective brand on mount and exposes a refresh for after branding changes. */
export function BrandProvider({ children }: { children: ReactNode }) {
  const [brand, setBrand] = useState<Brand>(defaultBrand);

  const refresh = useCallback(async () => {
    try {
      const next = await apiPublic<Brand>(endpoints.publicBrand);
      setBrand(next);
    } catch {
      /* offline or unreachable; keep current brand */
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const value = useMemo<BrandContextValue>(() => ({ brand, refresh }), [brand, refresh]);

  return <BrandContext.Provider value={value}>{children}</BrandContext.Provider>;
}
