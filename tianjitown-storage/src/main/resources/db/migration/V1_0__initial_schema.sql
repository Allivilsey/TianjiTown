CREATE TABLE active_buffs (
    buff_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    buff_key TEXT NOT NULL,
    effect_kind TEXT NOT NULL CHECK (effect_kind IN ('POTION', 'ATTRIBUTE')),
    effect_key TEXT NOT NULL,
    effect_operation TEXT NOT NULL,
    level INTEGER NOT NULL CHECK (level >= 1),
    stack_count INTEGER NOT NULL CHECK (stack_count >= 1),
    amount_per_level REAL NOT NULL,
    allowed_worlds TEXT NOT NULL,
    price_minor INTEGER NOT NULL CHECK (price_minor > 0),
    purchased_by BLOB,
    purchased_by_name TEXT NOT NULL,
    business_key TEXT NOT NULL,
    starts_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL CHECK (expires_at > starts_at),
    status TEXT NOT NULL CHECK (status IN (
        'ACTIVE', 'SUPERSEDED', 'EXPIRED', 'CANCELLED'
    )),
    last_error TEXT,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT uq_active_buff_business_key UNIQUE (business_key),
    CONSTRAINT fk_active_buff_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);
CREATE TABLE application_initial_members (
    application_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    confirmation_status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (confirmation_status IN ('PENDING', 'CONFIRMED', 'REJECTED')),
    responded_at INTEGER,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    PRIMARY KEY (application_id, player_uuid),
    CONSTRAINT fk_application_initial_member_application FOREIGN KEY (application_id)
        REFERENCES town_applications (application_id) ON DELETE CASCADE
);
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
CREATE TABLE building_refund_weekly (
    town_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    week_start TEXT NOT NULL,
    refund_count INTEGER NOT NULL DEFAULT 0 CHECK (refund_count >= 0),
    last_material_key TEXT NOT NULL,
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    PRIMARY KEY (town_id, player_uuid, week_start),
    CONSTRAINT fk_building_refund_weekly_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);
CREATE TABLE economy_operations (
    operation_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    operation_type TEXT NOT NULL CHECK (operation_type IN ('DONATION', 'ADMIN_ADJUSTMENT')),
    business_key TEXT NOT NULL,
    amount_minor INTEGER NOT NULL CHECK (amount_minor <> 0),
    actor_uuid BLOB,
    actor_name TEXT NOT NULL,
    note TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN (
        'PREPARED', 'EXTERNAL_APPLIED', 'COMPLETED', 'CANCELLED', 'COMPENSATION_REQUIRED'
    )),
    last_error TEXT,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT uq_economy_operation_business_key UNIQUE (business_key),
    CONSTRAINT fk_economy_operation_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);
