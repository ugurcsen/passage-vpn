import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/renderWithProviders";
import { json, resetFetchMock } from "@/test/helpers";
import { BrandingPage } from "@/pages/BrandingPage";

const brand = {
  name: "Acme VPN",
  primaryColor: "#f97316",
  footer: "Support: help@acme.com",
  logoUrl: null,
};

describe("BrandingPage", () => {
  beforeEach(() => {
    localStorage.clear();
    resetFetchMock();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(brand)));
  });

  afterEach(() => {
    resetFetchMock();
  });

  it("loads and shows the current brand", async () => {
    renderWithProviders(<BrandingPage />);
    const name = (await screen.findByLabelText("Brand name")) as HTMLInputElement;
    expect(name.value).toBe("Acme VPN");
    const color = screen.getByLabelText("Primary color") as HTMLInputElement;
    expect(color.value).toBe("#f97316");
    const footer = screen.getByLabelText("Footer text") as HTMLInputElement;
    expect(footer.value).toBe("Support: help@acme.com");
    expect(screen.getByText("Acme VPN")).toBeInTheDocument();
  });

  it("saves all four brand settings", async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "PUT" && String(input).includes("/admin/settings")) {
        return Promise.resolve(json({ brand_name: "New" }));
      }
      return Promise.resolve(json(brand));
    });
    vi.stubGlobal("fetch", fetchMock);
    renderWithProviders(<BrandingPage />);

    const name = await screen.findByLabelText("Brand name");
    await userEvent.clear(name);
    await userEvent.type(name, "New Name");
    await userEvent.click(screen.getByRole("button", { name: "Save branding" }));

    await waitFor(() => {
      const puts = fetchMock.mock.calls.filter((c) => c[1]?.method === "PUT");
      expect(puts.length).toBe(4);
      const names = puts.map((c) => String(c[0]));
      expect(names.some((n) => n.includes("/brand_name"))).toBe(true);
      expect(names.some((n) => n.includes("/brand_primary_color"))).toBe(true);
      expect(names.some((n) => n.includes("/brand_footer"))).toBe(true);
      expect(names.some((n) => n.includes("/brand_logo_url"))).toBe(true);
      const nameCall = puts.find((c) => String(c[0]).includes("/brand_name"));
      expect(String(nameCall?.[1]?.body)).toContain("New Name");
    });
    expect(await screen.findByText(/Branding saved/i)).toBeInTheDocument();
  });

  it("blocks saving an invalid hex color", async () => {
    renderWithProviders(<BrandingPage />);
    const color = await screen.findByLabelText("Primary color");
    await userEvent.clear(color);
    await userEvent.type(color, "orange");
    expect(screen.getByRole("button", { name: "Save branding" })).toBeDisabled();
  });
});
