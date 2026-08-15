import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { api, apiPublic, endpoints, tokenStore } from "@/lib/api";

export type Role = "ADMIN" | "GROUP_ADMIN" | "USER";

export interface CurrentUser {
  id: string;
  username: string;
  fullName?: string;
  email?: string;
  role: Role;
  mfaEnabled: boolean;
  mfaRequired?: boolean;
  banned: boolean;
  mustChangePassword: boolean;
  groups: string[];
  createdAt?: string;
  lastLoginAt?: string;
}

export interface LoginResult {
  accessToken: string | null;
  refreshToken: string | null;
  mfaRequired: boolean;
  mustEnrollMfa?: boolean;
  preAuthToken?: string;
}

interface AuthContextValue {
  user: CurrentUser | null;
  loading: boolean;
  login: (username: string, password: string) => Promise<LoginResult>;
  submitMfa: (preAuthToken: string, code: string) => Promise<void>;
  submitMfaEnroll: (preAuthToken: string, code: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshMe: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

async function loadMe(): Promise<CurrentUser | null> {
  if (!tokenStore.access) return null;
  try {
    return await api<CurrentUser>(endpoints.me);
  } catch {
    tokenStore.clear();
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadMe()
      .then(setUser)
      .finally(() => setLoading(false));
  }, []);

  const refreshMe = useCallback(async () => {
    const me = await loadMe();
    setUser(me);
    if (!me) tokenStore.clear();
  }, []);

  const login = useCallback(
    async (username: string, password: string) => {
      const res = await apiPublic<LoginResult>(endpoints.login, {
        method: "POST",
        body: JSON.stringify({ username, password }),
      });
      if (!res.mfaRequired && res.accessToken) {
        tokenStore.set(res.accessToken, res.refreshToken);
        setUser(await loadMe());
      }
      return res;
    },
    [],
  );

  const submitMfa = useCallback(async (preAuthToken: string, code: string) => {
    const res = await apiPublic<LoginResult>(endpoints.mfa, {
      method: "POST",
      body: JSON.stringify({ preAuthToken, code }),
    });
    tokenStore.set(res.accessToken, res.refreshToken);
    setUser(await loadMe());
  }, []);

  const submitMfaEnroll = useCallback(async (preAuthToken: string, code: string) => {
    const res = await apiPublic<LoginResult>(endpoints.mfaEnrollConfirm, {
      method: "POST",
      body: JSON.stringify({ preAuthToken, code }),
    });
    tokenStore.set(res.accessToken, res.refreshToken);
    setUser(await loadMe());
  }, []);

  const logout = useCallback(async () => {
    const refresh = tokenStore.refresh;
    try {
      if (refresh) {
        await apiPublic(endpoints.logout, {
          method: "POST",
          body: JSON.stringify({ refreshToken: refresh }),
        });
      }
    } finally {
      tokenStore.clear();
      setUser(null);
    }
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ user, loading, login, submitMfa, submitMfaEnroll, logout, refreshMe }),
    [user, loading, login, submitMfa, submitMfaEnroll, logout, refreshMe],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
