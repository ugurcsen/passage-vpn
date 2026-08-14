import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { SharePage } from "@/pages/SharePage";

const ovpn = { filename: "user-locked-alice.ovpn", content: "client\nremote vpn.example.com" };

function renderShare(token = "tok-abc") {
  return render(
    <MemoryRouter initialEntries={[`/share/${token}`]}>
      <Routes>
        <Route path="/share/:token" element={<SharePage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("SharePage", () => {
  beforeEach(() => {
    vi.stubGlobal("URL", {
      ...URL,
      createObjectURL: vi.fn().mockReturnValue("blob:stub"),
      revokeObjectURL: vi.fn(),
    });
    // A real anchor click on a blob URL makes jsdom attempt navigation and leak a
    // "Not implemented" timer into the next test; block it for deterministic tests.
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("downloads the profile the token resolves to", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify(ovpn), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    renderShare("tok-abc");

    await screen.findByText(/preparing your profile/i);
    const fetchMock = vi.mocked(fetch);
    await vi.waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/portal/share/tok-abc",
        expect.objectContaining({ headers: expect.anything() }),
      );
      expect(URL.createObjectURL).toHaveBeenCalled();
    });
  });

  it("shows an error message when the token is invalid", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: "Profile token has expired" }), {
          status: 409,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    renderShare("bad-token");

    expect(await screen.findByText("Profile token has expired")).toBeInTheDocument();
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });
});
