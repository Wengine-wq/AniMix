-- AniMix schema for PostgreSQL: the same tables and keys as the YDB schema
-- (001–003 .yql), so data moves between the two without reshaping.
-- Timestamps stay integer seconds/milliseconds exactly as the API writes them.

CREATE TABLE IF NOT EXISTS users (
  id text PRIMARY KEY,
  email text NOT NULL,
  display_name text NOT NULL,
  email_verified_at bigint NOT NULL,
  created_at bigint NOT NULL,
  updated_at bigint NOT NULL,
  last_seen_at bigint
);

CREATE TABLE IF NOT EXISTS users_by_email (
  email text PRIMARY KEY,
  user_id text NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
  id text PRIMARY KEY,
  user_id text NOT NULL,
  access_token_hash text NOT NULL,
  refresh_token_hash text NOT NULL,
  access_expires_at bigint NOT NULL,
  refresh_expires_at bigint NOT NULL,
  created_at bigint NOT NULL,
  last_used_at bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS access_tokens (
  token_hash text PRIMARY KEY,
  session_id text NOT NULL,
  expires_at bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
  token_hash text PRIMARY KEY,
  session_id text NOT NULL,
  expires_at bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS external_accounts (
  provider text NOT NULL,
  provider_subject text NOT NULL,
  user_id text NOT NULL,
  provider_email text,
  created_at bigint NOT NULL,
  updated_at bigint NOT NULL,
  PRIMARY KEY (provider, provider_subject)
);

CREATE TABLE IF NOT EXISTS external_accounts_by_user (
  user_id text NOT NULL,
  provider text NOT NULL,
  provider_subject text NOT NULL,
  PRIMARY KEY (user_id, provider)
);

CREATE TABLE IF NOT EXISTS oauth_transactions (
  state_hash text PRIMARY KEY,
  return_uri text NOT NULL,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_tickets (
  ticket_hash text PRIMARY KEY,
  state_hash text NOT NULL,
  user_id text NOT NULL,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL,
  consumed_at bigint
);

CREATE TABLE IF NOT EXISTS user_anime_entries (
  user_id text NOT NULL,
  shikimori_id bigint NOT NULL,
  status text NOT NULL,
  score bigint NOT NULL,
  episodes_watched bigint NOT NULL,
  updated_at bigint NOT NULL,
  PRIMARY KEY (user_id, shikimori_id)
);

CREATE TABLE IF NOT EXISTS user_history (
  user_id text NOT NULL,
  created_at bigint NOT NULL,
  id text NOT NULL,
  action text NOT NULL,
  shikimori_id bigint,
  metadata_json text,
  PRIMARY KEY (user_id, created_at, id)
);

CREATE TABLE IF NOT EXISTS user_settings (
  user_id text PRIMARY KEY,
  accent_color text,
  theme_mode text NOT NULL,
  smart_connection_enabled boolean NOT NULL,
  updated_at bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS profile_media (
  user_id text NOT NULL,
  kind text NOT NULL,
  object_key text NOT NULL,
  content_type text NOT NULL,
  updated_at bigint NOT NULL,
  PRIMARY KEY (user_id, kind)
);

CREATE TABLE IF NOT EXISTS user_privacy (
  user_id text PRIMARY KEY,
  library_visible boolean NOT NULL,
  updated_at bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS user_friend_edges (
  user_id text NOT NULL,
  peer_id text NOT NULL,
  status text NOT NULL,
  updated_at bigint NOT NULL,
  PRIMARY KEY (user_id, peer_id)
);

CREATE TABLE IF NOT EXISTS user_achievements (
  user_id text NOT NULL,
  achievement_id text NOT NULL,
  unlocked_at bigint NOT NULL,
  PRIMARY KEY (user_id, achievement_id)
);
