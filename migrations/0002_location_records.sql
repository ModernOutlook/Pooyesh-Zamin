CREATE TABLE IF NOT EXISTS location_records (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  client_id TEXT NOT NULL,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  accuracy_m REAL,
  altitude_m REAL,
  captured_at TEXT NOT NULL,
  image_key TEXT,
  image_mime TEXT,
  image_kind TEXT NOT NULL DEFAULT 'none',
  image_source TEXT NOT NULL DEFAULT 'none',
  notes TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE(user_id, client_id)
);
CREATE INDEX IF NOT EXISTS idx_location_user_created ON location_records(user_id, created_at);
