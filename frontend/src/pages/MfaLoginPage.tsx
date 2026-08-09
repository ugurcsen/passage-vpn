import { useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Alert, Box, Button, CircularProgress, Paper, TextField, Typography } from "@mui/material";
import { useAuth } from "@/hooks/useAuth";
import { useToast } from "@/hooks/useToast";

/** Second step of login: TOTP verification code. */
export function MfaLoginPage() {
  const { submitMfa } = useAuth();
  const { error: toastError } = useToast();
  const navigate = useNavigate();
  const location = useLocation();

  const [code, setCode] = useState("");
  const [loading, setLoading] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    const preAuthToken = String(location.state?.preAuthToken ?? "");
    if (!preAuthToken) {
      toastError("Session expired; please sign in again");
      navigate("/login");
      return;
    }
    setLoading(true);
    try {
      await submitMfa(preAuthToken, code);
      navigate("/");
    } catch (err) {
      toastError(err instanceof Error ? err.message : "Verification failed");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Box sx={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center", p: 2 }}>
      <Paper sx={{ p: 4, width: "100%", maxWidth: 400 }}>
        <Typography variant="h5" fontWeight={700} sx={{ mb: 1 }}>
          Two-factor verification
        </Typography>
        <Alert severity="info" sx={{ mb: 2 }}>
          Enter the 6-digit code from your authenticator app for{" "}
          {String(location.state?.username ?? "")}.
        </Alert>
        <form onSubmit={onSubmit}>
          <TextField
            label="Verification code"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            fullWidth
            required
            autoFocus
            margin="normal"
            inputProps={{ inputMode: "numeric", maxLength: 6 }}
          />
          <Button type="submit" variant="contained" fullWidth size="large" disabled={loading} sx={{ mt: 3 }}>
            {loading ? <CircularProgress size={22} color="inherit" /> : "Verify"}
          </Button>
        </form>
      </Paper>
    </Box>
  );
}
