import { useEffect, useState } from "react";
import {
  Box,
  Button,
  Card,
  CardActions,
  CardContent,
  Collapse,
  CircularProgress,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import DownloadIcon from "@mui/icons-material/Download";
import QrCode2Icon from "@mui/icons-material/QrCode2";
import RefreshIcon from "@mui/icons-material/Refresh";
import { QRCodeSVG } from "qrcode.react";
import { downloadOvpn, type OvpnFile, type ProfileDaemon } from "@/lib/api";

export interface QrData {
  /** Text encoded in the QR code (the share URL). */
  payload: string;
  /** Epoch millis when the share link expires (server-authoritative). */
  expiresAt: number;
}

interface ProfileCardProps {
  title: string;
  subtitle: string;
  disabled?: boolean;
  fetch: () => Promise<OvpnFile>;
  /** When provided, the QR encodes this short-lived share link instead of the full profile
   *  content (which exceeds QR capacity). The card then shows a live expiry countdown. */
  qrFetch?: () => Promise<QrData>;
  /** Optional daemon picker shown when a profile type is served by more than one daemon
   *  (e.g. full-tunnel vs split-tunnel instances). */
  daemonOptions?: ProfileDaemon[];
  daemon?: number | null;
  onDaemonChange?: (daemonIndex: number | null) => void;
}

function daemonLabel(d: ProfileDaemon): string {
  const name = d.name ?? `Daemon ${d.daemonIndex}`;
  const routing = d.fullTunnel ? "full tunnel" : "split tunnel";
  return `${name} · ${routing} (${d.proto}:${d.port})`;
}

function formatRemaining(ms: number): string {
  const total = Math.max(0, Math.ceil(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}

/** A profile tile with download and QR-code share actions. */
export function ProfileCard({
  title,
  subtitle,
  disabled,
  fetch,
  qrFetch,
  daemonOptions,
  daemon,
  onDaemonChange,
}: ProfileCardProps) {
  const [qrOpen, setQrOpen] = useState(false);
  const [content, setContent] = useState<string | null>(null);
  const [expiresAt, setExpiresAt] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [qrLoading, setQrLoading] = useState(false);
  const [qrError, setQrError] = useState(false);

  const remainingMs = expiresAt != null ? expiresAt - now : null;
  const expired = remainingMs != null && remainingMs <= 0;

  useEffect(() => {
    if (!qrOpen || expiresAt == null) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [qrOpen, expiresAt]);

  const download = async () => {
    try {
      downloadOvpn(await fetch());
    } catch {
      /* toast handled by parent when provided; silent here */
    }
  };

  const load = async () => {
    setQrLoading(true);
    setQrError(false);
    try {
      if (qrFetch) {
        const data = await qrFetch();
        setContent(data.payload);
        setExpiresAt(data.expiresAt);
      } else {
        setContent((await fetch()).content);
        setExpiresAt(null);
      }
      setNow(Date.now());
      setQrOpen(true);
    } catch {
      setQrError(true);
    } finally {
      setQrLoading(false);
    }
  };

  const toggleQr = async () => {
    if (qrOpen) {
      setQrOpen(false);
      return;
    }
    await load();
  };

  return (
    <Card variant="outlined" sx={{ height: "100%" }}>
      <CardContent>
        <Typography variant="subtitle1" fontWeight={600}>
          {title}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
          {subtitle}
        </Typography>
        {daemonOptions && daemonOptions.length > 1 && (
          <TextField
            select
            size="small"
            fullWidth
            label="Server"
            value={daemon ?? ""}
            disabled={disabled}
            onChange={(e) => onDaemonChange?.(e.target.value === "" ? null : Number(e.target.value))}
            helperText="Choose which VPN server this profile connects to."
            sx={{ mt: 1 }}
          >
            <MenuItem value="">All servers (auto)</MenuItem>
            {daemonOptions.map((d) => (
              <MenuItem key={d.daemonIndex} value={d.daemonIndex}>
                {daemonLabel(d)}
              </MenuItem>
            ))}
          </TextField>
        )}
      </CardContent>
      <CardActions>
        <Stack direction="row" spacing={1} sx={{ px: 1, pb: 1, width: "100%" }}>
          <Button
            size="small"
            variant="contained"
            startIcon={<DownloadIcon />}
            disabled={disabled}
            onClick={download}
            fullWidth
          >
            Download
          </Button>
          <Button
            size="small"
            variant="outlined"
            startIcon={qrLoading ? <CircularProgress size={14} /> : <QrCode2Icon />}
            disabled={disabled || qrLoading}
            onClick={toggleQr}
          >
            QR
          </Button>
        </Stack>
      </CardActions>
      <Collapse in={qrOpen}>
        {content && !expired ? (
          <Box sx={{ p: 2, display: "flex", flexDirection: "column", alignItems: "center", gap: 1 }}>
            <QRCodeSVG value={content} size={168} level="M" />
            <Typography
              variant="caption"
              color={remainingMs != null && remainingMs <= 30_000 ? "error" : "text.secondary"}
            >
              {remainingMs != null ? `Share link expires in ${formatRemaining(remainingMs)}` : null}
            </Typography>
          </Box>
        ) : content && expired ? (
          <Box sx={{ p: 2, display: "flex", flexDirection: "column", alignItems: "center", gap: 1 }}>
            <Typography variant="body2" color="error">
              Share link expired.
            </Typography>
            <Button size="small" variant="outlined" startIcon={<RefreshIcon />} onClick={load}>
              Generate new code
            </Button>
          </Box>
        ) : null}
      </Collapse>
      {qrError ? (
        <Typography variant="caption" color="error" sx={{ px: 2, pb: 1, display: "block" }}>
          Could not generate QR code.
        </Typography>
      ) : null}
    </Card>
  );
}