CREATE TABLE external_income_tax_records (
    tax_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    business_key TEXT NOT NULL UNIQUE,
    source TEXT NOT NULL CHECK (source IN ('JOBS', 'GLOBALMARKETPLUS')),
    receiver_uuid BLOB NOT NULL,
    gross_minor INTEGER NOT NULL CHECK (gross_minor > 0),
    tax_rate_bps INTEGER NOT NULL CHECK (tax_rate_bps BETWEEN 0 AND 9999),
    tax_minor INTEGER NOT NULL CHECK (tax_minor > 0),
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT fk_external_income_tax_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
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
CREATE TABLE governance_vote_voters (
    vote_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    PRIMARY KEY (vote_id, player_uuid),
    CONSTRAINT fk_vote_voters_vote FOREIGN KEY (vote_id)
        REFERENCES governance_votes (vote_id) ON DELETE CASCADE
);
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
CREATE TABLE ledger_entries (
    entry_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    entry_type TEXT NOT NULL CHECK (entry_type IN (
        'QUICKSHOP_TAX', 'JOBS_TAX', 'GLOBALMARKETPLUS_TAX', 'SERVER_TAX_SUBSIDY',
        'APPLICATION_FEE', 'DONATION', 'EXPANSION', 'EXPANSION_REFUND',
        'ADMIN_ADJUSTMENT', 'BUFF_PURCHASE', 'BUFF_REFUND',
        'RESOURCE_PURCHASE', 'RESOURCE_REFUND'
    )),
    amount_minor INTEGER NOT NULL CHECK (amount_minor <> 0),
    balance_after_minor INTEGER NOT NULL CHECK (balance_after_minor >= 0),
    actor_uuid BLOB,
    actor_name TEXT NOT NULL,
    business_key TEXT NOT NULL,
    note TEXT NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT uq_ledger_business_key UNIQUE (business_key),
    CONSTRAINT fk_ledger_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);
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
CREATE TABLE quickshop_tax_records (
    receiver_name TEXT NOT NULL DEFAULT '',
    tax_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    business_key TEXT NOT NULL,
    shop_id INTEGER NOT NULL,
    shop_type TEXT NOT NULL CHECK (shop_type IN ('SELLING', 'BUYING')),
    receiver_uuid BLOB NOT NULL,
    interacting_uuid BLOB NOT NULL,
    gross_minor INTEGER NOT NULL CHECK (gross_minor > 0),
    tax_rate_bps INTEGER NOT NULL CHECK (tax_rate_bps >= 0 AND tax_rate_bps < 10000),
    tax_minor INTEGER NOT NULL CHECK (tax_minor > 0),
    world_name TEXT NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT uq_quickshop_tax_business_key UNIQUE (business_key),
    CONSTRAINT fk_quickshop_tax_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);
CREATE TABLE resource_orders (
    order_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    buyer_uuid BLOB NOT NULL,
    buyer_name TEXT NOT NULL,
    resource_key TEXT NOT NULL,
    resource_name TEXT NOT NULL,
    material_key TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    total_minor INTEGER NOT NULL CHECK (total_minor > 0),
    business_key TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN (
        'PENDING', 'CLAIMING', 'CLAIMED', 'REFUND_REQUIRED', 'REFUNDED'
    )),
    claim_token BLOB,
    claim_started_at INTEGER,
    claimed_at INTEGER,
    last_error TEXT,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT uq_resource_order_business_key UNIQUE (business_key),
    CONSTRAINT fk_resource_order_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);
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
CREATE TABLE territory_chunks (
    unit_id BLOB NOT NULL,
    world_uuid BLOB NOT NULL,
    chunk_x INTEGER NOT NULL,
    chunk_z INTEGER NOT NULL,
    PRIMARY KEY (world_uuid, chunk_x, chunk_z),
    CONSTRAINT fk_territory_chunks_unit FOREIGN KEY (unit_id)
        REFERENCES territory_units (unit_id) ON DELETE CASCADE
);
CREATE TABLE territory_expansions (
    batch_id BLOB
    REFERENCES territory_expansion_batches (batch_id),
    expansion_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    unit_id BLOB NOT NULL,
    business_key TEXT NOT NULL,
    actor_uuid BLOB NOT NULL,
    price_minor INTEGER NOT NULL CHECK (price_minor > 0),
    status TEXT NOT NULL CHECK (status IN (
        'PREPARED', 'COMPLETED', 'REFUNDED', 'COMPENSATION_REQUIRED'
    )),
    last_error TEXT,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT uq_territory_expansion_business_key UNIQUE (business_key),
    CONSTRAINT uq_territory_expansion_unit UNIQUE (unit_id),
    CONSTRAINT fk_territory_expansion_town FOREIGN KEY (town_id) REFERENCES towns (town_id),
    CONSTRAINT fk_territory_expansion_unit FOREIGN KEY (unit_id)
        REFERENCES territory_units (unit_id)
);
CREATE TABLE territory_units (
    unit_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    world_uuid BLOB NOT NULL,
    world_name TEXT NOT NULL,
    grid_x INTEGER NOT NULL CHECK (grid_x BETWEEN -2 AND 2),
    grid_z INTEGER NOT NULL CHECK (grid_z BETWEEN -2 AND 2),
    center_chunk_x INTEGER NOT NULL,
    center_chunk_z INTEGER NOT NULL,
    residence_name TEXT NOT NULL,
    residence_area_name TEXT NOT NULL DEFAULT 'main',
    projection_status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (projection_status IN ('PENDING', 'ACTIVE', 'FAILED')),
    projection_error TEXT,
    reuse_blocked INTEGER NOT NULL DEFAULT 1 CHECK (reuse_blocked IN (0, 1)),
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT uq_territory_grid UNIQUE (town_id, grid_x, grid_z),
    CONSTRAINT fk_territory_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);
