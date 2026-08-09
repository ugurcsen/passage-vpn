import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { Box, CircularProgress, Paper, Typography } from "@mui/material";
import { apiPublic, downloadOvpn, type OvpnFile } from "@/lib/api";

/** Public share-link landing: downloads the .ovpn the token resolves to. */
export function SharePage() {
  const { token } = useParams<{ token: string }>();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) return;
    apiPublic<OvpnFile>(`/portal/share/${token}`)
      .then((file) => {
        downloadOvpn(file);
        setError(null);
      })
      .catch((err) => setError(err instanceof Error ? err.message : "Invalid or expired link"));
  }, [token]);

  return (
    <Box sx={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center", p: 3 }}>
      <Paper sx={{ p: 4, textAlign: "center", maxWidth: 420 }}>
        {error ? (
          <Typography color="error">{error}</Typography>
        ) : (
          <>
            <CircularProgress size={28} sx={{ mb: 2 }} />
            <Typography>Preparing your profile…</Typography>
          </>
        )}
      </Paper>
    </Box>
  );
}
