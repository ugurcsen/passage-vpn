import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  IconButton,
  MenuItem,
  Paper,
  Skeleton,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import DeleteIcon from "@mui/icons-material/Delete";
import EditIcon from "@mui/icons-material/Edit";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import { api, endpoints, type ServerSettings } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import {
  type ServerConfigForm,
  type SettingValueType,
  displaySetting,
  emptyServerConfigForm,
  formToServerConfig,
  KNOWN_SETTINGS,
  knownSetting,
  normalizeSetting,
  serializeSetting,
  serverConfigToForm,
} from "@/features/settings/knownSettings";

const ADVANCED_KEY_PATTERN = /^[a-zA-Z0-9_.-]{1,64}$/;

/** Dialog state for the typed "server defaults" editor. `key` is empty until a setting is chosen. */
interface DefaultDialog {
  key: string;
  value: string;
  isNew: boolean;
  /** Structured form state for `serverConfig`-typed settings (e.g. `network`). */
  config?: ServerConfigForm;
}

/** Dialog state for the raw JSON advanced editor. */
interface AdvancedDialog {
  key: string;
  value: string;
  isNew: boolean;
}

/** Settings page: typed editors for well-known defaults plus a raw JSON section for custom keys. */
export function SettingsPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<DefaultDialog | null>(null);
  const [advanced, setAdvanced] = useState<AdvancedDialog | null>(null);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [confirm, setConfirm] = useState<{ title: string; text: string; action: () => void } | null>(null);

  const { data, isLoading, error } = useQuery<ServerSettings>({
    queryKey: ["admin-settings"],
    queryFn: () => api<ServerSettings>(endpoints.settings),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-settings"] });

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
  const customEntries = entries.filter(([k]) => !knownSetting(k));
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
      c.adminHost.trim() === ""
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

      <Paper sx={{ p: 3, mb: 3, overflowX: "auto" }}>
        <Stack
          direction={{ xs: "column", sm: "row" }}
          justifyContent="space-between"
          alignItems={{ xs: "flex-start", sm: "center" }}
          sx={{ mb: 2 }}
        >
          <Box>
            <Typography variant="h6" fontWeight={600}>
              Server defaults
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {`${knownEntries.length} configured`}
            </Typography>
          </Box>
          <Button
            variant="contained"
            startIcon={<AddIcon />}
            disabled={availableDefaults.length === 0}
            onClick={openAddDefault}
          >
            Add default
          </Button>
        </Stack>

        {isLoading ? (
          <Skeleton height={120} />
        ) : knownEntries.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ py: 3, textAlign: "center" }}>
            No server defaults configured yet.
          </Typography>
        ) : (
          <Table size="small" sx={{ tableLayout: "fixed", width: "100%" }}>
            <TableHead>
              <TableRow>
                <TableCell sx={{ width: "35%" }}>Setting</TableCell>
                <TableCell sx={{ width: "45%" }}>Value</TableCell>
                <TableCell align="right" sx={{ width: "20%" }}>
                  Actions
                </TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {knownEntries.map(([k, v]) => {
                const setting = knownSetting(k)!;
                const isBoolean = setting.type === "boolean";
                return (
                  <TableRow key={k}>
                    <TableCell>
                      <Typography variant="body2" fontWeight={600}>
                        {setting.label}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {setting.description}
                      </Typography>
                    </TableCell>
                    <TableCell sx={{ overflowWrap: "anywhere" }}>
                      {isBoolean ? (
                        <Stack direction="row" alignItems="center" spacing={1}>
                          <Switch
                            size="small"
                            checked={v === true || v === "true"}
                            onChange={(e) => toggleKnown(k, e.target.checked)}
                            disabled={save.isPending}
                            inputProps={{ "aria-label": setting.label }}
                          />
                          <Typography variant="body2" color="text.secondary">
                            {v === true || v === "true" ? "On" : "Off"}
                          </Typography>
                        </Stack>
                      ) : setting.type === "choice" ? (
                        <TextField
                          select
                          size="small"
                          value={v === null || v === undefined ? "" : String(v)}
                          onChange={(e) => toggleChoice(k, e.target.value)}
                          disabled={save.isPending}
                          inputProps={{ "aria-label": setting.label }}
                          sx={{ minWidth: 140 }}
                        >
                          {(setting.options ?? []).map((opt) => (
                            <MenuItem key={opt} value={opt}>
                              {opt}
                            </MenuItem>
                          ))}
                        </TextField>
                      ) : (
                        <Typography variant="body2" color="text.secondary">
                          {displaySetting(setting.type, v)}
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell align="right">
                      <Stack direction="row" justifyContent="flex-end">
                        {!isBoolean && (
                          <Tooltip title="Edit value">
                            <IconButton size="small" aria-label={`Edit ${setting.label}`} onClick={() => openEditDefault(k, v)}>
                              <EditIcon fontSize="small" />
                            </IconButton>
                          </Tooltip>
                        )}
                        <Tooltip title="Delete setting">
                          <IconButton
                            size="small"
                            aria-label={`Delete ${setting.label}`}
                            onClick={() =>
                              setConfirm({
                                title: "Delete setting",
                                text: `Delete the "${k}" setting? Accounts will fall back to group and per-user values.`,
                                action: () => remove.mutate(k),
                              })
                            }
                          >
                            <DeleteIcon fontSize="small" />
                          </IconButton>
                        </Tooltip>
                      </Stack>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </Paper>

      <Paper sx={{ p: 3, overflowX: "auto" }}>
        <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 2 }}>
          <Box>
            <Typography variant="h6" fontWeight={600}>
              Advanced settings
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {`Custom keys stored as raw JSON (${customEntries.length})`}
            </Typography>
          </Box>
          <Stack direction="row" spacing={1}>
            <Button
              variant="outlined"
              startIcon={<AddIcon />}
              onClick={() => setAdvanced({ key: "", value: "", isNew: true })}
            >
              Add custom setting
            </Button>
            <IconButton
              aria-label="Toggle advanced settings"
              onClick={() => setShowAdvanced((s) => !s)}
              sx={{ transform: showAdvanced ? "rotate(180deg)" : "none", transition: "transform 0.2s" }}
            >
              <ExpandMoreIcon />
            </IconButton>
          </Stack>
        </Stack>

        {showAdvanced &&
          (customEntries.length === 0 ? (
            <Typography variant="body2" color="text.secondary" sx={{ py: 3, textAlign: "center" }}>
              No custom settings stored yet.
            </Typography>
          ) : (
            <Table size="small" sx={{ tableLayout: "fixed", width: "100%" }}>
              <TableHead>
                <TableRow>
                  <TableCell sx={{ width: "30%" }}>Key</TableCell>
                  <TableCell sx={{ width: "60%" }}>Value (JSON)</TableCell>
                  <TableCell align="right" sx={{ width: "10%" }}>
                    Actions
                  </TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {customEntries.map(([k, v]) => (
                  <TableRow key={k}>
                    <TableCell sx={{ fontWeight: 600, overflowWrap: "anywhere" }}>{k}</TableCell>
                    <TableCell sx={{ overflowWrap: "anywhere" }}>
                      <Typography
                        variant="body2"
                        component="pre"
                        sx={{ m: 0, fontFamily: "monospace", whiteSpace: "pre-wrap", fontSize: "0.8rem" }}
                      >
                        {JSON.stringify(v, null, 2)}
                      </Typography>
                    </TableCell>
                    <TableCell align="right">
                      <Stack direction="row" justifyContent="flex-end">
                        <Tooltip title="Edit value">
                          <IconButton
                            size="small"
                            aria-label={`Edit ${k}`}
                            onClick={() => setAdvanced({ key: k, value: normalizeSetting("json", v), isNew: false })}
                          >
                            <EditIcon fontSize="small" />
                          </IconButton>
                        </Tooltip>
                        <Tooltip title="Delete setting">
                          <IconButton
                            size="small"
                            aria-label={`Delete ${k}`}
                            onClick={() =>
                              setConfirm({
                                title: "Delete setting",
                                text: `Delete the "${k}" setting?`,
                                action: () => remove.mutate(k),
                              })
                            }
                          >
                            <DeleteIcon fontSize="small" />
                          </IconButton>
                        </Tooltip>
                      </Stack>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ))}
      </Paper>

      <Dialog open={!!dialog} onClose={() => setDialog(null)} maxWidth="sm" fullWidth>
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
                    onChange={(e) => updateConfig({ proto: e.target.value as "udp" | "tcp" })}
                    sx={{ flex: 1 }}
                  >
                    <MenuItem value="udp">UDP</MenuItem>
                    <MenuItem value="tcp">TCP</MenuItem>
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
                <TextField
                  label="Extra routes"
                  value={dialog?.config?.extraRoutes ?? ""}
                  onChange={(e) => updateConfig({ extraRoutes: e.target.value })}
                  helperText="Comma separated CIDR networks to route (optional)"
                />
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
          <Button onClick={() => setDialog(null)}>Cancel</Button>
          <Button variant="contained" disabled={!dialogValid || save.isPending} onClick={submitDialog}>
            Save
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={!!advanced} onClose={() => setAdvanced(null)} maxWidth="sm" fullWidth>
        <DialogTitle>{advanced?.isNew ? "Add custom setting" : advanced?.key ? `Edit ${advanced.key}` : "Add custom setting"}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Key"
              value={advanced?.key ?? ""}
              onChange={(e) => setAdvanced((d) => (d ? { ...d, key: e.target.value } : d))}
              disabled={advanced !== null && !advanced.isNew}
              helperText="Letters, numbers, dots, dashes and underscores (1-64 chars)"
              placeholder="e.g. support_email"
            />
            <TextField
              label="Value (JSON)"
              value={advanced?.value ?? ""}
              onChange={(e) => setAdvanced((d) => (d ? { ...d, value: e.target.value } : d))}
              multiline
              minRows={4}
              placeholder='e.g. "admin@example.com" or {"limit": 5}'
              sx={{ fontFamily: "monospace" }}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setAdvanced(null)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={!advanced || !ADVANCED_KEY_PATTERN.test(advanced.key.trim()) || save.isPending}
            onClick={submitAdvanced}
          >
            Save
          </Button>
        </DialogActions>
      </Dialog>

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
