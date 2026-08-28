CREATE TABLE IF NOT EXISTS user_history (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  action TEXT NOT NULL,
  shikimori_id INTEGER,
  metadata_json TEXT,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS user_history_user_created_idx
  ON user_history(user_id, created_at DESC);
