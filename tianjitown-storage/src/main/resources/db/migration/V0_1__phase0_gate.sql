CREATE TABLE IF NOT EXISTS phase0_installation_gate (
    id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
    verified_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER))
);

INSERT INTO phase0_installation_gate (id) VALUES (1)
ON CONFLICT (id) DO UPDATE SET
    verified_at = CAST(unixepoch('subsec') * 1000 AS INTEGER);
