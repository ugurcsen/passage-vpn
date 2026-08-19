import { useContext } from "react";
import { BrandContext } from "./BrandContext";

export type { BrandContextValue, defaultBrand } from "./BrandContext";

/** Reads the effective brand (defaults when no provider is mounted). */
export function useBrand() {
  return useContext(BrandContext).brand;
}

/** Reloads the effective brand from the public endpoint (e.g. after saving branding). */
export function useBrandRefresh(): () => Promise<void> {
  return useContext(BrandContext).refresh;
}
