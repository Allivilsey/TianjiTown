CREATE TABLE towns (
    town_id BLOB NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    short_name TEXT NOT NULL,
    normalized_short_name TEXT NOT NULL,
    description TEXT NOT NULL,
    rules_text TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PROVISIONING', 'ACTIVE', 'ARCHIVED')),
    reuse_blocked INTEGER NOT NULL DEFAULT 1 CHECK (reuse_blocked IN (0, 1)),
    reserved_normalized_name TEXT GENERATED ALWAYS AS (
        CASE WHEN status <> 'ARCHIVED' OR reuse_blocked = 1 THEN normalized_name ELSE NULL END
    ) STORED,
    reserved_normalized_short_name TEXT GENERATED ALWAYS AS (
        CASE WHEN status <> 'ARCHIVED' OR reuse_blocked = 1 THEN normalized_short_name ELSE NULL END
    ) STORED,
    mayor_uuid BLOB NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT uq_towns_reserved_normalized_name UNIQUE (reserved_normalized_name),
    CONSTRAINT uq_towns_reserved_normalized_short_name UNIQUE (reserved_normalized_short_name)
);

CREATE INDEX ix_towns_status ON towns (status);

CREATE TABLE town_members (
    town_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('MAYOR', 'MEMBER')),
    joined_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    last_active_at INTEGER,
    rules_revision INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (town_id, player_uuid),
    CONSTRAINT uq_town_members_player UNIQUE (player_uuid),
    CONSTRAINT fk_town_members_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

CREATE INDEX ix_town_members_page ON town_members (town_id, joined_at, player_uuid);

CREATE TABLE town_applications (
    application_id BLOB NOT NULL PRIMARY KEY,
    applicant_uuid BLOB NOT NULL,
    name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    short_name TEXT NOT NULL,
    normalized_short_name TEXT NOT NULL,
    residence_name TEXT NOT NULL,
    description TEXT NOT NULL,
    rules_text TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN (
        'DRAFT', 'SITE_SELECTED', 'SUBMITTED', 'UNDER_REVIEW', 'NEED_CHANGES',
        'APPROVED_PROVISIONING', 'ACTIVE', 'REJECTED', 'CANCELLED', 'PROVISION_FAILED'
    )),
    active_applicant BLOB GENERATED ALWAYS AS (
        CASE WHEN status IN (
            'DRAFT', 'SITE_SELECTED', 'SUBMITTED', 'UNDER_REVIEW', 'NEED_CHANGES',
            'APPROVED_PROVISIONING', 'PROVISION_FAILED'
        ) THEN applicant_uuid ELSE NULL END
    ) STORED,
    active_name TEXT GENERATED ALWAYS AS (
        CASE WHEN status IN (
            'DRAFT', 'SITE_SELECTED', 'SUBMITTED', 'UNDER_REVIEW', 'NEED_CHANGES',
            'APPROVED_PROVISIONING', 'PROVISION_FAILED'
        ) THEN normalized_name ELSE NULL END
    ) STORED,
    active_short_name TEXT GENERATED ALWAYS AS (
        CASE WHEN status IN (
            'DRAFT', 'SITE_SELECTED', 'SUBMITTED', 'UNDER_REVIEW', 'NEED_CHANGES',
            'APPROVED_PROVISIONING', 'PROVISION_FAILED'
        ) THEN normalized_short_name ELSE NULL END
    ) STORED,
    active_residence_name TEXT GENERATED ALWAYS AS (
        CASE WHEN status IN (
            'DRAFT', 'SITE_SELECTED', 'SUBMITTED', 'UNDER_REVIEW', 'NEED_CHANGES',
            'APPROVED_PROVISIONING', 'PROVISION_FAILED'
        ) THEN lower(residence_name) ELSE NULL END
    ) STORED,
    town_id BLOB,
    review_message TEXT,
    last_error TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    submitted_at INTEGER,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT uq_applications_active_applicant UNIQUE (active_applicant),
    CONSTRAINT uq_applications_active_name UNIQUE (active_name),
    CONSTRAINT uq_applications_active_short_name UNIQUE (active_short_name),
    CONSTRAINT uq_applications_active_residence_name UNIQUE (active_residence_name),
    CONSTRAINT fk_applications_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

CREATE INDEX ix_applications_review_queue ON town_applications (status, submitted_at);

CREATE TABLE application_reviews (
    review_id INTEGER PRIMARY KEY AUTOINCREMENT,
    application_id BLOB NOT NULL,
    reviewer_uuid BLOB NOT NULL,
    action TEXT NOT NULL,
    reason TEXT NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT fk_reviews_application FOREIGN KEY (application_id)
        REFERENCES town_applications (application_id)
);

