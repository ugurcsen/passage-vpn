import { useState } from "react";
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
  Stack,
  TextField,
  Tooltip,
} from "@mui/material";
import ContentCopyIcon from "@mui/icons-material/ContentCopy";
import type { MfaSetup } from "@/lib/api";
import { api, copyToClipboard, endpoints } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import type { UserRow } from "./types";

export interface MfaDialogProps {
  target: UserRow | null;
  onClose: () => void;
  onSaved: () => void;
}

export function MfaDialog({
  target,
  onClose,
  onSaved,
}: MfaDialogProps) {
  const toast = useToast();
  const [mfaSetup, setMfaSetup] = useState<MfaSetup | null>(null);
  const [mfaCode, setMfaCode] = useState("");
  const [mfaDisableConfirm, setMfaDisableConfirm] = useState(false);
  const [setupPending, setSetupPending] = useState(false);
  const [enablePending, setEnablePending] = useState(false);
  const [disablePending, setDisablePending] = useState(false);

  const closeAndReset = () => {
    setMfaSetup(null);
    setMfaCode("");
    setMfaDisableConfirm(false);
    onClose();
  };

  const handleSetup = async () => {
    if (!target) return;
    setSetupPending(true);
    try {
      const data = await api<MfaSetup>(endpoints.users + `/${target.id}/mfa/setup`, { method: "POST" });
      setMfaSetup(data);
      setMfaCode("");
    } catch {
      toast.error("MFA setup failed");
    } finally {
      setSetupPending(false);
    }
  };

  const handleEnable = async () => {
    if (!target || mfaCode.length < 6) return;
    setEnablePending(true);
    try {
      await api(endpoints.users + `/${target.id}/mfa/enable`, {
          method: "POST",
          body: JSON.stringify({ code: mfaCode }),
        });
      toast.success("MFA enabled");
      closeAndReset();
      onSaved();
    } catch {
      toast.error("Enable failed");
    } finally {
      setEnablePending(false);
    }
  };

  const handleDisable = async () => {
    if (!target) return;
    setDisablePending(true);
    try {
      await api(endpoints.users + `/${target.id}/mfa/disable`, { method: "POST" });
      toast.success("MFA disabled");
      closeAndReset();
      onSaved();
    } catch {
      toast.error("Disable failed");
    } finally {
      setDisablePending(false);
    }
  };

  return (
    <Dialog open={!!target} onClose={closeAndReset} maxWidth="sm" fullWidth>
      <DialogTitle>Manage MFA \u2014 {target?.username}</DialogTitle>
      <DialogContent>
        {target?.mfaEnabled && !mfaSetup ? (
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Alert severity="success">Two-factor authentication is enabled.</Alert>
            {target.mfaRequired && (
              <Alert severity="warning">
                MFA is required by policy; it cannot be disabled for this user while the setting is
                active.
              </Alert>
            )}
            {mfaDisableConfirm ? (
              <>
                <Alert severity="warning">
                  Disable MFA for {target.username}? They will no longer be asked for a code at
                  sign-in.
                </Alert>
                <Button
                  color="error"
                  variant="contained"
                  disabled={disablePending}
                  onClick={handleDisable}
                >
                  {disablePending ? <CircularProgress size={18} /> : "Disable MFA"}
                </Button>
              </>
            ) : (
              <Button
                color="error"
                variant="outlined"
                disabled={target.mfaRequired}
                onClick={() => setMfaDisableConfirm(true)}
              >
                Disable MFA
              </Button>
            )}
          </Stack>
        ) : mfaSetup ? (
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Alert severity="info">
              Scan the QR code with Google Authenticator (or any TOTP app), then enter the 6-digit
              code to enable two-factor authentication.
            </Alert>
            <Box sx={{ display: "flex", justifyContent: "center" }}>
              <img src={mfaSetup.qrDataUrl} alt="TOTP QR code" width={180} height={180} />
            </Box>
            <TextField
              label="Secret"
              value={mfaSetup.secret}
              fullWidth
              slotProps={{
                input: {
                  readOnly: true,
                  endAdornment: (
                    <InputAdornment position="end">
                      <Tooltip title="Copy secret">
                        <IconButton
                          size="small"
                          onClick={async () => {
                            const ok = await copyToClipboard(mfaSetup.secret);
                            if (ok) alert("Secret copied");
                          }}
                        >
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
              helperText="Confirm the code to finish enabling MFA."
            />
          </Stack>
        ) : (
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Alert severity="info">
              Two-factor authentication is disabled for this user. Set it up to require a code from
              an authenticator app at sign-in.
            </Alert>
            <Button variant="contained" disabled={setupPending} onClick={handleSetup}>
              {setupPending ? <CircularProgress size={18} /> : "Set up MFA"}
            </Button>
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        {mfaSetup ? (
          <>
            <Button onClick={() => setMfaSetup(null)}>Back</Button>
            <Button
              variant="contained"
              disabled={mfaCode.length < 6 || enablePending}
              onClick={handleEnable}
            >
              {enablePending ? <CircularProgress size={18} /> : "Enable"}
            </Button>
          </>
        ) : (
          <Button onClick={closeAndReset}>Close</Button>
        )}
      </DialogActions>
    </Dialog>
  );
}
