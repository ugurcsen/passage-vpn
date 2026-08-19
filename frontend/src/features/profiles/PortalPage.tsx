import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Alert, Box, Grid2, Typography } from "@mui/material";
import { api, endpoints, type PortalProfileType } from "@/lib/api";
import { withDaemon } from "@/lib/api";
import { ProfileCard } from "@/components/ProfileCard";
import { useAuth } from "@/hooks/useAuth";

const TYPE_HINTS: Record<PortalProfileType["type"], string> = {
  USER_LOCKED: "Requires your username and password on every connection.",
  AUTO_LOGIN: "Connects without a password prompt (certificate-only).",
  SERVER_LOCKED: "Binds the profile to the configured server endpoint.",
  GENERIC: "No certificate; username and password only.",
};

/** Encodes the share URL directly: a plain camera opens it and downloads the .ovpn, and OpenVPN
 *  Connect imports it by fetching the profile from that URL (same as Access Server QR codes). */
function qrPayload(token: string): string {
  return `${window.location.origin}/share/${token}`;
}

export function PortalPage() {
  const { user } = useAuth();
  const [selected, setSelected] = useState<Record<string, number | null>>({});

  const { data: types } = useQuery<PortalProfileType[]>({
    queryKey: ["portal-profiles"],
    queryFn: () => api<PortalProfileType[]>(endpoints.portalProfiles),
  });

  const visible = (types ?? []).filter((t) => t.allowed && t.available);
  const hidden = (types ?? []).length - visible.length > 0;

  return (
    <Box>
      <Typography variant="h5" fontWeight={700}>
        My connection profiles
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Signed in as {user?.username}. Download a profile and import it into OpenVPN Connect or the
        OpenVPN client of your choice.
      </Typography>
      {hidden && (
        <Alert severity="info" sx={{ mb: 3 }}>
          Some profile types are hidden because they are disabled by the administrator or no enabled
          server is available to serve them.
        </Alert>
      )}
      <Grid2 container spacing={2}>
        {visible.map((t) => {
          const daemonIndex = selected[t.type] ?? null;
          return (
            <Grid2 size={{ xs: 12, sm: 6, md: 4 }} key={t.type}>
              <ProfileCard
                title={t.type.replaceAll("_", " ")}
                subtitle={TYPE_HINTS[t.type]}
                daemonOptions={t.daemons}
                daemon={daemonIndex}
                onDaemonChange={(d) => setSelected((prev) => ({ ...prev, [t.type]: d }))}
                fetch={() => api(withDaemon(`/portal/profiles/${t.type}/download`, daemonIndex))}
                qrFetch={async () => {
                  const { token, expiresAt } = await api<{ token: string; expiresAt: string }>(
                    withDaemon(`/portal/profiles/${t.type}/qr`, daemonIndex),
                  );
                  return {
                    payload: qrPayload(token),
                    expiresAt: Date.parse(expiresAt),
                  };
                }}
              />
            </Grid2>
          );
        })}
      </Grid2>
    </Box>
  );
}
