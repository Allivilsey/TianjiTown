-- SQLite 不能直接放宽 CHECK 约束，因此通过新表原子替换成员表。
CREATE TABLE town_members_phase2 (
    town_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('MAYOR', 'OFFICER', 'MEMBER')),
    joined_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    last_active_at INTEGER,
    rules_revision INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (town_id, player_uuid),
    CONSTRAINT uq_town_members_phase2_player UNIQUE (player_uuid),
    CONSTRAINT fk_town_members_phase2_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

INSERT INTO town_members_phase2
    (town_id, player_uuid, role, joined_at, last_active_at, rules_revision)
SELECT town_id, player_uuid, role, joined_at, COALESCE(last_active_at, joined_at), 1
  FROM town_members;

DROP TABLE town_members;
ALTER TABLE town_members_phase2 RENAME TO town_members;
CREATE INDEX ix_town_members_page ON town_members (town_id, joined_at, player_uuid);
CREATE INDEX ix_town_members_active ON town_members (town_id, last_active_at, joined_at);
CREATE UNIQUE INDEX uq_town_members_single_mayor
    ON town_members (town_id) WHERE role = 'MAYOR';

ALTER TABLE towns ADD COLUMN rules_revision INTEGER NOT NULL DEFAULT 1;
ALTER TABLE towns ADD COLUMN archived_at INTEGER;
ALTER TABLE towns ADD COLUMN archive_reason TEXT;
ALTER TABLE town_join_applications ADD COLUMN rules_revision INTEGER NOT NULL DEFAULT 1;

CREATE TABLE mayor_transfer_requests (
    transfer_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    requested_by BLOB NOT NULL,
    candidate_uuid BLOB NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    pending_town_id BLOB GENERATED ALWAYS AS (
        CASE WHEN status = 'PENDING' THEN town_id ELSE NULL END
    ) STORED,
    expires_at INTEGER NOT NULL,
    decided_at INTEGER,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT uq_mayor_transfer_pending_town UNIQUE (pending_town_id),
    CONSTRAINT fk_mayor_transfer_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

CREATE INDEX ix_mayor_transfer_candidate
    ON mayor_transfer_requests (candidate_uuid, status, expires_at);

CREATE TABLE governance_votes (
    vote_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    vote_type TEXT NOT NULL CHECK (vote_type IN ('KICK_MEMBER', 'REPLACE_MAYOR')),
    subject_uuid BLOB NOT NULL,
    candidate_uuid BLOB,
    created_by BLOB NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('OPEN', 'PASSED', 'REJECTED', 'CANCELLED')),
    open_town_id BLOB GENERATED ALWAYS AS (
        CASE WHEN status = 'OPEN' THEN town_id ELSE NULL END
    ) STORED,
    open_vote_type TEXT GENERATED ALWAYS AS (
        CASE WHEN status = 'OPEN' THEN vote_type ELSE NULL END
    ) STORED,
    eligible_voters INTEGER NOT NULL CHECK (eligible_voters > 0),
    required_yes INTEGER NOT NULL CHECK (required_yes > 0),
    yes_votes INTEGER NOT NULL DEFAULT 0 CHECK (yes_votes >= 0),
    no_votes INTEGER NOT NULL DEFAULT 0 CHECK (no_votes >= 0),
    ends_at INTEGER NOT NULL,
    settled_at INTEGER,
    cancelled_reason TEXT,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT uq_governance_vote_open_kind UNIQUE (open_town_id, open_vote_type),
    CONSTRAINT fk_governance_vote_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

CREATE INDEX ix_governance_votes_due ON governance_votes (status, ends_at);

CREATE TABLE governance_vote_voters (
    vote_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    PRIMARY KEY (vote_id, player_uuid),
    CONSTRAINT fk_vote_voters_vote FOREIGN KEY (vote_id)
        REFERENCES governance_votes (vote_id) ON DELETE CASCADE
);

CREATE TABLE governance_vote_ballots (
    vote_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    approve INTEGER NOT NULL CHECK (approve IN (0, 1)),
    cast_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    PRIMARY KEY (vote_id, player_uuid),
    CONSTRAINT fk_vote_ballots_voter FOREIGN KEY (vote_id, player_uuid)
        REFERENCES governance_vote_voters (vote_id, player_uuid)
);

CREATE TABLE town_archived_members (
    town_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('MAYOR', 'OFFICER', 'MEMBER')),
    joined_at INTEGER NOT NULL,
    last_active_at INTEGER,
    rules_revision INTEGER NOT NULL,
    archived_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    PRIMARY KEY (town_id, player_uuid),
    CONSTRAINT fk_archived_members_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);
