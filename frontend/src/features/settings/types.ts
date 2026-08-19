import type { ServerConfigForm } from "@/features/settings/knownSettings";

export const ADVANCED_KEY_PATTERN = /^[a-zA-Z0-9_.-]{1,64}$/;

/** Dialog state for the typed "server defaults" editor. `key` is empty until a setting is chosen. */
export interface DefaultDialog {
  key: string;
  value: string;
  isNew: boolean;
  /** Structured form state for `serverConfig`-typed settings (e.g. `network`). */
  config?: ServerConfigForm;
}

/** Dialog state for the raw JSON advanced editor. */
export interface AdvancedDialog {
  key: string;
  value: string;
  isNew: boolean;
}

export function parseRoutes(value: string): string[] {
  return value
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}
