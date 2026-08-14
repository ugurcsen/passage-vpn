import { useEffect, useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  IconButton,
  InputAdornment,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import ContentCopyIcon from "@mui/icons-material/ContentCopy";
import { apiPublic, copyToClipboard, endpoints, type MfaSetup } from "@/lib/api";
import { useAuth } from "@/hooks/useAuth";
import { useToast } from "@/hooks/useToast";

/** Forced MFA enrollment shown after login when the account has no TOTP yet. */
export function MfaEnrollPage() {
  const { submitMfaEnroll } = useAuth();
  const { error: toastError, success: toastSuccess } = useToast();
  const navigate = useNavigate();
  const location = useLocation();

  const preAuthToken = String(location.state?.preAuthToken ?? "");
  const username = String(location.state?.username ?? "");

  const [setup, setSetup] = useState<MfaSetup | null>(null);
  const [code, setCode] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!preAuthToken) {
      toastError("Session expired; please sign in again");
      navigate("/login");
      return;
    }
    let cancelled = false;
    apiPublic<MfaSetup>(endpoints.mfaEnroll, {
      method: "POST",
      body: JSON.stringify({ preAuthToken }),
    })
      .then((data) => {
        if (!cancelled) setSetup(data);
      })
      .catch((err) => {
        toastError(err instanceof Error ? err.message : "Enrollment failed");
        navigate("/login");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [preAuthToken, navigate, toastError]);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    try {
      await submitMfaEnroll(preAuthToken, code);
      navigate("/");
    } catch (err) {
      toastError(err instanceof Error ? err.message : "Verification failed");
    } finally {
      setLoading(false);
    }
  };

  const copySecret = async () => {
    if (!setup) return;
    const ok = await copyToClipboard(setup.secret);
    if (ok) toastSuccess("Secret copied");
    else toastError("Copy failed");
  };

  return (
    <Box sx={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center", p: 2 }}>
      <Paper sx={{ p: 4, width: "100%", maxWidth: 440 }}>
        <Typography variant="h5" fontWeight={700} sx={{ mb: 1 }}>
          Set up two-factor authentication
        </Typography>
        {loading && !setup ? (
          <Box sx={{ display: "flex", justifyContent: "center", py: 6 }}>
            <CircularProgress />
          </Box>
        ) : (
          <form onSubmit={onSubmit}>
            <Stack spacing={2}>
              <Alert severity="info">
                Two-factor authentication is required for {username || "your account"}. Scan the QR
                code with an authenticator app (Google Authenticator compatible), then enter the
                6-digit code to continue.
              </Alert>
              <Box sx={{ display: "flex", justifyContent: "center" }}>
                <img src={setup?.qrDataUrl} alt="TOTP QR code" width={180} height={180} />
              </Box>
              <TextField
                label="Secret"
                value={setup?.secret ?? ""}
                fullWidth
                slotProps={{
                  input: {
                    readOnly: true,
                    endAdornment: (
                      <InputAdornment position="end">
                        <Tooltip title="Copy secret">
                          <IconButton size="small" onClick={copySecret}>
                            <ContentCopyIcon fontSize="small" />
                          </IconButton>
                        </Tooltip>
                      </InputAdornment>
                    ),
                  },
                }}
              />
              <TextField
                id="code"
                name="code"
                label="Verification code"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                fullWidth
                required
                autoFocus
                inputProps={{ inputMode: "numeric", maxLength: 6 }}
              />
              <Button type="submit" variant="contained" fullWidth size="large" disabled={loading || code.length < 6}>
                {loading ? <CircularProgress size={22} color="inherit" /> : "Enable and sign in"}
              </Button>
            </Stack>
          </form>
        )}
      </Paper>
    </Box>
  );
}
