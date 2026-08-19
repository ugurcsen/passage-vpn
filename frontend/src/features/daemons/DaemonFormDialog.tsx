import {
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
} from "@mui/material";
import type { OpenVpnNode } from "@/lib/api";
import type { DaemonForm } from "./helpers";

export interface DaemonFormDialogProps {
  open: boolean;
  editing: boolean;
  form: DaemonForm;
  nodes: OpenVpnNode[];
  newRoute: string;
  routeError: string | null;
  onChange: (patch: Partial<DaemonForm>) => void;
  onAddRoute: () => void;
  onRemoveRoute: (route: string) => void;
  onChangeNewRoute: (v: string) => void;
  onClearRouteError: () => void;
  onClose: () => void;
  onSave: () => void;
}

export function DaemonFormDialog({
  open,
  editing,
  form,
  nodes,
  newRoute,
  routeError,
  onChange,
  onAddRoute,
  onRemoveRoute,
  onChangeNewRoute,
  onClearRouteError,
  onClose,
  onSave,
}: DaemonFormDialogProps) {
  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{editing ? "Edit daemon" : "New daemon"}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Stack direction="row" spacing={2}>
            <TextField
              label="Name"
              value={form.name}
              onChange={(e) => onChange({ name: e.target.value })}
              placeholder="e.g. Generic access"
              sx={{ flex: 1 }}
            />
            <TextField
              label="Daemon index"
              value={form.daemonIndex}
              onChange={(e) => onChange({ daemonIndex: e.target.value })}
              required
              sx={{ width: 140 }}
              helperText="0 is the primary daemon"
            />
          </Stack>
          <Stack direction="row" spacing={2}>
            <TextField
              label="Port"
              value={form.port}
              onChange={(e) => onChange({ port: e.target.value })}
              sx={{ width: 140 }}
              helperText="Empty = auto-assign from published range"
            />
            <TextField
              select
              label="Protocol"
              value={form.proto}
              onChange={(e) => onChange({ proto: e.target.value as DaemonForm["proto"] })}
              sx={{ width: 140 }}
            >
              <MenuItem value="udp">UDP</MenuItem>
              <MenuItem value="tcp">TCP</MenuItem>
              <MenuItem value="udp6">UDP6</MenuItem>
              <MenuItem value="tcp6">TCP6</MenuItem>
            </TextField>
          </Stack>
          <Stack direction="row" spacing={2}>
            <TextField
              label="Admin host"
              value={form.adminHost}
              onChange={(e) => onChange({ adminHost: e.target.value })}
              placeholder="vpn.example.com"
              sx={{ flex: 1 }}
            />
            <TextField
              select
              label="VPN node"
              value={form.nodeId}
              onChange={(e) => onChange({ nodeId: e.target.value })}
              helperText="Empty = local deployment"
              sx={{ width: 220 }}
            >
              <MenuItem value="">Local (this server)</MenuItem>
              {nodes
                .filter((n) => n.enabled)
                .map((n) => (
                  <MenuItem key={n.id} value={n.id}>
                    {n.name}
                  </MenuItem>
                ))}
            </TextField>
          </Stack>
          <Stack direction="row" spacing={2}>
            <TextField
              label="Subnet"
              value={form.subnet}
              onChange={(e) => onChange({ subnet: e.target.value })}
              required
              placeholder="10.8.0.0"
              sx={{ flex: 1 }}
            />
            <TextField
              label="Subnet mask"
              value={form.subnetMask}
              onChange={(e) => onChange({ subnetMask: e.target.value })}
              required
              placeholder="255.255.255.0"
              sx={{ width: 160 }}
            />
          </Stack>
          <TextField
            label="DNS servers (comma separated)"
            value={form.dnsServers}
            onChange={(e) => onChange({ dnsServers: e.target.value })}
            placeholder="1.1.1.1, 8.8.8.8"
          />
          <Stack direction="row" spacing={2}>
            <TextField
              label="DNS domain"
              value={form.domain}
              onChange={(e) => onChange({ domain: e.target.value })}
              sx={{ flex: 1 }}
            />
          </Stack>
          <Stack spacing={1}>
            <Typography variant="body2" color="text.secondary">
              Extra routes (split tunnel only)
            </Typography>
            <Stack direction="row" spacing={1}>
              <TextField
                size="small"
                value={newRoute}
                onChange={(e) => {
                  onChangeNewRoute(e.target.value);
                  onClearRouteError();
                }}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault();
                    onAddRoute();
                  }
                }}
                placeholder="192.168.0.0/24 or fd00::/64"
                error={!!routeError}
                helperText={routeError}
                sx={{ flex: 1 }}
              />
              <Button variant="outlined" onClick={onAddRoute}>
                Add
              </Button>
            </Stack>
            {form.extraRoutes.length > 0 && (
              <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 0.5 }}>
                {form.extraRoutes.map((route) => (
                  <Chip
                    key={route}
                    label={route}
                    color={route.includes(":") ? "info" : "default"}
                    onDelete={() => onRemoveRoute(route)}
                    size="small"
                  />
                ))}
              </Stack>
            )}
            <Typography variant="caption" color="text.secondary">
              IPv4: 192.168.0.0/24 — IPv6: fd00::/8
            </Typography>
          </Stack>
          <FormControlLabel
            control={
              <Switch
                checked={form.fullTunnel}
                onChange={(e) => onChange({ fullTunnel: e.target.checked })}
              />
            }
            label="Full tunnel (route all traffic through VPN)"
          />
          <FormControlLabel
            control={
              <Switch
                checked={form.ipv6Enabled}
                onChange={(e) => onChange({ ipv6Enabled: e.target.checked })}
              />
            }
            label="Enable IPv6 (dual-stack tunnel)"
          />
          {form.ipv6Enabled && (
            <TextField
              label="IPv6 subnet"
              value={form.ipv6Subnet}
              onChange={(e) => onChange({ ipv6Subnet: e.target.value })}
              helperText="Client subnet in CIDR form, e.g. fd00:1::/64"
            />
          )}
          <FormControlLabel
            control={
              <Switch
                checked={form.clientCertNotRequired}
                onChange={(e) => onChange({ clientCertNotRequired: e.target.checked })}
              />
            }
            label="Client cert not required (serves GENERIC profiles)"
          />
          <FormControlLabel
            control={
              <Switch
                checked={form.authUserPass}
                onChange={(e) => onChange({ authUserPass: e.target.checked })}
              />
            }
            label="Username/password auth (disable for AUTO_LOGIN cert-only daemon)"
          />
          <FormControlLabel
            control={
              <Switch
                checked={form.enabled}
                onChange={(e) => onChange({ enabled: e.target.checked })}
              />
            }
            label="Enabled"
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={!form.daemonIndex || !form.subnet || !form.subnetMask}
          onClick={onSave}
        >
          {editing ? "Save" : "Create"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
