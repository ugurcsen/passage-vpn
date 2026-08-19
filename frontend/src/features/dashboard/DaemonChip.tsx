import { Box, Chip, Tooltip } from "@mui/material";
import type { DaemonHealth } from "@/lib/api";

export function DaemonChip({ daemon }: { daemon: DaemonHealth }) {
  const ok = daemon.mgmtReachable && daemon.enabled && daemon.configPresent;
  const status = !daemon.enabled
    ? "Disabled"
    : !daemon.configPresent
      ? "Config missing"
      : daemon.mgmtReachable
        ? "Online"
        : "Management down";
  const label = `#${daemon.index} ${daemon.name ?? "unnamed"}`;
  return (
    <Tooltip title={`${label} · ${daemon.proto.toUpperCase()} :${daemon.port} · ${status}${daemon.dco ? " · DCO" : ""}`}>
      <Chip
        size="small"
        color={ok ? "success" : daemon.enabled ? "warning" : "default"}
        variant={ok ? "filled" : "outlined"}
        label={
          <Box sx={{ display: "inline-flex", alignItems: "center", gap: 0.75 }}>
            <Box
              component="span"
              sx={{
                width: 8,
                height: 8,
                borderRadius: "50%",
                bgcolor: ok ? "success.contrastText" : daemon.enabled ? "warning.main" : "text.disabled",
                display: "inline-block",
              }}
            />
            <Box component="span" sx={{ fontWeight: 500 }}>
              {label}
            </Box>
            <Box component="span" sx={{ fontSize: "0.7rem", opacity: 0.9 }}>
              {daemon.proto.toUpperCase()}:{daemon.port}
            </Box>
            {daemon.dco ? (
              <Box
                component="span"
                sx={{
                  fontSize: "0.65rem",
                  px: 0.5,
                  py: 0.1,
                  borderRadius: 1,
                  bgcolor: "secondary.dark",
                  color: "secondary.contrastText",
                  lineHeight: 1.2,
                }}
                title="Data Channel Offload (kernel)"
              >
                DCO
              </Box>
            ) : null}
          </Box>
        }
      />
    </Tooltip>
  );
}
