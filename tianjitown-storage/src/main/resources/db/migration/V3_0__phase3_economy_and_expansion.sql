ALTER TABLE towns ADD COLUMN tax_rate_bps INTEGER NOT NULL DEFAULT 500
    CHECK (tax_rate_bps >= 0 AND tax_rate_bps < 10000);
ALTER TABLE towns ADD COLUMN tax_revision INTEGER NOT NULL DEFAULT 1;
ALTER TABLE town_members ADD COLUMN accepted_tax_revision INTEGER NOT NULL DEFAULT 1;

CREATE TABLE town_accounts (
    town_id BLOB NOT NULL PRIMARY KEY,
    balance_minor INTEGER NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
    locked INTEGER NOT NULL DEFAULT 0 CHECK (locked IN (0, 1)),
    lock_reason TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT fk_town_accounts_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

INSERT INTO town_accounts (town_id)
SELECT town_id FROM towns;

CREATE TRIGGER tr_towns_create_account
AFTER INSERT ON towns
FOR EACH ROW
BEGIN
    INSERT INTO town_accounts (town_id) VALUES (NEW.town_id);
END;

CREATE TABLE ledger_entries (
    entry_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    entry_type TEXT NOT NULL CHECK (entry_type IN (
        'QUICKSHOP_TAX', 'DONATION', 'EXPANSION', 'EXPANSION_REFUND', 'ADMIN_ADJUSTMENT'
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

CREATE INDEX ix_ledger_town_time ON ledger_entries (town_id, created_at DESC, entry_id);
CREATE INDEX ix_ledger_created ON ledger_entries (created_at);

CREATE TABLE quickshop_tax_records (
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

CREATE INDEX ix_quickshop_tax_town_time
    ON quickshop_tax_records (town_id, created_at DESC, tax_id);

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

CREATE INDEX ix_economy_operations_recovery
    ON economy_operations (status, updated_at);

-- 阶段 1 将 Residence 名称设为全局唯一；阶段 3 改为一个 Residence 对应多个网格区域。
DROP TRIGGER tr_territory_units_updated_at;

CREATE TABLE territory_units_phase3 (
    unit_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    world_uuid BLOB NOT NULL,
    world_name TEXT NOT NULL,
    grid_x INTEGER NOT NULL CHECK (grid_x BETWEEN -1 AND 1),
    grid_z INTEGER NOT NULL CHECK (grid_z BETWEEN -1 AND 1),
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
    CONSTRAINT uq_territory_phase3_grid UNIQUE (town_id, grid_x, grid_z),
    CONSTRAINT fk_territory_phase3_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

INSERT INTO territory_units_phase3
    (unit_id, town_id, world_uuid, world_name, grid_x, grid_z, center_chunk_x,
     center_chunk_z, residence_name, residence_area_name, projection_status,
     projection_error, reuse_blocked, created_at, updated_at)
SELECT unit_id, town_id, world_uuid, world_name, grid_x, grid_z, center_chunk_x,
       center_chunk_z, residence_name, 'main', projection_status,
       projection_error, reuse_blocked, created_at, updated_at
  FROM territory_units;

CREATE TABLE territory_chunks_phase3 (
    unit_id BLOB NOT NULL,
    world_uuid BLOB NOT NULL,
    chunk_x INTEGER NOT NULL,
    chunk_z INTEGER NOT NULL,
    PRIMARY KEY (world_uuid, chunk_x, chunk_z),
    CONSTRAINT fk_territory_chunks_phase3_unit FOREIGN KEY (unit_id)
        REFERENCES territory_units_phase3 (unit_id) ON DELETE CASCADE
);

INSERT INTO territory_chunks_phase3 (unit_id, world_uuid, chunk_x, chunk_z)
SELECT unit_id, world_uuid, chunk_x, chunk_z FROM territory_chunks;

DROP TABLE territory_chunks;
DROP TABLE territory_units;
ALTER TABLE territory_units_phase3 RENAME TO territory_units;
ALTER TABLE territory_chunks_phase3 RENAME TO territory_chunks;

CREATE INDEX ix_territory_world_center
    ON territory_units (world_uuid, center_chunk_x, center_chunk_z);
CREATE INDEX ix_territory_chunks_unit ON territory_chunks (unit_id);
CREATE UNIQUE INDEX uq_territory_residence_origin
    ON territory_units (lower(residence_name))
    WHERE grid_x = 0 AND grid_z = 0 AND reuse_blocked = 1;
CREATE UNIQUE INDEX uq_territory_residence_area
    ON territory_units (lower(residence_name), residence_area_name)
    WHERE reuse_blocked = 1;

CREATE TRIGGER tr_territory_units_updated_at
AFTER UPDATE ON territory_units
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE territory_units
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE unit_id = NEW.unit_id;
END;

CREATE TABLE territory_expansions (
    expansion_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    unit_id BLOB NOT NULL,
    business_key TEXT NOT NULL,
    actor_uuid BLOB NOT NULL,
    price_minor INTEGER NOT NULL CHECK (price_minor > 0),
    status TEXT NOT NULL CHECK (status IN ('PREPARED', 'COMPLETED', 'REFUNDED', 'COMPENSATION_REQUIRED')),
    last_error TEXT,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT uq_territory_expansion_business_key UNIQUE (business_key),
    CONSTRAINT uq_territory_expansion_unit UNIQUE (unit_id),
    CONSTRAINT fk_territory_expansion_town FOREIGN KEY (town_id) REFERENCES towns (town_id),
    CONSTRAINT fk_territory_expansion_unit FOREIGN KEY (unit_id) REFERENCES territory_units (unit_id)
);

CREATE INDEX ix_territory_expansions_recovery
    ON territory_expansions (status, updated_at);

CREATE TRIGGER tr_town_accounts_updated_at
AFTER UPDATE ON town_accounts
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE town_accounts
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE town_id = NEW.town_id;
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

CREATE TRIGGER tr_territory_expansions_updated_at
AFTER UPDATE ON territory_expansions
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE territory_expansions
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE expansion_id = NEW.expansion_id;
END;
