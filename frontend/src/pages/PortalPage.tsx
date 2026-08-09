import { useQuery } from "@tanstack/react-query";
import { Box, Grid2, Typography } from "@mui/material";
import { api, endpoints, type ProfileType } from "@/lib/api";
import { ProfileCard } from "@/components/ProfileCard";
import { useAuth } from "@/hooks/useAuth";

interface ProfileTypeDto {
  type: ProfileType;
  label: string;
  locked: boolean;
}

const TYPE_HINTS: Record<ProfileType, string> = {
  USER_LOCKED: "Requires your username and password on every connection.",
  AUTO_LOGIN: "Connects without a password prompt (certificate-only).",
  SERVER_LOCKED: "Binds the profile to the configured server endpoint.",
  GENERIC: "No certificate; username and password only.",
};

export function PortalPage() {
  const { user } = useAuth();

  const { data: types } = useQuery<ProfileTypeDto[]>({
    queryKey: ["portal-profiles"],
    queryFn: () => api<ProfileTypeDto[]>(endpoints.portalProfiles),
  });

  return (
    <Box>
      <Typography variant="h5" fontWeight={700}>
        My connection profiles
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Signed in as {user?.username}. Download a profile and import it into OpenVPN Connect or the
        OpenVPN client of your choice.
      </Typography>
      <Grid2 container spacing={2}>
        {(types ?? []).map((t) => (
          <Grid2 size={{ xs: 12, sm: 6, md: 4 }} key={t.type}>
            <ProfileCard
              title={t.type.replaceAll("_", " ")}
              subtitle={TYPE_HINTS[t.type]}
              fetch={() => api(`/portal/profiles/${t.type}/download`)}
            />
          </Grid2>
        ))}
      </Grid2>
    </Box>
  );
}
