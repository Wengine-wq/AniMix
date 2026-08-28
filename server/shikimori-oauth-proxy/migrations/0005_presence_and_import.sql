ALTER TABLE users ADD COLUMN last_seen_at INTEGER;

CREATE INDEX IF NOT EXISTS user_anime_entries_user_updated_idx
  ON user_anime_entries(user_id, updated_at DESC);
