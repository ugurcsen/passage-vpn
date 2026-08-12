import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  InputAdornment,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import ContentCopyIcon from "@mui/icons-material/ContentCopy";
import { api, copyToClipboard, endpoints, type MfaSetup } from "@/lib/api";
import { useAuth } from "@/hooks/useAuth";
import { useToast } from "@/hooks/useToast";

/** Self-service account page: TOTP MFA management and password change (client portal). */
export function AccountPage() {
  const toast = useToast();
  const { user, refreshMe, logout } = useAuth();
  const navigate = useNavigate();

  // MFA setup flow: re-authenticate -> QR -> confirm code.
  const [setupOpen, setSetupOpen] = useState(false);
  const [setupPassword, setSetupPassword] = useState("");
  const [mfaSetup, setMfaSetup] = useState<MfaSetup | null>(null);
  const [mfaCode, setMfaCode] = useState("");
  // MFA disable flow: re-authenticate.
  const [disableOpen, setDisableOpen] = useState(false);
  const [disablePassword, setDisablePassword] = useState("");
  // Password change flow.
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");

  const openSetup = () => {
    setSetupPassword("");
    setMfaCode("");
    setMfaSetup(null);
    setSetupOpen(true);
  };

  const closeAll = () => {
    setSetupOpen(false);
    setDisableOpen(false);
    setMfaSetup(null);
    setSetupPassword("");
    setDisablePassword("");
    setMfaCode("");
  };

  const setupMutation = useMutation({
    mutationFn: () =>
      api<MfaSetup>(endpoints.portalAccountMfaSetup, {
        method: "POST",
        body: JSON.stringify({ currentPassword: setupPassword }),
      }),
    onSuccess: (data) => {
      setSetupOpen(false);
      setMfaSetup(data);
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "MFA setup failed"),
  });

  const enableMutation = useMutation({
    mutationFn: () =>
      api(endpoints.portalAccountMfaEnable, {
        method: "POST",
        body: JSON.stringify({ code: mfaCode }),
      }),
    onSuccess: async () => {
      toast.success("Two-factor authentication enabled");
      setMfaSetup(null);
      setMfaCode("");
      await refreshMe();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Enable failed"),
  });

  const disableMutation = useMutation({
    mutationFn: () =>
      api(endpoints.portalAccountMfaDisable, {
        method: "POST",
        body: JSON.stringify({ currentPassword: disablePassword }),
      }),
    onSuccess: async () => {
      toast.success("Two-factor authentication disabled");
      setDisableOpen(false);
      setDisablePassword("");
      await refreshMe();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Disable failed"),
  });

  const passwordMutation = useMutation({
    mutationFn: () =>
      api(endpoints.portalAccountPassword, {
        method: "POST",
        body: JSON.stringify({ currentPassword, newPassword }),
      }),
    onSuccess: async () => {
      toast.success("Password changed; please sign in again");
      await logout();
      navigate("/login");
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Password change failed"),
  });

  const copySecret = async () => {
    if (!mfaSetup) return;
    const ok = await copyToClipboard(mfaSetup.secret);
    if (ok) toast.success("Secret copied");
    else toast.error("Copy failed");
  };

  const passwordsMatch = newPassword.length > 0 && newPassword === confirmPassword;
  const passwordValid = newPassword.length >= 8 && passwordsMatch;

  return (
    <Box sx={{ maxWidth: 640 }}>
      <Typography variant="h5" fontWeight={700} sx={{ mb: 1 }}>
        My account
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Signed in as {user?.username}. Manage your two-factor authentication and password.
      </Typography>

      <Paper sx={{ p: 3, mb: 3 }}>
        <Typography variant="subtitle1" fontWeight={600} sx={{ mb: 1 }}>
          Two-factor authentication (MFA)
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Add a one-time code from an authenticator app (Google Authenticator compatible) on top of
          your password when signing in.
        </Typography>
        {user?.mfaEnabled ? (
          <Alert severity="success" sx={{ mb: 2 }}>
            Two-factor authentication is enabled.
          </Alert>
        ) : (
          <Alert severity="info" sx={{ mb: 2 }}>
            Two-factor authentication is disabled.
          </Alert>
        )}
        {user?.mfaEnabled ? (
          <Button variant="outlined" color="error" onClick={() => setDisableOpen(true)}>
            Disable MFA
          </Button>
        ) : (
          <Button variant="contained" onClick={openSetup}>
            Set up MFA
          </Button>
        )}
      </Paper>

      <Paper sx={{ p: 3 }}>
        <Typography variant="subtitle1" fontWeight={600} sx={{ mb: 1 }}>
          Change password
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          You will be signed out everywhere after changing your password.
        </Typography>
        <Stack spacing={2}>
          <TextField
            label="Current password"
            type="password"
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
            fullWidth
          />
          <TextField
            label="New password"
            type="password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            fullWidth
            helperText="At least 8 characters."
          />
          <TextField
            label="Confirm new password"
            type="password"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
            fullWidth
            error={confirmPassword.length > 0 && newPassword !== confirmPassword}
            helperText={
              confirmPassword.length > 0 && newPassword !== confirmPassword
                ? "Passwords do not match."
                : " "
            }
          />
          <Box>
            <Button
              variant="contained"
              disabled={!currentPassword || !passwordValid || passwordMutation.isPending}
              onClick={() => passwordMutation.mutate()}
            >
              {passwordMutation.isPending ? <CircularProgress size={18} /> : "Change password"}
            </Button>
          </Box>
        </Stack>
      </Paper>

      {/* MFA setup: re-authenticate with the current password first. */}
      <Dialog open={setupOpen} onClose={closeAll} maxWidth="xs" fullWidth>
        <DialogTitle>Set up MFA</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 2 }}>
            Confirm your password to begin. Then scan the QR code with your authenticator app.
          </Typography>
          <TextField
            label="Current password"
            type="password"
            value={setupPassword}
            onChange={(e) => setSetupPassword(e.target.value)}
            fullWidth
            autoFocus
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={closeAll}>Cancel</Button>
          <Button
            variant="contained"
            disabled={setupPassword.length < 8 || setupMutation.isPending}
            onClick={() => setupMutation.mutate()}
          >
            {setupMutation.isPending ? <CircularProgress size={18} /> : "Continue"}
          </Button>
        </DialogActions>
      </Dialog>

      {/* MFA setup: show QR + code once the secret exists. */}
      <Dialog open={!!mfaSetup} onClose={closeAll} maxWidth="sm" fullWidth>
        <DialogTitle>Set up MFA</DialogTitle>
        <DialogContent>
          <Stack spacing={2}>
            <Alert severity="info">
              Scan the QR code with Google Authenticator (or any TOTP app), then enter the 6-digit
              code to enable two-factor authentication.
            </Alert>
            <Box sx={{ display: "flex", justifyContent: "center" }}>
              <img src={mfaSetup?.qrDataUrl} alt="TOTP QR code" width={180} height={180} />
            </Box>
            <TextField
              label="Secret"
              value={mfaSetup?.secret ?? ""}
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
              label="Verification code"
              value={mfaCode}
              onChange={(e) => setMfaCode(e.target.value)}
              fullWidth
              inputProps={{ inputMode: "numeric", maxLength: 6 }}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={closeAll}>Cancel</Button>
          <Button
            variant="contained"
            disabled={mfaCode.length < 6 || enableMutation.isPending}
            onClick={() => enableMutation.mutate()}
          >
            {enableMutation.isPending ? <CircularProgress size={18} /> : "Enable"}
          </Button>
        </DialogActions>
      </Dialog>

      {/* MFA disable: re-authenticate with the current password. */}
      <Dialog open={disableOpen} onClose={closeAll} maxWidth="xs" fullWidth>
        <DialogTitle>Disable MFA</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 2 }}>
            Confirm your password to disable two-factor authentication.
          </Typography>
          <TextField
            label="Current password"
            type="password"
            value={disablePassword}
            onChange={(e) => setDisablePassword(e.target.value)}
            fullWidth
            autoFocus
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={closeAll}>Cancel</Button>
          <Button
            variant="contained"
            color="error"
            disabled={disablePassword.length < 8 || disableMutation.isPending}
            onClick={() => disableMutation.mutate()}
          >
            {disableMutation.isPending ? <CircularProgress size={18} /> : "Disable MFA"}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
