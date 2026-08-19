import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Box,
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
import { api, endpoints, type ServerSettings } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import {
  type ServerConfigForm,
  type SettingValueType,
  emptyServerConfigForm,
  formToServerConfig,
  KNOWN_SETTINGS,
  knownSetting,
  normalizeSetting,
  serializeSetting,
  serverConfigToForm,
} from "@/features/settings/knownSettings";
import { type DefaultDialog, type AdvancedDialog, parseRoutes } from "./types";
import { ServerDefaultsTable } from "./ServerDefaultsTable";
import { AdvancedSettingsSection } from "./AdvancedSettingsSection";
import { AdvancedSettingDialog } from "./AdvancedSettingDialog";

/** Settings page: typed editors for well-known defaults plus a raw JSON section for custom keys. */
export function SettingsPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<DefaultDialog | null>(null);
  const [advanced, setAdvanced] = useState<AdvancedDialog | null>(null);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [confirm, setConfirm] = useState<{ title: string; text: string; action: () => void } | null>(null);
  const [newRoute, setNewRoute] = useState("");
  const [routeError, setRouteError] = useState<string | null>(null);

  const { data, isLoading, error } = useQuery<ServerSettings>({
    queryKey: ["admin-settings"],
    queryFn: () => api<ServerSettings>(endpoints.settings),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-settings"] });

  const IPV4_CIDR = /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\/\d{1,2}$/;
  const IPV6_CIDR = /^[0-9a-fA-F:.]+\/\d{1,3}$/;

  const addRoute = () => {
    if (!dialog?.config) return;
    const trimmed = newRoute.trim();
    if (!trimmed) return;
    if (!IPV4_CIDR.test(trimmed) && !IPV6_CIDR.test(trimmed)) {
      setRouteError("Invalid CIDR format (e.g. 192.168.0.0/24 or fd00::/64)");
      return;
    }
    const current = parseRoutes(dialog.config.extraRoutes);
    if (current.includes(trimmed)) {
      setRouteError("Route already exists");
      return;
    }
    updateConfig({ extraRoutes: [...current, trimmed].join(", ") });
    setNewRoute("");
    setRouteError(null);
  };

  const removeRoute = (route: string) => {
    if (!dialog?.config) return;
    const current = parseRoutes(dialog.config.extraRoutes).filter((r) => r !== route);
    updateConfig({ extraRoutes: current.join(", ") });
  };

  const save = useMutation({
    mutationFn: ({ key, value }: { key: string; value: unknown }) =>
      api<ServerSettings>(`${endpoints.settings}/${encodeURIComponent(key)}`, {
        method: "PUT",
        body: JSON.stringify({ value }),
      }),
    onSuccess: (updated, vars) => {
      queryClient.setQueryData(["admin-settings"], updated);
      toast.success(`Saved "${vars.key}"`);
      setDialog(null);
      setAdvanced(null);
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Save failed"),
  });

  const remove = useMutation({
    mutationFn: (key: string) =>
      api<void>(`${endpoints.settings}/${encodeURIComponent(key)}`, { method: "DELETE" }),
    onSuccess: (_result, key) => {
      toast.success(`Deleted "${key}"`);
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Delete failed"),
  });

  const entries = Object.entries(data ?? {});
  const knownEntries = entries.filter(([k]) => knownSetting(k));
  const availableDefaults = KNOWN_SETTINGS.filter(
    (s) => s.type !== "serverConfig" && !knownEntries.some(([k]) => k === s.key),
  );

  const openAddDefault = () =>
    setDialog({ key: availableDefaults[0]?.key ?? "", value: "", isNew: true });
  const openEditDefault = (key: string, value: unknown) => {
    const type = knownSetting(key)?.type ?? "json";
    if (type === "serverConfig") {
      setDialog({ key, value: "", isNew: false, config: serverConfigToForm(value) });
      return;
    }
    setDialog({ key, value: normalizeSetting(type, value), isNew: false });
  };

  const dialogSetting = dialog ? knownSetting(dialog.key) : undefined;
  const dialogType: SettingValueType = dialogSetting?.type ?? "json";
  const numberInvalid =
    dialogType === "number" && dialog !== null && dialog.value.trim() !== "" &&
    !(Number.isInteger(Number(dialog.value)) && Number(dialog.value) >= 0);
  const configInvalid = (() => {
    if (dialogType !== "serverConfig" || !dialog?.config) return false;
    const c = dialog.config;
    const port = Number(c.port);
    return (
      !Number.isInteger(port) ||
      port < 1 ||
      port > 65535 ||
      c.subnet.trim() === "" ||
      c.subnetMask.trim() === "" ||
      c.adminHost.trim() === "" ||
      (c.ipv6Enabled && c.ipv6Subnet.trim() === "")
    );
  })();
  const dialogValid = dialog !== null && dialog.key.trim() !== "" && !numberInvalid && !configInvalid;

  const updateConfig = (patch: Partial<ServerConfigForm>) =>
    setDialog((d) => (d ? { ...d, config: { ...d.config!, ...patch } } : d));

  const submitDialog = () => {
    if (!dialog || !dialogValid) return;
    const value =
      dialogType === "serverConfig"
        ? formToServerConfig(dialog.config ?? emptyServerConfigForm())
        : serializeSetting(dialogType, dialog.value);
    save.mutate({ key: dialog.key, value });
  };

  const submitAdvanced = () => {
    if (!advanced) return;
    save.mutate({ key: advanced.key, value: serializeSetting("json", advanced.value) });
  };

  const toggleKnown = (key: string, checked: boolean) => save.mutate({ key, value: checked });
  const toggleChoice = (key: string, value: string) => save.mutate({ key, value });

  return (
    <Box>
      <Typography variant="h5" fontWeight={700} sx={{ mb: 1 }}>
        Settings
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Server-wide defaults applied to every account. Group and per-user settings override them.
      </Typography>
      {error && (
        <Alert severity="error" sx={{ mb: 3 }}>
          {(error as Error).message}
        </Alert>
      )}

      <ServerDefaultsTable
        data={data}
        isLoading={isLoading}
        savePending={save.isPending}
        onOpenAdd={openAddDefault}
        onOpenEdit={openEditDefault}
        onToggleBoolean={toggleKnown}
        onToggleChoice={toggleChoice}
        onDelete={(key, title, text) => setConfirm({ title, text, action: () => remove.mutate(key) })}
      />

      <AdvancedSettingsSection
        data={data}
        showAdvanced={showAdvanced}
        onToggleShow={() => setShowAdvanced((s) => !s)}
        onOpenAdd={() => setAdvanced({ key: "", value: "", isNew: true })}
        onOpenEdit={(key, value) => setAdvanced({ key, value: normalizeSetting("json", value), isNew: false })}
        onDelete={(key, title, text) => setConfirm({ title, text, action: () => remove.mutate(key) })}
      />

      <Dialog open={!!dialog} onClose={() => { setDialog(null); setNewRoute(""); setRouteError(null); }} maxWidth="sm" fullWidth>
        <DialogTitle>
          {dialog?.isNew ? "Add default setting" : dialog && dialogSetting ? `Edit ${dialogSetting.label}` : "Add default setting"}
        </DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {dialog?.isNew ? (
              <TextField
                select
                label="Setting"
                value={dialog?.key ?? ""}
                onChange={(e) => setDialog((d) => (d ? { ...d, key: e.target.value } : d))}
                helperText={dialog ? knownSetting(dialog.key)?.description : undefined}
              >
                {availableDefaults.map((s) => (
                  <MenuItem key={s.key} value={s.key}>
                    {s.label}
                  </MenuItem>
                ))}
              </TextField>
            ) : dialogSetting ? (
              <TextField
                label="Setting"
                value={dialogSetting.label}
                helperText={dialogSetting.description}
                disabled
              />
            ) : null}
            {dialogType === "serverConfig" ? (
              <Stack spacing={2}>
                <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
                  <TextField
                    label="Port"
                    type="number"
                    value={dialog?.config?.port ?? ""}
                    onChange={(e) => updateConfig({ port: e.target.value })}
                    error={configInvalid}
                    helperText={configInvalid ? "Port must be 1-65535" : "UDP/TCP listen port"}
                    sx={{ flex: 1 }}
                  />
                  <TextField
                    select
                    label="Protocol"
                    value={dialog?.config?.proto ?? "udp"}
                    onChange={(e) =>
                      updateConfig({ proto: e.target.value as "udp" | "tcp" | "udp6" | "tcp6" })
                    }
                    sx={{ flex: 1 }}
                  >
                    <MenuItem value="udp">UDP</MenuItem>
                    <MenuItem value="tcp">TCP</MenuItem>
                    <MenuItem value="udp6">UDP6</MenuItem>
                    <MenuItem value="tcp6">TCP6</MenuItem>
                  </TextField>
                </Stack>
                <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
                  <TextField
                    label="Subnet"
                    value={dialog?.config?.subnet ?? ""}
                    onChange={(e) => updateConfig({ subnet: e.target.value })}
                    error={configInvalid && !(dialog?.config?.subnet.trim())}
                    helperText="VPN client subnet"
                    sx={{ flex: 1 }}
                  />
                  <TextField
                    label="Subnet mask"
                    value={dialog?.config?.subnetMask ?? ""}
                    onChange={(e) => updateConfig({ subnetMask: e.target.value })}
                    error={configInvalid && !(dialog?.config?.subnetMask.trim())}
                    sx={{ flex: 1 }}
                  />
                </Stack>
                <FormControlLabel
                  control={
                    <Switch
                      checked={dialog?.config?.ipv6Enabled ?? false}
                      onChange={(e) => updateConfig({ ipv6Enabled: e.target.checked })}
                    />
                  }
                  label="Enable IPv6 (dual-stack tunnel)"
                />
                {dialog?.config?.ipv6Enabled && (
                  <TextField
                    label="IPv6 subnet"
                    value={dialog?.config?.ipv6Subnet ?? ""}
                    onChange={(e) => updateConfig({ ipv6Subnet: e.target.value })}
                    error={configInvalid && !(dialog?.config?.ipv6Subnet.trim())}
                    helperText="Client subnet in CIDR form, e.g. fd00:1::/64"
                  />
                )}
                <TextField
                  label="DNS servers"
                  value={dialog?.config?.dnsServers ?? ""}
                  onChange={(e) => updateConfig({ dnsServers: e.target.value })}
                  helperText="Comma separated DNS servers pushed to clients"
                />
                <TextField
                  label="Domain"
                  value={dialog?.config?.domain ?? ""}
                  onChange={(e) => updateConfig({ domain: e.target.value })}
                  helperText="DNS search domain pushed to clients (optional)"
                />
                <Stack spacing={1}>
                  <Typography variant="body2" color="text.secondary">
                    Extra routes (split tunnel only)
                  </Typography>
                  <Stack direction="row" spacing={1}>
                    <TextField
                      size="small"
                      value={newRoute}
                      onChange={(e) => {
                        setNewRoute(e.target.value);
                        setRouteError(null);
                      }}
                      onKeyDown={(e) => {
                        if (e.key === "Enter") {
                          e.preventDefault();
                          addRoute();
                        }
                      }}
                      placeholder="192.168.0.0/24 or fd00::/64"
                      error={!!routeError}
                      helperText={routeError}
                      sx={{ flex: 1 }}
                    />
                    <Button variant="outlined" onClick={addRoute}>
                      Add
                    </Button>
                  </Stack>
                  {parseRoutes(dialog?.config?.extraRoutes ?? "").length > 0 && (
                    <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 0.5 }}>
                      {parseRoutes(dialog?.config?.extraRoutes ?? "").map((route) => (
                        <Chip
                          key={route}
                          label={route}
                          color={route.includes(":") ? "info" : "default"}
                          onDelete={() => removeRoute(route)}
                          size="small"
                        />
                      ))}
                    </Stack>
                  )}
                  <Typography variant="caption" color="text.secondary">
                    IPv4: 192.168.0.0/24 — IPv6: fd00::/8
                  </Typography>
                </Stack>
                <TextField
                  label="Admin host"
                  value={dialog?.config?.adminHost ?? ""}
                  onChange={(e) => updateConfig({ adminHost: e.target.value })}
                  error={configInvalid && !(dialog?.config?.adminHost.trim())}
                  helperText="VPN server hostname/IP pushed to clients"
                />
                <FormControlLabel
                  control={
                    <Switch
                      checked={dialog?.config?.fullTunnel ?? true}
                      onChange={(e) => updateConfig({ fullTunnel: e.target.checked })}
                    />
                  }
                  label="Full tunnel"
                />
                <FormControlLabel
                  control={
                    <Switch
                      checked={dialog?.config?.clientCertNotRequired ?? false}
                      onChange={(e) => updateConfig({ clientCertNotRequired: e.target.checked })}
                    />
                  }
                  label="Client certificate not required"
                />
                <FormControlLabel
                  control={
                    <Switch
                      checked={dialog?.config?.authUserPass ?? true}
                      onChange={(e) => updateConfig({ authUserPass: e.target.checked })}
                    />
                  }
                  label="Username + password auth"
                />
              </Stack>
            ) : dialogType === "boolean" ? (
              <FormControlLabel
                control={
                  <Switch
                    checked={dialog?.value === "true"}
                    onChange={(e) => setDialog((d) => (d ? { ...d, value: e.target.checked ? "true" : "false" } : d))}
                  />
                }
                label={dialog?.value === "true" ? "On" : "Off"}
              />
            ) : dialogType === "choice" ? (
              <TextField
                select
                label="Value"
                value={dialog?.value ?? ""}
                onChange={(e) => setDialog((d) => (d ? { ...d, value: e.target.value } : d))}
                helperText={dialogSetting?.description}
              >
                {(dialogSetting?.options ?? []).map((opt) => (
                  <MenuItem key={opt} value={opt}>
                    {opt}
                  </MenuItem>
                ))}
              </TextField>
            ) : dialogType === "number" ? (
              <TextField
                label="Value"
                type="number"
                value={dialog?.value ?? ""}
                onChange={(e) => setDialog((d) => (d ? { ...d, value: e.target.value } : d))}
                helperText={numberInvalid ? "Must be a whole number of 0 or more" : "0 = unlimited"}
                error={numberInvalid}
                placeholder={dialogSetting?.placeholder}
              />
            ) : dialogType === "json" ? (
              <TextField
                label="Value (JSON)"
                value={dialog?.value ?? ""}
                onChange={(e) => setDialog((d) => (d ? { ...d, value: e.target.value } : d))}
                multiline
                minRows={3}
                placeholder='e.g. {"limit": 5}'
                sx={{ fontFamily: "monospace" }}
              />
            ) : (
              <TextField
                label="Value"
                value={dialog?.value ?? ""}
                onChange={(e) => setDialog((d) => (d ? { ...d, value: e.target.value } : d))}
                helperText={dialogType === "list" ? "Comma separated values" : undefined}
                placeholder={dialogSetting?.placeholder}
              />
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => { setDialog(null); setNewRoute(""); setRouteError(null); }}>Cancel</Button>
          <Button variant="contained" disabled={!dialogValid || save.isPending} onClick={submitDialog}>
            Save
          </Button>
        </DialogActions>
      </Dialog>

      <AdvancedSettingDialog
        open={!!advanced}
        dialog={advanced ?? { key: "", value: "", isNew: true }}
        savePending={save.isPending}
        onChange={(patch) => setAdvanced((d) => d ? { ...d, ...patch } : null)}
        onClose={() => setAdvanced(null)}
        onSave={submitAdvanced}
      />

      <ConfirmDialog
        open={!!confirm}
        title={confirm?.title ?? ""}
        message={confirm?.text}
        danger
        confirmLabel="Delete"
        onCancel={() => setConfirm(null)}
        onConfirm={() => {
          confirm?.action();
          setConfirm(null);
        }}
      />
    </Box>
  );
}
