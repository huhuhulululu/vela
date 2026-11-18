-- Vela sync blob store. Byte-level columns follow docs/plans/sync.md section 10.2.
-- Every connection must also run (set in app.py; not all of these persist):
--   PRAGMA journal_mode=WAL;
--   PRAGMA synchronous=FULL;
--   PRAGMA busy_timeout=5000;

CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS blobs (
    id TEXT PRIMARY KEY CHECK(length(id)=64),
    ver INTEGER NOT NULL,
    pub BLOB NOT NULL CHECK(length(pub)=65),
    data TEXT NOT NULL,
    size INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS deleted (
    id TEXT PRIMARY KEY CHECK(length(id)=64),
    deleted_at INTEGER NOT NULL,
    last_epoch TEXT NOT NULL,
    last_ver INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS creations (
    ts INTEGER NOT NULL,
    ip_bucket TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS creations_ts ON creations(ts);
