-- Connection log retention: the periodic purge targets closed rows (disconnected_at set);
-- index the column so the DELETE on a large history stays fast.

CREATE INDEX idx_connection_logs_disconnected_at ON connection_logs (disconnected_at);