CREATE TABLE town_accounts (
    town_id BLOB NOT NULL PRIMARY KEY,
    balance_minor INTEGER NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
    locked INTEGER NOT NULL DEFAULT 0 CHECK (locked IN (0, 1)),
    lock_reason TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT fk_town_accounts_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);
CREATE TABLE town_applications (
    application_fee_status TEXT NOT NULL DEFAULT 'UNPAID'
    CHECK (application_fee_status IN (
        'UNPAID', 'ESCROWED', 'CONSUMED', 'REFUND_PENDING', 'REFUNDED'
    )),
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
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)), application_fee_minor INTEGER NOT NULL DEFAULT 0
    CHECK (application_fee_minor >= 0),
    CONSTRAINT uq_applications_active_applicant UNIQUE (active_applicant),
    CONSTRAINT uq_applications_active_name UNIQUE (active_name),
    CONSTRAINT uq_applications_active_short_name UNIQUE (active_short_name),
    CONSTRAINT uq_applications_active_residence_name UNIQUE (active_residence_name),
    CONSTRAINT fk_applications_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);
CREATE TABLE "town_archived_members" (
    town_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('MAYOR', 'DEPUTY_MAYOR', 'MEMBER')),
    joined_at INTEGER NOT NULL,
    last_active_at INTEGER,
    rules_revision INTEGER NOT NULL,
    archived_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    PRIMARY KEY (town_id, player_uuid),
    CONSTRAINT fk_archived_members_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);
CREATE TABLE town_beacon_effects (
    town_id BLOB NOT NULL,
    effect_key TEXT NOT NULL,
    amplifier INTEGER NOT NULL CHECK (amplifier >= 0),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    PRIMARY KEY (town_id, effect_key),
    CONSTRAINT fk_town_beacon_effect_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);
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
CREATE TABLE town_join_applications (
    join_application_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    applicant_uuid BLOB NOT NULL,
    status TEXT NOT NULL CHECK (status IN (
        'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED'
    )),
    active_town_id BLOB GENERATED ALWAYS AS (
        CASE WHEN status = 'PENDING' THEN town_id ELSE NULL END
    ) STORED,
    active_applicant_uuid BLOB GENERATED ALWAYS AS (
        CASE WHEN status = 'PENDING' THEN applicant_uuid ELSE NULL END
    ) STORED,
    expires_at INTEGER NOT NULL,
    decided_by BLOB,
    decided_at INTEGER,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)), rules_revision INTEGER NOT NULL DEFAULT 1,
    CONSTRAINT uq_join_application_active_town_player
        UNIQUE (active_town_id, active_applicant_uuid),
    CONSTRAINT fk_join_applications_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);
CREATE TABLE town_member_departures (
    departure_id INTEGER PRIMARY KEY AUTOINCREMENT,
    town_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    departure_type TEXT NOT NULL CHECK (departure_type IN ('VOLUNTARY', 'REMOVED')),
    departed_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT fk_member_departures_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);
CREATE TABLE "town_members" (
    town_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('MAYOR', 'DEPUTY_MAYOR', 'MEMBER')),
    joined_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    last_active_at INTEGER,
    rules_revision INTEGER NOT NULL DEFAULT 1,
    accepted_tax_revision INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (town_id, player_uuid),
    CONSTRAINT uq_town_members_player UNIQUE (player_uuid),
    CONSTRAINT fk_town_members_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);
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
CREATE TABLE town_visitors (
    town_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    invited_by BLOB NOT NULL,
    added_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    PRIMARY KEY (town_id, player_uuid),
    CONSTRAINT fk_town_visitors_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);
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
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)), rules_revision INTEGER NOT NULL DEFAULT 1, archived_at INTEGER, archive_reason TEXT, tax_rate_bps INTEGER NOT NULL DEFAULT 500
    CHECK (tax_rate_bps >= 0 AND tax_rate_bps < 10000), tax_revision INTEGER NOT NULL DEFAULT 1,
    CONSTRAINT uq_towns_reserved_normalized_name UNIQUE (reserved_normalized_name),
    CONSTRAINT uq_towns_reserved_normalized_short_name UNIQUE (reserved_normalized_short_name)
);
CREATE INDEX ix_active_buffs_expiry ON active_buffs (status, expires_at);
CREATE INDEX ix_active_buffs_town ON active_buffs (town_id, status, expires_at);
CREATE INDEX ix_application_initial_members_player
    ON application_initial_members (player_uuid, confirmation_status);