CREATE INDEX ix_reviews_application ON application_reviews (application_id, created_at);

CREATE TABLE site_reservations (
    reservation_id BLOB NOT NULL PRIMARY KEY,
    application_id BLOB NOT NULL,
    world_uuid BLOB NOT NULL,
    world_name TEXT NOT NULL,
    center_chunk_x INTEGER NOT NULL,
    center_chunk_z INTEGER NOT NULL,
    min_chunk_x INTEGER NOT NULL,
    max_chunk_x INTEGER NOT NULL,
    min_chunk_z INTEGER NOT NULL,
    max_chunk_z INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    released_at INTEGER,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT uq_reservation_application UNIQUE (application_id),
    CONSTRAINT fk_reservations_application FOREIGN KEY (application_id)
        REFERENCES town_applications (application_id)
);

CREATE INDEX ix_reservations_overlap ON site_reservations (
    world_uuid, released_at, expires_at, min_chunk_x, max_chunk_x, min_chunk_z, max_chunk_z
);

CREATE TABLE territory_units (
    unit_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    world_uuid BLOB NOT NULL,
    world_name TEXT NOT NULL,
    grid_x INTEGER NOT NULL,
    grid_z INTEGER NOT NULL,
    center_chunk_x INTEGER NOT NULL,
    center_chunk_z INTEGER NOT NULL,
    residence_name TEXT NOT NULL,
    projection_status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (projection_status IN ('PENDING', 'ACTIVE', 'FAILED')),
    projection_error TEXT,
    reuse_blocked INTEGER NOT NULL DEFAULT 1 CHECK (reuse_blocked IN (0, 1)),
    reserved_residence_name TEXT GENERATED ALWAYS AS (
        CASE WHEN reuse_blocked = 1 THEN residence_name ELSE NULL END
    ) STORED,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT uq_territory_grid UNIQUE (town_id, grid_x, grid_z),
    CONSTRAINT uq_territory_reserved_residence UNIQUE (reserved_residence_name),
    CONSTRAINT fk_territory_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

CREATE INDEX ix_territory_world_center
    ON territory_units (world_uuid, center_chunk_x, center_chunk_z);

CREATE TABLE territory_chunks (
    unit_id BLOB NOT NULL,
    world_uuid BLOB NOT NULL,
    chunk_x INTEGER NOT NULL,
    chunk_z INTEGER NOT NULL,
    PRIMARY KEY (world_uuid, chunk_x, chunk_z),
    CONSTRAINT fk_territory_chunks_unit FOREIGN KEY (unit_id)
        REFERENCES territory_units (unit_id) ON DELETE CASCADE
);

CREATE INDEX ix_territory_chunks_unit ON territory_chunks (unit_id);

CREATE TABLE town_invitations (
    invitation_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    invited_by BLOB NOT NULL,
    expires_at INTEGER NOT NULL,
    accepted_at INTEGER,
    revoked_at INTEGER,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT uq_invitation_town_player UNIQUE (town_id, player_uuid),
    CONSTRAINT fk_invitations_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

CREATE INDEX ix_invitations_player
    ON town_invitations (player_uuid, accepted_at, revoked_at, expires_at);

CREATE TABLE audit_logs (
    audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
    idempotency_key TEXT,
    actor_uuid BLOB,
    actor_name TEXT NOT NULL,
    action TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    detail TEXT NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT uq_audit_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX ix_audit_target ON audit_logs (target_type, target_id, created_at);
CREATE INDEX ix_audit_created ON audit_logs (created_at);

CREATE TABLE town_profile_sync (
    town_id BLOB NOT NULL PRIMARY KEY,
    revision INTEGER NOT NULL DEFAULT 0,
    checksum TEXT,
    sync_status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (sync_status IN ('PENDING', 'SYNCED', 'FAILED', 'MANUAL_CHANGE')),
    last_error TEXT,
    exported_at INTEGER,
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT fk_profile_sync_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

CREATE TRIGGER tr_towns_updated_at
AFTER UPDATE ON towns
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE towns
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE town_id = NEW.town_id;
END;

CREATE TRIGGER tr_applications_updated_at
AFTER UPDATE ON town_applications
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE town_applications
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE application_id = NEW.application_id;
END;

CREATE TRIGGER tr_territory_units_updated_at
AFTER UPDATE ON territory_units
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE territory_units
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE unit_id = NEW.unit_id;
END;

CREATE TRIGGER tr_profile_sync_updated_at
AFTER UPDATE ON town_profile_sync
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE town_profile_sync
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE town_id = NEW.town_id;
END;
