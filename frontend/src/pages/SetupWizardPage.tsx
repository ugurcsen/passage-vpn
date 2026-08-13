import { useState } from "react";
import {
  Box,
  Button,
  CircularProgress,
  FormControlLabel,
  MenuItem,
  Paper,
  Stack,
  Step,
  StepContent,
  StepLabel,
  Stepper,
  Switch,
  TextField,
  Typography,
} from "@mui/material";
import { useNavigate } from "react-router-dom";
import { api, endpoints } from "@/lib/api";
import { useToast } from "@/hooks/useToast";

const STEPS = [
  { label: "Admin account", description: "Create the administrator account." },
  { label: "VPN server", description: "Configure the OpenVPN daemon (port, protocol, network)." },
  { label: "PKI", description: "Initialize the certificate authority and server certificate." },
  { label: "Done", description: "The panel is ready to use." },
];

const DEFAULTS = {
  port: "1194",
  proto: "udp" as "udp" | "tcp" | "udp6" | "tcp6",
  subnet: "10.8.0.0",
  subnetMask: "255.255.255.0",
  dns: "1.1.1.1, 8.8.8.8",
  domain: "",
  fullTunnel: true,
  clientCertNotRequired: false,
  authUserPass: true,
  adminHost: "vpn.example.com",
  ipv6Enabled: false,
  ipv6Subnet: "fd00:1::/64",
};