CREATE INDEX ix_applications_review_queue ON town_applications (status, submitted_at);
CREATE INDEX ix_audit_created ON audit_logs (created_at);
CREATE INDEX ix_audit_target ON audit_logs (target_type, target_id, created_at);
CREATE INDEX ix_building_refund_cleanup ON building_refund_weekly (week_start);
CREATE INDEX ix_building_refund_player_week
    ON building_refund_weekly (player_uuid, week_start, refund_count);
CREATE INDEX ix_economy_operations_recovery
    ON economy_operations (status, updated_at);
CREATE INDEX ix_external_income_tax_source_created
    ON external_income_tax_records (source, created_at);
CREATE INDEX ix_external_income_tax_town_created
    ON external_income_tax_records (town_id, created_at);
CREATE INDEX ix_governance_votes_due ON governance_votes (status, ends_at);
CREATE INDEX ix_invitations_player
    ON town_invitations (player_uuid, accepted_at, revoked_at, expires_at);
CREATE INDEX ix_join_applications_applicant
    ON town_join_applications (applicant_uuid, status, expires_at);
CREATE INDEX ix_join_applications_town
    ON town_join_applications (town_id, status, created_at);
CREATE INDEX ix_ledger_created ON ledger_entries (created_at);
CREATE INDEX ix_ledger_town_time ON ledger_entries (town_id, created_at DESC, entry_id);
CREATE INDEX ix_mayor_transfer_candidate
    ON mayor_transfer_requests (candidate_uuid, status, expires_at);
CREATE INDEX ix_member_departures_player
    ON town_member_departures (player_uuid, departure_type, departed_at);
CREATE INDEX ix_quickshop_tax_town_time
    ON quickshop_tax_records (town_id, created_at DESC, tax_id);
CREATE INDEX ix_reservations_overlap ON site_reservations (
    world_uuid, released_at, expires_at, min_chunk_x, max_chunk_x, min_chunk_z, max_chunk_z
);
CREATE INDEX ix_resource_orders_daily
    ON resource_orders (town_id, resource_key, created_at, status);
CREATE INDEX ix_resource_orders_player_status
    ON resource_orders (buyer_uuid, status, created_at DESC);
CREATE INDEX ix_resource_orders_recovery
    ON resource_orders (status, claim_started_at);
CREATE INDEX ix_reviews_application ON application_reviews (application_id, created_at);
CREATE INDEX ix_territory_chunks_unit ON territory_chunks (unit_id);
CREATE INDEX ix_territory_expansions_recovery
    ON territory_expansions (status, updated_at);
CREATE INDEX ix_territory_world_center
    ON territory_units (world_uuid, center_chunk_x, center_chunk_z);
CREATE INDEX ix_town_beacon_effects_town ON town_beacon_effects (town_id);
CREATE INDEX ix_town_members_active ON town_members (town_id, last_active_at, joined_at);
CREATE INDEX ix_town_members_page ON town_members (town_id, joined_at, player_uuid);
CREATE INDEX ix_town_visitors_page
    ON town_visitors (town_id, added_at, player_uuid);
CREATE INDEX ix_towns_status ON towns (status);
CREATE UNIQUE INDEX uq_active_buff_town_key
    ON active_buffs (town_id, buff_key) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_governance_vote_open_town
    ON governance_votes (open_town_id);
CREATE UNIQUE INDEX uq_territory_residence_area
    ON territory_units (lower(residence_name), residence_area_name)
    WHERE reuse_blocked = 1;
CREATE UNIQUE INDEX uq_territory_residence_origin
    ON territory_units (lower(residence_name))
    WHERE grid_x = 0 AND grid_z = 0 AND reuse_blocked = 1;
CREATE UNIQUE INDEX uq_town_members_single_mayor
    ON town_members (town_id) WHERE role = 'MAYOR';
CREATE TRIGGER tr_active_buffs_updated_at
AFTER UPDATE ON active_buffs
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE active_buffs
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE buff_id = NEW.buff_id;
END;
CREATE TRIGGER tr_application_initial_members_updated_at
AFTER UPDATE ON application_initial_members
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE application_initial_members
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE application_id = NEW.application_id AND player_uuid = NEW.player_uuid;
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
CREATE TRIGGER tr_building_refund_updated_at
AFTER UPDATE ON building_refund_weekly
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE building_refund_weekly
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE town_id = NEW.town_id
       AND player_uuid = NEW.player_uuid
       AND week_start = NEW.week_start;
