CREATE INDEX IF NOT EXISTS sessions_user_active_idx
  ON sessions(user_id, revoked_at, refresh_expires_at DESC);

CREATE INDEX IF NOT EXISTS sessions_expiry_idx
  ON sessions(refresh_expires_at, revoked_at);
