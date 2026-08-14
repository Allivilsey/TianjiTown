ALTER TABLE town_applications ADD COLUMN application_fee_minor INTEGER NOT NULL DEFAULT 0
    CHECK (application_fee_minor >= 0);

-- 旧版本允许任意税率；升级时统一纠正到新的最低合法档位。
UPDATE towns
   SET tax_rate_bps = 500
 WHERE tax_rate_bps < 500 OR tax_rate_bps > 2500 OR tax_rate_bps % 500 <> 0;

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

CREATE INDEX ix_application_initial_members_player
    ON application_initial_members (player_uuid, confirmation_status);

CREATE TRIGGER tr_application_initial_members_updated_at
AFTER UPDATE ON application_initial_members
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE application_initial_members
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE application_id = NEW.application_id AND player_uuid = NEW.player_uuid;
END;

-- 扩张网格由 3×3 放宽为固定 5×5；重建相关表以替换 SQLite CHECK 约束。
DROP TRIGGER tr_territory_units_updated_at;
DROP TRIGGER tr_territory_expansions_updated_at;
DROP INDEX ix_territory_world_center;
DROP INDEX ix_territory_chunks_unit;
DROP INDEX uq_territory_residence_origin;
DROP INDEX uq_territory_residence_area;
DROP INDEX ix_territory_expansions_recovery;

ALTER TABLE territory_expansions RENAME TO territory_expansions_phase6;
ALTER TABLE territory_chunks RENAME TO territory_chunks_phase6;
ALTER TABLE territory_units RENAME TO territory_units_phase6;

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
    CONSTRAINT uq_territory_phase7_grid UNIQUE (town_id, grid_x, grid_z),
    CONSTRAINT fk_territory_phase7_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

INSERT INTO territory_units
    (unit_id, town_id, world_uuid, world_name, grid_x, grid_z, center_chunk_x,
     center_chunk_z, residence_name, residence_area_name, projection_status,
     projection_error, reuse_blocked, created_at, updated_at)
SELECT unit_id, town_id, world_uuid, world_name, grid_x, grid_z, center_chunk_x,
       center_chunk_z, residence_name, residence_area_name, projection_status,
       projection_error, reuse_blocked, created_at, updated_at
  FROM territory_units_phase6;

CREATE TABLE territory_chunks (
    unit_id BLOB NOT NULL,
    world_uuid BLOB NOT NULL,
    chunk_x INTEGER NOT NULL,
    chunk_z INTEGER NOT NULL,
    PRIMARY KEY (world_uuid, chunk_x, chunk_z),
    CONSTRAINT fk_territory_chunks_phase7_unit FOREIGN KEY (unit_id)
        REFERENCES territory_units (unit_id) ON DELETE CASCADE
);

INSERT INTO territory_chunks (unit_id, world_uuid, chunk_x, chunk_z)
SELECT unit_id, world_uuid, chunk_x, chunk_z FROM territory_chunks_phase6;

CREATE TABLE territory_expansions (
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
    CONSTRAINT uq_territory_expansion_phase7_business_key UNIQUE (business_key),
    CONSTRAINT uq_territory_expansion_phase7_unit UNIQUE (unit_id),
    CONSTRAINT fk_territory_expansion_phase7_town FOREIGN KEY (town_id) REFERENCES towns (town_id),
    CONSTRAINT fk_territory_expansion_phase7_unit FOREIGN KEY (unit_id)
        REFERENCES territory_units (unit_id)
);

INSERT INTO territory_expansions
    (expansion_id, town_id, unit_id, business_key, actor_uuid, price_minor, status,
     last_error, created_at, updated_at)
SELECT expansion_id, town_id, unit_id, business_key, actor_uuid, price_minor, status,
       last_error, created_at, updated_at
  FROM territory_expansions_phase6;

DROP TABLE territory_expansions_phase6;
DROP TABLE territory_chunks_phase6;
DROP TABLE territory_units_phase6;

CREATE INDEX ix_territory_world_center
    ON territory_units (world_uuid, center_chunk_x, center_chunk_z);
CREATE INDEX ix_territory_chunks_unit ON territory_chunks (unit_id);
CREATE UNIQUE INDEX uq_territory_residence_origin
    ON territory_units (lower(residence_name))
    WHERE grid_x = 0 AND grid_z = 0 AND reuse_blocked = 1;
CREATE UNIQUE INDEX uq_territory_residence_area
    ON territory_units (lower(residence_name), residence_area_name)
    WHERE reuse_blocked = 1;
CREATE INDEX ix_territory_expansions_recovery
    ON territory_expansions (status, updated_at);

CREATE TRIGGER tr_territory_units_updated_at
AFTER UPDATE ON territory_units
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE territory_units
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE unit_id = NEW.unit_id;
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

-- 新账本类型分别记录申请初始资金和与税额等额的服务器补贴。
ALTER TABLE ledger_entries RENAME TO ledger_entries_phase6;

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
    CONSTRAINT uq_ledger_phase7_business_key UNIQUE (business_key),
    CONSTRAINT fk_ledger_phase7_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

INSERT INTO ledger_entries
    (entry_id, town_id, entry_type, amount_minor, balance_after_minor, actor_uuid,
     actor_name, business_key, note, created_at)
SELECT entry_id, town_id, entry_type, amount_minor, balance_after_minor, actor_uuid,
       actor_name, business_key, note, created_at
  FROM ledger_entries_phase6;

DROP TABLE ledger_entries_phase6;
CREATE INDEX ix_ledger_town_time ON ledger_entries (town_id, created_at DESC, entry_id);
CREATE INDEX ix_ledger_created ON ledger_entries (created_at);

CREATE TABLE town_beacon_effects (
    town_id BLOB NOT NULL,
    effect_key TEXT NOT NULL,
    amplifier INTEGER NOT NULL CHECK (amplifier >= 0),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    PRIMARY KEY (town_id, effect_key),
    CONSTRAINT fk_town_beacon_effect_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

CREATE INDEX ix_town_beacon_effects_town ON town_beacon_effects (town_id);