END;
CREATE TRIGGER tr_economy_operations_updated_at
AFTER UPDATE ON economy_operations
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE economy_operations
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE operation_id = NEW.operation_id;
END;
CREATE TRIGGER tr_join_applications_updated_at
AFTER UPDATE ON town_join_applications
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE town_join_applications
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE join_application_id = NEW.join_application_id;
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
CREATE TRIGGER tr_resource_orders_updated_at
AFTER UPDATE ON resource_orders
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE resource_orders
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE order_id = NEW.order_id;
END;
CREATE TRIGGER tr_territory_expansions_updated_at
AFTER UPDATE ON territory_expansions
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE territory_expansions
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE expansion_id = NEW.expansion_id;
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
CREATE TRIGGER tr_town_accounts_updated_at
AFTER UPDATE ON town_accounts
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE town_accounts
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE town_id = NEW.town_id;
END;
CREATE TRIGGER tr_town_members_deputy_insert_limit
BEFORE INSERT ON town_members
FOR EACH ROW
WHEN NEW.role = 'DEPUTY_MAYOR' AND (
    SELECT COUNT(*) FROM town_members
     WHERE town_id = NEW.town_id AND role = 'DEPUTY_MAYOR'
) >= 3
BEGIN
    SELECT RAISE(ABORT, '每个小镇最多三名副镇长');
END;
CREATE TRIGGER tr_town_members_deputy_update_limit
BEFORE UPDATE OF role, town_id ON town_members
FOR EACH ROW
WHEN NEW.role = 'DEPUTY_MAYOR'
 AND (OLD.role <> 'DEPUTY_MAYOR' OR OLD.town_id <> NEW.town_id) AND (
    SELECT COUNT(*) FROM town_members
     WHERE town_id = NEW.town_id AND role = 'DEPUTY_MAYOR'
) >= 3
BEGIN
    SELECT RAISE(ABORT, '每个小镇最多三名副镇长');
END;
CREATE TRIGGER tr_towns_create_account
AFTER INSERT ON towns
FOR EACH ROW
BEGIN
    INSERT INTO town_accounts (town_id) VALUES (NEW.town_id);
END;
CREATE TRIGGER tr_towns_updated_at
AFTER UPDATE ON towns
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE towns
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE town_id = NEW.town_id;
END;

-- 可保存不完整字段的申请表单草稿；正式申请仍只接收完整且通过校验的资料。
CREATE TABLE application_form_drafts (
    applicant_uuid BLOB NOT NULL PRIMARY KEY,
    application_id BLOB,
    application_version INTEGER NOT NULL DEFAULT 0,
    current_step INTEGER NOT NULL DEFAULT 1 CHECK (current_step BETWEEN 1 AND 3),
    name TEXT NOT NULL DEFAULT '',
    short_name TEXT NOT NULL DEFAULT '',
    residence_name TEXT NOT NULL DEFAULT '',
    description TEXT NOT NULL DEFAULT '',
    rules_text TEXT NOT NULL DEFAULT '',
    member_one_uuid BLOB,
    member_one_name TEXT NOT NULL DEFAULT '',
    member_two_uuid BLOB,
    member_two_name TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT fk_application_form_draft_application FOREIGN KEY (application_id)
        REFERENCES town_applications (application_id) ON DELETE CASCADE
);

CREATE TRIGGER tr_application_form_drafts_updated_at
AFTER UPDATE ON application_form_drafts
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE application_form_drafts
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE applicant_uuid = NEW.applicant_uuid;
END;

-- 建镇费在创建完成前属于托管资金。失败解锁时继续复用，取消时进入退款流程。


-- QuickShop 补贴先在数据库中原子预留，再执行外部清算账户调整。
CREATE TABLE quickshop_subsidy_reservations (
    reservation_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    business_key TEXT NOT NULL UNIQUE,
    requested_minor INTEGER NOT NULL CHECK (requested_minor > 0),
    granted_minor INTEGER NOT NULL CHECK (granted_minor >= 0),
    period_12h_start INTEGER NOT NULL,
    week_start INTEGER NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('RESERVED', 'APPLIED', 'CANCELLED')),
    last_error TEXT,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT fk_quickshop_subsidy_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

