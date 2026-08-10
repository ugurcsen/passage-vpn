import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ToastProvider } from "@/hooks/useToast";
import { SetupWizardPage } from "@/pages/SetupWizardPage";

function renderPage() {
  return render(
    <ThemeProvider theme={darkTheme}>
      <ToastProvider>
        <MemoryRouter initialEntries={["/setup"]}>
          <SetupWizardPage />
        </MemoryRouter>
      </ToastProvider>
    </ThemeProvider>,
  );
}

function activeLabel(): string | null {
  return document.querySelector(".MuiStepLabel-label.Mui-active")?.textContent ?? null;
}

function findWizardCall(fetchMock: ReturnType<typeof vi.mocked<typeof fetch>>, step: string) {
  return fetchMock.mock.calls.find(([url, opts]) => {
    if (url !== "/api/setup/wizard") return false;
    const body = opts?.body ? JSON.parse(String(opts.body)) : null;
    return body?.step === step;
  });
}

describe("SetupWizardPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() =>
        Promise.resolve(
          new Response(JSON.stringify({ ok: true }), {
            status: 200,
            headers: { "Content-Type": "application/json" },
          }),
        ),
      ),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("shows the stepper with the admin step first", () => {
    renderPage();

    expect(screen.getByText("Initial setup")).toBeInTheDocument();
    expect(screen.getByText("Admin account")).toBeInTheDocument();
    expect(screen.getByText("VPN server")).toBeInTheDocument();
    expect(activeLabel()).toBe("Admin account");
  });

  it("rejects a short password without calling the backend", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText(/password/i), "short");
    await user.click(screen.getByRole("button", { name: /continue/i }));

    expect(await screen.findByText("Password must be at least 8 characters")).toBeInTheDocument();
    expect(vi.mocked(fetch)).not.toHaveBeenCalled();
  });

  it("posts the admin payload and advances to the VPN server step", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText(/password/i), "correct-horse");
    await user.click(screen.getByRole("button", { name: /continue/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const call = findWizardCall(fetchMock, "admin");
      expect(call).toBeDefined();
    });
    const [, opts] = findWizardCall(fetchMock, "admin")!;
    expect(JSON.parse(String(opts!.body))).toEqual({
      step: "admin",
      payload: { username: "admin", password: "correct-horse" },
    });

    await waitFor(() => {
      expect(activeLabel()).toBe("VPN server");
    });
    expect(screen.getByLabelText(/port/i)).toBeInTheDocument();
  });

  it("posts the server config with defaults and advances to the PKI step", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText(/password/i), "correct-horse");
    await user.click(screen.getByRole("button", { name: /continue/i }));
    await screen.findByLabelText(/port/i);

    await user.click(screen.getByRole("button", { name: /continue/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      const call = findWizardCall(fetchMock, "server");
      expect(call).toBeDefined();
    });
    const [, opts] = findWizardCall(fetchMock, "server")!;
    expect(JSON.parse(String(opts!.body))).toEqual({
      step: "server",
      payload: {
        daemonIndex: 0,
        port: 1194,
        proto: "udp",
        subnet: "10.8.0.0",
        subnetMask: "255.255.255.0",
        dnsServers: ["1.1.1.1", "8.8.8.8"],
        domain: null,
        extraRoutes: [],
        fullTunnel: true,
        clientCertNotRequired: false,
        authUserPass: true,
        adminHost: "vpn.example.com",
      },
    });

    await waitFor(() => {
      expect(activeLabel()).toBe("PKI");
    });
  });

  it("provisions the PKI then finishes setup", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText(/password/i), "correct-horse");
    await user.click(screen.getByRole("button", { name: /continue/i }));
    await screen.findByLabelText(/port/i);
    await user.click(screen.getByRole("button", { name: /continue/i }));

    const continueBtn = await screen.findByRole("button", { name: /continue/i });
    expect(continueBtn).toBeDisabled();

    await user.click(screen.getByRole("button", { name: /provision pki/i }));

    const fetchMock = vi.mocked(fetch);
    await waitFor(() => {
      expect(findWizardCall(fetchMock, "pki")).toBeDefined();
    });
    expect(await screen.findByText(/certificate authority initialized/i)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /continue/i }));
    await waitFor(() => {
      expect(activeLabel()).toBe("Done");
    });

    await user.click(screen.getByRole("button", { name: /finish/i }));
    expect(await screen.findByText("Setup complete")).toBeInTheDocument();
  });
});
