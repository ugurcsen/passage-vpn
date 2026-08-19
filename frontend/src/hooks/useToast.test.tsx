import { describe, expect, it } from "vitest";
import { renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { useToast } from "@/hooks/useToast";
import { ToastProvider } from "@/hooks/ToastContext";

function wrap() {
  return ({ children }: { children: ReactNode }) => <ToastProvider>{children}</ToastProvider>;
}

describe("useToast", () => {
  it("returns toast methods from context", () => {
    const { result } = renderHook(() => useToast(), { wrapper: wrap() });
    expect(typeof result.current.toast).toBe("function");
    expect(typeof result.current.success).toBe("function");
    expect(typeof result.current.error).toBe("function");
    expect(typeof result.current.info).toBe("function");
    expect(typeof result.current.warning).toBe("function");
  });

  it("throws when used outside ToastProvider", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    expect(() => {
      renderHook(() => useToast());
    }).toThrow("useToast must be used within ToastProvider");
    spy.mockRestore();
  });
});
