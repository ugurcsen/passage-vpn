/** Compact clock label for the x-axis: "3:04 PM", no seconds. */
export function formatTime(value: number | Date) {
  return new Date(value).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

/** Always-formatted rate (used by chart ticks and tooltips; 0 renders as "0 B/s"). */
export function formatRateLabel(bytesPerSec: number) {
  if (bytesPerSec < 1024) return `${bytesPerSec.toFixed(0)} B/s`;
  if (bytesPerSec < 1024 ** 2) return `${(bytesPerSec / 1024).toFixed(1)} KB/s`;
  return `${(bytesPerSec / 1024 ** 2).toFixed(2)} MB/s`;
}

/** Rate with an explicit "no data" marker (used by the live chips). */
export function formatRate(bytesPerSec: number | null | undefined) {
  if (bytesPerSec === null || bytesPerSec === undefined || !Number.isFinite(bytesPerSec) || bytesPerSec <= 0) {
    return "—";
  }
  return formatRateLabel(bytesPerSec);
}

export function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return "—";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
  return `${(bytes / 1024 ** i).toFixed(1)} ${units[i]}`;
}

export function formatSince(iso: string) {
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${minutes % 60}m ago`;
}
