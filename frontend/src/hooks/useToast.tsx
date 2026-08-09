import { createContext, useCallback, useContext, useState, type ReactNode } from "react";
import { Alert, Snackbar } from "@mui/material";

type Severity = "success" | "error" | "info" | "warning";

interface ToastOptions {
  severity?: Severity;
  autoHideDuration?: number;
}

interface ToastContextValue {
  toast: (message: string, options?: ToastOptions) => void;
  success: (message: string) => void;
  error: (message: string) => void;
  info: (message: string) => void;
  warning: (message: string) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

interface ToastState {
  message: string;
  severity: Severity;
  autoHideDuration: number;
  key: number;
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<ToastState | null>(null);

  const show = useCallback((message: string, options: ToastOptions = {}) => {
    setToast({
      message,
      severity: options.severity ?? "info",
      autoHideDuration: options.autoHideDuration ?? 4000,
      key: Date.now(),
    });
  }, []);

  const value: ToastContextValue = {
    toast: show,
    success: (m) => show(m, { severity: "success" }),
    error: (m) => show(m, { severity: "error", autoHideDuration: 6000 }),
    info: (m) => show(m, { severity: "info" }),
    warning: (m) => show(m, { severity: "warning" }),
  };

  return (
    <ToastContext.Provider value={value}>
      {children}
      {toast && (
        <Snackbar
          key={toast.key}
          open
          autoHideDuration={toast.autoHideDuration}
          onClose={() => setToast(null)}
          anchorOrigin={{ vertical: "bottom", horizontal: "right" }}
        >
          <Alert
            onClose={() => setToast(null)}
            severity={toast.severity}
            variant="filled"
            sx={{ minWidth: 260 }}
          >
            {toast.message}
          </Alert>
        </Snackbar>
      )}
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast must be used within ToastProvider");
  return ctx;
}
