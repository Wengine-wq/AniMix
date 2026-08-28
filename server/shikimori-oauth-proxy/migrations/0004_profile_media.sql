CREATE TABLE IF NOT EXISTS user_profile_media (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('avatar', 'banner')),
  content_type TEXT NOT NULL CHECK (content_type = 'image/jpeg'),
  content BLOB NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, kind)
);
