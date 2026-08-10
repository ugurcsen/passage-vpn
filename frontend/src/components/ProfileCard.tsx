import { useState } from "react";
import {
  Box,
  Button,
  Card,
  CardActions,
  CardContent,
  Collapse,
  CircularProgress,
  Stack,
  Typography,
} from "@mui/material";
import DownloadIcon from "@mui/icons-material/Download";
import QrCode2Icon from "@mui/icons-material/QrCode2";
import { QRCodeSVG } from "qrcode.react";
import { downloadOvpn, type OvpnFile } from "@/lib/api";

interface ProfileCardProps {
  title: string;
  subtitle: string;
  disabled?: boolean;
  fetch: () => Promise<OvpnFile>;
  /** When provided, the QR code encodes this payload (e.g. OpenVPN Connect import XML)
   *  instead of the full profile content, which exceeds QR capacity. */
  qrFetch?: () => Promise<string>;
}

/** A profile tile with download and QR-code share actions. */
export function ProfileCard({ title, subtitle, disabled, fetch, qrFetch }: ProfileCardProps) {
  const [qrOpen, setQrOpen] = useState(false);
  const [content, setContent] = useState<string | null>(null);
  const [qrLoading, setQrLoading] = useState(false);
  const [qrError, setQrError] = useState(false);

  const download = async () => {
    try {
      downloadOvpn(await fetch());
    } catch {
      /* toast handled by parent when provided; silent here */
    }
  };

  const toggleQr = async () => {
    if (qrOpen) {
      setQrOpen(false);
      return;
    }
    setQrLoading(true);
    setQrError(false);
    try {
      setContent(qrFetch ? await qrFetch() : (await fetch()).content);
      setQrOpen(true);
    } catch {
      setQrError(true);
    } finally {
      setQrLoading(false);
    }
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
        <Box sx={{ p: 2, display: "flex", justifyContent: "center" }}>
          {content ? <QRCodeSVG value={content} size={168} level="M" /> : null}
        </Box>
      </Collapse>
      {qrError ? (
        <Typography variant="caption" color="error" sx={{ px: 2, pb: 1, display: "block" }}>
          Could not generate QR code.
        </Typography>
      ) : null}
    </Card>
  );
}
