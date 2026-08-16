-- PostgreSQL variant: same schema as the SQLite migration (V21).
-- Per-daemon profile pinning: share links (and QR codes) can be bound to a
-- specific OpenVPN daemon so the downloaded profile connects to the chosen
-- instance (e.g. full-tunnel vs split-tunnel). Null = legacy behavior
-- (multi-remote / first matching daemon).

ALTER TABLE profile_tokens ADD COLUMN daemon_index INTEGER;