/** First-run wizard driving the backend setup state machine (admin → server → pki → complete). */
export function SetupWizardPage() {
  const [activeStep, setActiveStep] = useState(0);
  const navigate = useNavigate();
  const { error: toastError, success: toastSuccess } = useToast();

  const [admin, setAdmin] = useState({ username: "admin", password: "" });
  const [server, setServer] = useState(DEFAULTS);
  const [pkiRunning, setPkiRunning] = useState(false);
  const [pkiDone, setPkiDone] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const runStep = async (step: string, payload: unknown) => {
    setSubmitting(true);
    try {
      await api(endpoints.setupWizard, {
        method: "POST",
        body: JSON.stringify({ step, payload }),
      });
      return true;
    } catch (err) {
      toastError(err instanceof Error ? err.message : "Setup step failed");
      return false;
    } finally {
      setSubmitting(false);
    }
  };

  const next = async () => {
    if (activeStep === 0) {
      if (admin.password.length < 8) {
        toastError("Password must be at least 8 characters");
        return;
      }
      if (await runStep("admin", admin)) setActiveStep((s) => s + 1);
      return;
    }
    if (activeStep === 1) {
      const port = Number(server.port);
      if (!Number.isInteger(port) || port < 1 || port > 65535) {
        toastError("Port must be between 1 and 65535");
        return;
      }
      if (!server.subnet.trim() || !server.subnetMask.trim()) {
        toastError("Subnet and subnet mask are required");
        return;
      }
      const payload = {
        daemonIndex: 0,
        port,
        proto: server.proto,
        subnet: server.subnet.trim(),
        subnetMask: server.subnetMask.trim(),
        dnsServers: server.dns
          .split(",")
          .map((d) => d.trim())
          .filter((d) => d.length > 0),
        domain: server.domain.trim() || null,
        extraRoutes: [],
        fullTunnel: server.fullTunnel,
        clientCertNotRequired: server.clientCertNotRequired,
        authUserPass: server.authUserPass,
        adminHost: server.adminHost.trim() || DEFAULTS.adminHost,
        ipv6Enabled: server.ipv6Enabled,
        ipv6Subnet: server.ipv6Enabled ? server.ipv6Subnet.trim() || null : null,
      };
      if (await runStep("server", payload)) setActiveStep((s) => s + 1);
      return;
    }
    if (activeStep === 2) {
      if (!pkiDone) return;
      setActiveStep((s) => s + 1);
      return;
    }
    if (activeStep === 3) {
      toastSuccess("Setup complete");
      navigate("/");
    }
  };

  const provisionPki = async () => {
    setPkiRunning(true);
    try {
      if (await runStep("pki", {})) {
        setPkiDone(true);
      }
    } finally {
      setPkiRunning(false);
    }
  };

  const update = <K extends keyof typeof DEFAULTS>(key: K, value: (typeof DEFAULTS)[K]) =>
    setServer((prev) => ({ ...prev, [key]: value }));

  return (
    <Box sx={{ maxWidth: 640, mx: "auto", mt: 6 }}>
      <Paper sx={{ p: 4 }}>
        <Typography variant="h5" fontWeight={700} sx={{ mb: 3 }}>
          Initial setup
        </Typography>
        <Stepper activeStep={activeStep} orientation="vertical">
          {STEPS.map((step, index) => (
            <Step key={step.label}>
              <StepLabel>{step.label}</StepLabel>
              <StepContent>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                  {step.description}
                </Typography>
                {index === 0 && (
                  <Stack spacing={2} sx={{ mb: 2 }}>
                    <TextField
                      label="Username"
                      value={admin.username}
                      onChange={(e) => setAdmin({ ...admin, username: e.target.value })}
                    />
                    <TextField
                      label="Password"
                      type="password"
                      value={admin.password}
                      onChange={(e) => setAdmin({ ...admin, password: e.target.value })}
                      helperText="Minimum 8 characters"
                    />
                  </Stack>
                )}
                {index === 1 && (
                  <Stack spacing={2} sx={{ mb: 2 }}>
                    <Box sx={{ display: "flex", gap: 2 }}>
                      <TextField
                        label="Port"
                        type="number"
                        value={server.port}
                        onChange={(e) => update("port", e.target.value)}
                        sx={{ width: 140 }}
                      />
                      <TextField
                        select
                        label="Protocol"
                        value={server.proto}
                        onChange={(e) =>
                          update("proto", e.target.value as "udp" | "tcp" | "udp6" | "tcp6")
                        }
                        sx={{ width: 140 }}
                      >
                        <MenuItem value="udp">UDP</MenuItem>
                        <MenuItem value="tcp">TCP</MenuItem>
                        <MenuItem value="udp6">UDP6</MenuItem>
                        <MenuItem value="tcp6">TCP6</MenuItem>
                      </TextField>
                    </Box>
                    <Box sx={{ display: "flex", gap: 2 }}>
                      <TextField
                        label="Subnet"
                        value={server.subnet}
                        onChange={(e) => update("subnet", e.target.value)}
                      />
                      <TextField
                        label="Subnet mask"
                        value={server.subnetMask}
                        onChange={(e) => update("subnetMask", e.target.value)}
                      />
                    </Box>
                    <FormControlLabel
                      control={
                        <Switch
                          checked={server.ipv6Enabled}
                          onChange={(e) => update("ipv6Enabled", e.target.checked)}
                        />
                      }
                      label="Enable IPv6 (dual-stack tunnel)"
                    />
                    {server.ipv6Enabled && (
                      <TextField
                        label="IPv6 subnet"
                        value={server.ipv6Subnet}
                        onChange={(e) => update("ipv6Subnet", e.target.value)}
                        helperText="Client subnet in CIDR form, e.g. fd00:1::/64"
                      />
                    )}
                    <TextField
                      label="DNS servers"
                      value={server.dns}
                      onChange={(e) => update("dns", e.target.value)}
                      helperText="Comma-separated list"
                    />
                    <TextField
                      label="Domain (optional)"
                      value={server.domain}
                      onChange={(e) => update("domain", e.target.value)}
                    />
                    <TextField
                      label="Admin host"
                      value={server.adminHost}
                      onChange={(e) => update("adminHost", e.target.value)}
                      helperText="Hostname/IP pushed to clients as VPN server address"
                    />
                    <FormControlLabel
                      control={
                        <Switch
                          checked={server.fullTunnel}
                          onChange={(e) => update("fullTunnel", e.target.checked)}
                        />
                      }
                      label="Full tunnel (redirect all client traffic)"
                    />
                    <FormControlLabel
                      control={
                        <Switch
                          checked={server.authUserPass}
                          onChange={(e) => update("authUserPass", e.target.checked)}
                        />
                      }
                      label="Require username/password in addition to certificates"
                    />
                    <FormControlLabel
                      control={
                        <Switch
                          checked={server.clientCertNotRequired}
                          onChange={(e) => update("clientCertNotRequired", e.target.checked)}
                        />
                      }
                      label="Allow connections without a client certificate"
                    />
                  </Stack>
                )}
                {index === 2 && (
                  <Box sx={{ mb: 2 }}>
                    {pkiDone ? (
                      <Typography color="success.main">Certificate authority initialized.</Typography>
                    ) : (
                      <Button variant="outlined" onClick={provisionPki} disabled={pkiRunning || submitting}>
                        {pkiRunning ? <CircularProgress size={20} sx={{ mr: 1 }} /> : null}
                        {pkiRunning ? "Provisioning PKI…" : "Provision PKI"}
                      </Button>
                    )}
                  </Box>
                )}
                <Box sx={{ mt: 2 }}>
                  <Button
                    variant="contained"
                    onClick={next}
                    disabled={submitting || (index === 2 && !pkiDone) || (index === 2 && pkiRunning)}
                  >
                    {index === STEPS.length - 1 ? "Finish" : "Continue"}
                  </Button>
                </Box>
              </StepContent>
            </Step>
          ))}
        </Stepper>
      </Paper>
    </Box>
  );
}
