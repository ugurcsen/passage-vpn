import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { Box, Button, CircularProgress, Paper, TextField, Typography } from "@mui/material";
import LockOutlinedIcon from "@mui/icons-material/LockOutlined";
import { useAuth } from "@/hooks/useAuth";
import { useToast } from "@/hooks/useToast";

/** Login page. MFA step handled after password verification. */
export function LoginPage() {
  const { login } = useAuth();
  const { error: toastError } = useToast();
  const navigate = useNavigate();

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    try {
      const res = await login(username, password);
      if (res.mfaRequired) {
        navigate("/login/mfa", { state: { username, preAuthToken: res.preAuthToken } });
      } else {
        navigate("/");
      }
    } catch (err) {
      toastError(err instanceof Error ? err.message : "Login failed");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Box sx={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center", p: 2 }}>
      <Paper sx={{ p: 4, width: "100%", maxWidth: 400 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 3 }}>
          <LockOutlinedIcon color="primary" />
          <Typography variant="h5" fontWeight={700}>
            OpenVPN Panel
          </Typography>
        </Box>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
          Sign in to manage your VPN.
        </Typography>
        <form onSubmit={onSubmit}>
          <TextField
            label="Username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            fullWidth
            autoFocus
            required
            margin="normal"
            autoComplete="username"
          />
          <TextField
            label="Password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            fullWidth
            required
            margin="normal"
            autoComplete="current-password"
          />
          <Button type="submit" variant="contained" fullWidth size="large" disabled={loading} sx={{ mt: 3 }}>
            {loading ? <CircularProgress size={22} color="inherit" /> : "Sign in"}
          </Button>
        </form>
      </Paper>
    </Box>
  );
}
