import { useQuery } from "@tanstack/react-query";
import { Alert, Box, Grid2, Typography } from "@mui/material";
import { api, endpoints, type ProfileType } from "@/lib/api";
import { ProfileCard } from "@/components/ProfileCard";
import { useAuth } from "@/hooks/useAuth";

interface ProfileTypeDto {
  type: ProfileType;
  label: string;
  locked: boolean;
  /** True when the admin allows this type for self-service downloads. */
  allowed: boolean;
  /** True when an enabled daemon serves this profile type. */
  available: boolean;
}

const TYPE_HINTS: Record<ProfileType, string> = {
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

  const { data: types } = useQuery<ProfileTypeDto[]>({
    queryKey: ["portal-profiles"],
    queryFn: () => api<ProfileTypeDto[]>(endpoints.portalProfiles),
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
        {visible.map((t) => (
          <Grid2 size={{ xs: 12, sm: 6, md: 4 }} key={t.type}>
            <ProfileCard
              title={t.type.replaceAll("_", " ")}
              subtitle={TYPE_HINTS[t.type]}
              fetch={() => api(`/portal/profiles/${t.type}/download`)}
              qrFetch={async () => {
                const { token, expiresAt } = await api<{ token: string; expiresAt: string }>(
                  `/portal/profiles/${t.type}/qr`,
                );
                return {
                  payload: qrPayload(token),
                  expiresAt: Date.parse(expiresAt),
                };
              }}
            />
          </Grid2>
        ))}
      </Grid2>
    </Box>
  );
}
