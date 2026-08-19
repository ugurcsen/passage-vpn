import { useEffect, useState } from "react";
import {
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  IconButton,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import ClearIcon from "@mui/icons-material/Clear";
import TuneIcon from "@mui/icons-material/Tune";
import { api, endpoints } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import type { UserRow } from "./types";

export interface CcdSettingsDialogProps {
  target: UserRow | null;
  onClose: () => void;
  onSaved: () => void;
}

export function CcdSettingsDialog({ target, onClose, onSaved }: CcdSettingsDialogProps) {
  const toast = useToast();
  const [dns, setDns] = useState("");
  const [domain, setDomain] = useState("");
  const [routes, setRoutes] = useState("");
  const [mfaOnConnect, setMfaOnConnect] = useState(false);
  const [tunnelMode, setTunnelMode] = useState<"" | "full" | "split">("");
  const [staticIp, setStaticIp] = useState("");
  const [staticIpv6, setStaticIpv6] = useState("");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [savingIp, setSavingIp] = useState(false);
  const [allocatingIp, setAllocatingIp] = useState(false);
  const [savingIpv6, setSavingIpv6] = useState(false);
  const [allocatingIpv6, setAllocatingIpv6] = useState(false);

  useEffect(() => {
    if (!target) return;
    setStaticIp(target.staticIp ?? "");
    setStaticIpv6(target.staticIpv6 ?? "");
    setLoading(true);
    api<Record<string, unknown>>(endpoints.users + `/${target.id}/settings`)
      .then((s) => {
        setDns(Array.isArray(s.dns_servers) ? s.dns_servers.join(", ") : String(s.dns_servers ?? ""));
        setDomain(String(s.dns_domain ?? ""));
        setRoutes(
          Array.isArray(s.route_restriction)
            ? s.route_restriction.join(", ")
            : String(s.route_restriction ?? ""),
        );
        setMfaOnConnect(s.require_mfa_on_connect === true);
        const mode = s.tunnel_mode;
        setTunnelMode(mode === "full" || mode === "split" ? mode : "");
      })
      .catch(() => toast.error("Failed to load per-user settings"))
      .finally(() => setLoading(false));
  }, [target, toast]);

  const saveSettings = async () => {
    if (!target) return;
    setSaving(true);
    try {
      const base = endpoints.users + `/${target.id}/settings`;
      await api(base + "/dns_servers", { method: "PUT", body: JSON.stringify(dns) });
      await api(base + "/dns_domain", { method: "PUT", body: JSON.stringify(domain) });
      await api(base + "/route_restriction", { method: "PUT", body: JSON.stringify(routes) });
      await api(base + "/require_mfa_on_connect", {
        method: "PUT",
        body: JSON.stringify(mfaOnConnect),
      });
      await api(base + "/tunnel_mode", { method: "PUT", body: JSON.stringify(tunnelMode) });
      toast.success("Per-user settings saved");
      onSaved();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Save failed");
    } finally {
      setSaving(false);
    }
  };

  const saveStaticIp = async (ip: string) => {
    if (!target) return;
    setSavingIp(true);
    try {
      if (ip.trim()) {
        await api(endpoints.users + `/${target.id}/static-ip`, {
          method: "PUT",
          body: JSON.stringify({ staticIp: ip.trim() }),
        });
      } else {
        await api(endpoints.users + `/${target.id}/static-ip`, { method: "DELETE" });
      }
      toast.success("Static IP updated");
      onSaved();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Update failed");
    } finally {
      setSavingIp(false);
    }
  };

  const allocateIp = async () => {
    if (!target) return;
    setAllocatingIp(true);
    try {
      const updated = await api(endpoints.users + `/${target.id}/static-ip/allocate`, {
        method: "POST",
      });
      toast.success("Static IP allocated");
      setStaticIp((updated as unknown as UserRow).staticIp ?? "");
      onSaved();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Allocation failed");
    } finally {
      setAllocatingIp(false);
    }
  };

  const saveStaticIpv6 = async (ip: string) => {
    if (!target) return;
    setSavingIpv6(true);
    try {
      if (ip.trim()) {
        await api(endpoints.users + `/${target.id}/static-ipv6`, {
          method: "PUT",
          body: JSON.stringify({ staticIpv6: ip.trim() }),
        });
      } else {
        await api(endpoints.users + `/${target.id}/static-ipv6`, { method: "DELETE" });
      }
      toast.success("Static IPv6 updated");
      onSaved();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Update failed");
    } finally {
      setSavingIpv6(false);
    }
  };

  const allocateIpv6 = async () => {
    if (!target) return;
    setAllocatingIpv6(true);
    try {
      const updated = await api(endpoints.users + `/${target.id}/static-ipv6/allocate`, {
        method: "POST",
      });
      toast.success("Static IPv6 allocated");
      setStaticIpv6((updated as unknown as UserRow).staticIpv6 ?? "");
      onSaved();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Allocation failed");
    } finally {
      setAllocatingIpv6(false);
    }
  };

  const closeAndReset = () => {
    setDns("");
    setDomain("");
    setRoutes("");
    setMfaOnConnect(false);
    setTunnelMode("");
    onClose();
  };

  return (
    <Dialog open={!!target} onClose={closeAndReset} maxWidth="sm" fullWidth>
      <DialogTitle>CCD settings \u2014 {target?.username}</DialogTitle>
      <DialogContent>
        {loading ? (
          <Box sx={{ display: "flex", justifyContent: "center", py: 4 }}>
            <CircularProgress />
          </Box>
        ) : (
          <Stack spacing={3} sx={{ mt: 1 }}>
            <Box>
              <Typography variant="subtitle2" gutterBottom>
                Static IP
              </Typography>
              <Stack direction="row" spacing={1} alignItems="center">
                <TextField
                  size="small"
                  placeholder="e.g. 10.8.0.42"
                  value={staticIp}
                  onChange={(e) => setStaticIp(e.target.value)}
                  sx={{ flex: 1 }}
                  helperText={target?.staticIp ? "Override the group pool allocation." : "Leave empty to clear."}
                />
                <Button
                  variant="outlined"
                  startIcon={<TuneIcon />}
                  disabled={savingIp}
                  onClick={() => saveStaticIp(staticIp)}
                >
                  Set
                </Button>
                <Button
                  variant="contained"
                  disabled={allocatingIp}
                  onClick={allocateIp}
                  title="Allocate next free IP from the group pool"
                >
                  {allocatingIp ? <CircularProgress size={18} /> : "Allocate"}
                </Button>
                {target?.staticIp && (
                  <Tooltip title="Clear static IP">
                    <IconButton
                      size="small"
                      disabled={savingIp}
                      onClick={() => {
                        setStaticIp("");
                        saveStaticIp("");
                      }}
                    >
                      <ClearIcon fontSize="small" />
                    </IconButton>
                  </Tooltip>
                )}
              </Stack>
            </Box>
            <Box>
              <Typography variant="subtitle2" gutterBottom>
                Static IPv6
              </Typography>
              <Stack direction="row" spacing={1} alignItems="center">
                <TextField
                  size="small"
                  placeholder="e.g. fd00:1::42"
                  value={staticIpv6}
                  onChange={(e) => setStaticIpv6(e.target.value)}
                  sx={{ flex: 1 }}
                  helperText={target?.staticIpv6 ? "Override the group pool allocation." : "Leave empty to clear."}
                />
                <Button
                  variant="outlined"
                  startIcon={<TuneIcon />}
                  disabled={savingIpv6}
                  onClick={() => saveStaticIpv6(staticIpv6)}
                >
                  Set
                </Button>
                <Button
                  variant="contained"
                  disabled={allocatingIpv6}
                  onClick={allocateIpv6}
                  title="Allocate next free IPv6 from the group pool"
                >
                  {allocatingIpv6 ? <CircularProgress size={18} /> : "Allocate IPv6"}
                </Button>
                {target?.staticIpv6 && (
                  <Tooltip title="Clear static IPv6">
                    <IconButton
                      size="small"
                      disabled={savingIpv6}
                      onClick={() => {
                        setStaticIpv6("");
                        saveStaticIpv6("");
                      }}
                    >
                      <ClearIcon fontSize="small" />
                    </IconButton>
                  </Tooltip>
                )}
              </Stack>
            </Box>
            <Box>
              <Typography variant="subtitle2" gutterBottom>
                Per-user settings (override group/server defaults)
              </Typography>
              <Stack spacing={2}>
                <TextField
                  label="DNS servers"
                  size="small"
                  placeholder="e.g. 1.1.1.1, 8.8.8.8"
                  value={dns}
                  onChange={(e) => setDns(e.target.value)}
                  helperText="Comma-separated DNS servers pushed to this client."
                />
                <TextField
                  label="DNS domain"
                  size="small"
                  placeholder="e.g. vpn.example.com"
                  value={domain}
                  onChange={(e) => setDomain(e.target.value)}
                  helperText="Search domain pushed to this client."
                />
                <TextField
                  label="Tunnel mode"
                  size="small"
                  select
                  value={tunnelMode}
                  onChange={(e) => setTunnelMode(e.target.value as "" | "full" | "split")}
                  helperText="Full routes all traffic through the VPN; split routes only the networks below. Empty inherits the group/server default."
                >
                  <MenuItem value="">Inherit default</MenuItem>
                  <MenuItem value="full">Full tunnel</MenuItem>
                  <MenuItem value="split">Split tunnel</MenuItem>
                </TextField>
                <TextField
                  label="Route restriction"
                  size="small"
                  placeholder="e.g. 10.0.0.0/8, 192.168.0.0/16"
                  value={routes}
                  onChange={(e) => setRoutes(e.target.value)}
                  helperText="Comma-separated CIDRs this client may reach. Empty allows all."
                />
                <FormControlLabel
                  control={
                    <Switch
                      checked={mfaOnConnect}
                      onChange={(e) => setMfaOnConnect(e.target.checked)}
                    />
                  }
                  label="Require MFA on connect"
                />
              </Stack>
            </Box>
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={closeAndReset}>Cancel</Button>
        <Button variant="contained" disabled={saving} onClick={saveSettings}>
          {saving ? <CircularProgress size={18} /> : "Save"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
