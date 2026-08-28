PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  avatar_url TEXT,
  banner_url TEXT,
  email_verified_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  access_token_hash TEXT NOT NULL UNIQUE,
  refresh_token_hash TEXT NOT NULL UNIQUE,
  access_expires_at INTEGER NOT NULL,
  refresh_expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  last_used_at INTEGER NOT NULL,
  revoked_at INTEGER
);

CREATE INDEX IF NOT EXISTS sessions_access_idx ON sessions(access_token_hash);
CREATE INDEX IF NOT EXISTS sessions_refresh_idx ON sessions(refresh_token_hash);

CREATE TABLE IF NOT EXISTS verification_codes (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL,
  purpose TEXT NOT NULL,
  code_hash TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  request_ip_hash TEXT,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  consumed_at INTEGER
);

CREATE INDEX IF NOT EXISTS verification_codes_lookup
  ON verification_codes(email, purpose, created_at DESC);
CREATE INDEX IF NOT EXISTS verification_codes_ip_lookup
  ON verification_codes(request_ip_hash, created_at DESC);

CREATE TABLE IF NOT EXISTS user_anime_entries (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  shikimori_id INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'planned',
  score INTEGER NOT NULL DEFAULT 0,
  episodes_watched INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, shikimori_id)
);

CREATE INDEX IF NOT EXISTS user_anime_entries_status_idx
  ON user_anime_entries(user_id, status);

CREATE TABLE IF NOT EXISTS user_settings (
  user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  accent_color TEXT,
  theme_mode TEXT NOT NULL DEFAULT 'system',
  smart_connection_enabled INTEGER NOT NULL DEFAULT 1,
  profile_banner_url TEXT,
  updated_at INTEGER NOT NULL
);
