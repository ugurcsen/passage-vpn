import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { useBrand } from "@/hooks/useBrand";
import { BrandProvider, defaultBrand } from "@/hooks/BrandContext";

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function wrap() {
  return ({ children }: { children: ReactNode }) => <BrandProvider>{children}</BrandProvider>;
}

describe("useBrand", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ name: "CustomBrand", primaryColor: "#ff0000", footer: "", logoUrl: null })));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("returns default brand before fetch resolves", () => {
    const { result } = renderHook(() => useBrand(), { wrapper: wrap() });
    expect(result.current.name).toBe(defaultBrand.name);
  });

  it("returns fetched brand after provider loads", async () => {
    const { result } = renderHook(() => useBrand(), { wrapper: wrap() });
    const { waitFor } = await import("@testing-library/react");
    await waitFor(() => expect(result.current.name).toBe("CustomBrand"));
    expect(result.current.primaryColor).toBe("#ff0000");
  });

  it("returns default brand when used outside provider", () => {
    const { result } = renderHook(() => useBrand());
    expect(result.current).toEqual(defaultBrand);
  });
});
