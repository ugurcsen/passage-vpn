import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Box,
  Button,
  Paper,
  Skeleton,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import LockOutlinedIcon from "@mui/icons-material/LockOutlined";
import { api, apiPublic, endpoints, type Brand, type ServerSettings } from "@/lib/api";
import { useBrandRefresh } from "@/hooks/useBrand";
import { useToast } from "@/hooks/useToast";

const HEX_COLOR = /^#[0-9a-fA-F]{6}$/;
const BRAND_KEYS = ["brand_name", "brand_primary_color", "brand_footer", "brand_logo_url"] as const;

/** Branding page: edits the four brand settings and previews the login look. */
export function BrandingPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const refreshBrand = useBrandRefresh();

  const { data, isLoading, error } = useQuery<Brand>({
    queryKey: ["public-brand"],
    queryFn: () => apiPublic<Brand>(endpoints.publicBrand),
  });

  const [name, setName] = useState("");
  const [primaryColor, setPrimaryColor] = useState("#4f8cff");
  const [footer, setFooter] = useState("");
  const [logoUrl, setLogoUrl] = useState("");

  useEffect(() => {
    if (data) {
      setName(data.name);
      setPrimaryColor(data.primaryColor);
      setFooter(data.footer ?? "");
      setLogoUrl(data.logoUrl ?? "");
    }
  }, [data]);

  const save = useMutation({
    mutationFn: async () => {
      let updated: ServerSettings = {};
      for (const key of BRAND_KEYS) {
        const value: string = key === "brand_name" ? name
          : key === "brand_primary_color" ? primaryColor
          : key === "brand_footer" ? footer
          : logoUrl;
        updated = await api<ServerSettings>(`${endpoints.settings}/${key}`, {
          method: "PUT",
          body: JSON.stringify({ value }),
        });
      }
      return updated;
    },
    onSuccess: async () => {
      queryClient.setQueryData(["public-brand"], { name, primaryColor, footer, logoUrl: logoUrl || null });
      await refreshBrand();
      toast.success("Branding saved");
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Save failed"),
  });

  const colorInvalid = primaryColor.trim() !== "" && !HEX_COLOR.test(primaryColor.trim());

  return (
    <Box>
      <Typography variant="h5" fontWeight={700} sx={{ mb: 1 }}>
        Branding
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Customize the product name, colors, logo and footer shown across the panel.
      </Typography>
      {error && (
        <Alert severity="error" sx={{ mb: 3 }}>
          {(error as Error).message}
        </Alert>
      )}

      <Stack direction={{ xs: "column", lg: "row" }} spacing={3}>
        <Paper sx={{ p: 3, flex: 1, minWidth: 0 }}>
          {isLoading ? (
            <Skeleton height={300} />
          ) : (
            <Stack spacing={2}>
              <TextField
                id="brand-name"
                label="Brand name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                fullWidth
              />
              <TextField
                id="brand-primary-color"
                label="Primary color"
                value={primaryColor}
                onChange={(e) => setPrimaryColor(e.target.value)}
                fullWidth
                error={colorInvalid}
                helperText={colorInvalid ? "Must be a hex color like #4f8cff" : "Hex color used for buttons and highlights"}
                InputProps={{
                  startAdornment: (
                    <Box component="label" sx={{ mr: 1, display: "flex", alignItems: "center", cursor: "pointer" }}>
                      <input
                        type="color"
                        aria-label="Pick primary color"
                        value={HEX_COLOR.test(primaryColor) ? primaryColor : "#4f8cff"}
                        onChange={(e) => setPrimaryColor(e.target.value)}
                        style={{ width: 36, height: 28, border: "none", background: "none", padding: 0 }}
                      />
                    </Box>
                  ),
                }}
              />
              <TextField
                id="brand-logo-url"
                label="Logo URL"
                value={logoUrl}
                onChange={(e) => setLogoUrl(e.target.value)}
                fullWidth
                placeholder="https://…/logo.png (optional)"
              />
              <TextField
                id="brand-footer"
                label="Footer text"
                value={footer}
                onChange={(e) => setFooter(e.target.value)}
                fullWidth
                placeholder="Support: help@example.com (optional)"
              />
              <Box>
                <Button
                  variant="contained"
                  disabled={colorInvalid || save.isPending}
                  onClick={() => save.mutate()}
                >
                  Save branding
                </Button>
              </Box>
            </Stack>
          )}
        </Paper>

        <Paper sx={{ p: 3, width: { xs: "100%", lg: 340 }, flexShrink: 0 }}>
          <Typography variant="h6" fontWeight={600} sx={{ mb: 2 }}>
            Preview
          </Typography>
          <Stack spacing={1} alignItems="center">
            {logoUrl ? (
              <Box component="img" src={logoUrl} alt="Logo" sx={{ height: 36, width: 36, objectFit: "contain" }} />
            ) : (
              <LockOutlinedIcon sx={{ fontSize: 36, color: "primary.main" }} />
            )}
            <Typography variant="h5" fontWeight={700} color="primary">
              {name || "Brand name"}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Sign in to manage your VPN.
            </Typography>
            <Button variant="contained" disabled>
              Sign in
            </Button>
            {footer && (
              <Typography variant="caption" color="text.secondary" sx={{ mt: 1 }}>
                {footer}
              </Typography>
            )}
          </Stack>
        </Paper>
      </Stack>
    </Box>
  );
}
