-- SQLite 不能直接扩展 CHECK 枚举，因此以向前迁移重建账本表。
ALTER TABLE ledger_entries RENAME TO ledger_entries_phase3;

CREATE TABLE ledger_entries (
    entry_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    entry_type TEXT NOT NULL CHECK (entry_type IN (
        'QUICKSHOP_TAX', 'DONATION', 'EXPANSION', 'EXPANSION_REFUND',
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

INSERT INTO ledger_entries
    (entry_id, town_id, entry_type, amount_minor, balance_after_minor, actor_uuid,
     actor_name, business_key, note, created_at)
SELECT entry_id, town_id, entry_type, amount_minor, balance_after_minor, actor_uuid,
       actor_name, business_key, note, created_at
  FROM ledger_entries_phase3;

DROP TABLE ledger_entries_phase3;

CREATE INDEX ix_ledger_town_time ON ledger_entries (town_id, created_at DESC, entry_id);
CREATE INDEX ix_ledger_created ON ledger_entries (created_at);

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

CREATE UNIQUE INDEX uq_active_buff_town_key
    ON active_buffs (town_id, buff_key) WHERE status = 'ACTIVE';
CREATE INDEX ix_active_buffs_expiry ON active_buffs (status, expires_at);
CREATE INDEX ix_active_buffs_town ON active_buffs (town_id, status, expires_at);

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

CREATE INDEX ix_resource_orders_player_status
    ON resource_orders (buyer_uuid, status, created_at DESC);
CREATE INDEX ix_resource_orders_daily
    ON resource_orders (town_id, resource_key, created_at, status);
CREATE INDEX ix_resource_orders_recovery
    ON resource_orders (status, claim_started_at);

CREATE TRIGGER tr_active_buffs_updated_at
AFTER UPDATE ON active_buffs
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE active_buffs
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE buff_id = NEW.buff_id;
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
