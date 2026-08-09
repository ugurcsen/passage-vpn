import { useState } from "react";
import { Box, Button, Paper, Step, StepContent, StepLabel, Stepper, TextField, Typography } from "@mui/material";
import { useNavigate } from "react-router-dom";
import { api, endpoints } from "@/lib/api";
import { useToast } from "@/hooks/useToast";

const STEPS = [
  { label: "Admin account", description: "Create the administrator account." },
  { label: "VPN server", description: "Configure the OpenVPN daemon (port, protocol, network)." },
  { label: "PKI", description: "Initialize the certificate authority and server certificate." },
  { label: "Done", description: "The panel is ready to use." },
];

/** First-run wizard. Backend state machine consumed in Phase 1.5. */
export function SetupWizardPage() {
  const [activeStep, setActiveStep] = useState(0);
  const navigate = useNavigate();
  const { error: toastError, success: toastSuccess } = useToast();

  const [admin, setAdmin] = useState({ username: "admin", password: "" });

  const next = async () => {
    if (activeStep === 0) {
      if (admin.password.length < 8) {
        toastError("Password must be at least 8 characters");
        return;
      }
      try {
        await api(endpoints.setupWizard, {
          method: "POST",
          body: JSON.stringify({ step: "admin", payload: admin }),
        });
      } catch (err) {
        toastError(err instanceof Error ? err.message : "Setup step failed");
        return;
      }
    }
    if (activeStep === 3) {
      toastSuccess("Setup complete");
      navigate("/");
      return;
    }
    setActiveStep((s) => s + 1);
  };

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
                  <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
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
                  </Box>
                )}
                <Box sx={{ mt: 2 }}>
                  <Button variant="contained" onClick={next}>
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
