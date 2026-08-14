import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ProfileCard, type QrData } from "@/components/ProfileCard";

const ovpn = { filename: "user-locked-alice.ovpn", content: "client\nremote vpn.example.com 1194\n" };

function renderCard(fetch: () => Promise<{ filename: string; content: string }>) {
  return render(
    <ThemeProvider theme={darkTheme}>
      <ProfileCard title="USER LOCKED" subtitle="Requires your username and password." fetch={fetch} />
    </ThemeProvider>,
  );
}

function renderCardWithQr(qrFetch: () => Promise<QrData>) {
  return render(
    <ThemeProvider theme={darkTheme}>
      <ProfileCard
        title="USER LOCKED"
        subtitle="Requires your username and password."
        fetch={() => Promise.resolve(ovpn)}
        qrFetch={qrFetch}
      />
    </ThemeProvider>,
  );
}

describe("ProfileCard", () => {
  beforeEach(() => {
    vi.stubGlobal("URL", {
      ...URL,
      createObjectURL: vi.fn().mockReturnValue("blob:stub"),
      revokeObjectURL: vi.fn(),
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("renders title, subtitle and actions", () => {
    renderCard(() => Promise.resolve(ovpn));
    expect(screen.getByText("USER LOCKED")).toBeInTheDocument();
    expect(screen.getByText("Requires your username and password.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /download/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /qr/i })).toBeInTheDocument();
  });

  it("downloads the profile file", async () => {
    const user = userEvent.setup();
    const fetch = vi.fn().mockResolvedValue(ovpn);
    renderCard(fetch);

    await user.click(screen.getByRole("button", { name: /download/i }));

    expect(fetch).toHaveBeenCalledTimes(1);
    expect(URL.createObjectURL).toHaveBeenCalledOnce();
  });

  it("shows a QR code with the profile content", async () => {
    const user = userEvent.setup();
    const fetch = vi.fn().mockResolvedValue(ovpn);
    const { container } = renderCard(fetch);

    await user.click(screen.getByRole("button", { name: /qr/i }));

    await screen.findByText("USER LOCKED");
    const svg = container.querySelector("svg");
    expect(svg).not.toBeNull();
  });

  it("shows a live expiry countdown for a short-lived share QR", async () => {
    const user = userEvent.setup();
    const qrFetch = vi.fn().mockResolvedValue({
      payload: "http://vpn.example.com/share/tok",
      expiresAt: Date.now() + 5 * 60_000,
    });
    const { container } = renderCardWithQr(qrFetch);

    await user.click(screen.getByRole("button", { name: /qr/i }));

    expect(await screen.findByText(/share link expires in \d:\d\d/i)).toBeInTheDocument();
    expect(container.querySelector("svg")).not.toBeNull();
  });

  it("marks the share QR expired and offers regeneration", async () => {
    const user = userEvent.setup();
    const qrFetch = vi
      .fn()
      .mockResolvedValueOnce({ payload: "http://vpn.example.com/share/tok", expiresAt: Date.now() - 1000 })
      .mockResolvedValueOnce({
        payload: "http://vpn.example.com/share/tok2",
        expiresAt: Date.now() + 5 * 60_000,
      });
    const { container } = renderCardWithQr(qrFetch);

    await user.click(screen.getByRole("button", { name: /qr/i }));

    expect(await screen.findByText(/share link expired/i)).toBeInTheDocument();
    expect(screen.queryByText(/share link expires in/i)).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /generate new code/i }));

    expect(await screen.findByText(/share link expires in \d:\d\d/i)).toBeInTheDocument();
    expect(container.querySelector("svg")).not.toBeNull();
    expect(qrFetch).toHaveBeenCalledTimes(2);
  });
});