CREATE INDEX ix_quickshop_subsidy_12h
    ON quickshop_subsidy_reservations (town_id, period_12h_start, status);
CREATE INDEX ix_quickshop_subsidy_week
    ON quickshop_subsidy_reservations (town_id, week_start, status);

CREATE TRIGGER tr_quickshop_subsidy_updated_at
AFTER UPDATE ON quickshop_subsidy_reservations
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE quickshop_subsidy_reservations
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE reservation_id = NEW.reservation_id;
END;

-- 保留交易发生时的收款玩家名，避免历史账本随缓存变化而丢失身份信息。


-- 批量扩张拥有独立批次状态；一个事务扣款并创建全部单元，一个结果整体完成或退款。
CREATE TABLE territory_expansion_batches (
    batch_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    business_key TEXT NOT NULL UNIQUE,
    actor_uuid BLOB NOT NULL,
    actor_name TEXT NOT NULL,
    total_price_minor INTEGER NOT NULL CHECK (total_price_minor > 0),
    status TEXT NOT NULL CHECK (status IN (
        'PREPARED', 'COMPLETED', 'REFUNDED', 'COMPENSATION_REQUIRED'
    )),
    last_error TEXT,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT fk_territory_expansion_batch_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);


CREATE INDEX ix_territory_expansion_batch ON territory_expansions (batch_id, status);
CREATE INDEX ix_territory_expansion_batch_recovery
    ON territory_expansion_batches (status, updated_at);

CREATE TRIGGER tr_territory_expansion_batches_updated_at
AFTER UPDATE ON territory_expansion_batches
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE territory_expansion_batches
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE batch_id = NEW.batch_id;
END;

-- Events roll back with the underlying membership transaction. No historical backfill.
CREATE TABLE player_change_notifications (
    notification_id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid BLOB NOT NULL,
    town_name TEXT NOT NULL,
    old_role TEXT NOT NULL,
    new_role TEXT NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER))
);
CREATE INDEX ix_player_change_pending ON player_change_notifications(player_uuid, notification_id);

CREATE TRIGGER tr_town_members_remove_visitor
AFTER INSERT ON town_members
FOR EACH ROW
BEGIN
    INSERT INTO player_change_notifications(player_uuid, town_name, old_role, new_role)
    SELECT NEW.player_uuid, name,
           CASE WHEN EXISTS (SELECT 1 FROM town_visitors WHERE town_id = NEW.town_id AND player_uuid = NEW.player_uuid)
                THEN 'VISITOR' ELSE 'NONE' END, NEW.role
      FROM towns WHERE town_id = NEW.town_id;
    DELETE FROM town_visitors WHERE town_id = NEW.town_id AND player_uuid = NEW.player_uuid;
END;

CREATE TRIGGER tr_notify_town_members_delete
AFTER DELETE ON town_members
FOR EACH ROW
BEGIN
    INSERT INTO player_change_notifications(player_uuid, town_name, old_role, new_role)
    SELECT OLD.player_uuid, name, OLD.role, 'NONE' FROM towns WHERE town_id = OLD.town_id;
END;

CREATE TRIGGER tr_notify_town_members_update
AFTER UPDATE OF role ON town_members
FOR EACH ROW WHEN OLD.role <> NEW.role
BEGIN
    INSERT INTO player_change_notifications(player_uuid, town_name, old_role, new_role)
    SELECT NEW.player_uuid, name, OLD.role, NEW.role FROM towns WHERE town_id = NEW.town_id;
END;

CREATE TRIGGER tr_notify_town_visitors_insert
AFTER INSERT ON town_visitors
FOR EACH ROW
BEGIN
    INSERT INTO player_change_notifications(player_uuid, town_name, old_role, new_role)
    SELECT NEW.player_uuid, name, 'NONE', 'VISITOR' FROM towns WHERE town_id = NEW.town_id;
END;

CREATE TRIGGER tr_notify_town_visitors_delete
AFTER DELETE ON town_visitors
FOR EACH ROW WHEN NOT EXISTS (SELECT 1 FROM town_members WHERE town_id = OLD.town_id AND player_uuid = OLD.player_uuid)
BEGIN
    INSERT INTO player_change_notifications(player_uuid, town_name, old_role, new_role)
    SELECT OLD.player_uuid, name, 'VISITOR', 'NONE' FROM towns WHERE town_id = OLD.town_id;
END;
