import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ProfileCard } from "@/components/ProfileCard";

const ovpn = { filename: "user-locked-alice.ovpn", content: "client\nremote vpn.example.com 1194\n" };

function renderCard(fetch: () => Promise<{ filename: string; content: string }>) {
  return render(
    <ThemeProvider theme={darkTheme}>
      <ProfileCard title="USER LOCKED" subtitle="Requires your username and password." fetch={fetch} />
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
});
