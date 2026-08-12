-- 将旧官员身份迁移为副镇长，并在数据库层限制每镇最多三名副镇长。
CREATE TABLE town_members_phase6 (
    town_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('MAYOR', 'DEPUTY_MAYOR', 'MEMBER')),
    joined_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    last_active_at INTEGER,
    rules_revision INTEGER NOT NULL DEFAULT 1,
    accepted_tax_revision INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (town_id, player_uuid),
    CONSTRAINT uq_town_members_phase6_player UNIQUE (player_uuid),
    CONSTRAINT fk_town_members_phase6_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

INSERT INTO town_members_phase6
    (town_id, player_uuid, role, joined_at, last_active_at, rules_revision,
     accepted_tax_revision)
SELECT town_id, player_uuid,
       CASE
           WHEN role = 'OFFICER' AND ROW_NUMBER() OVER (
               PARTITION BY town_id, role ORDER BY joined_at, player_uuid
           ) > 3 THEN 'MEMBER'
           WHEN role = 'OFFICER' THEN 'DEPUTY_MAYOR'
           ELSE role
       END,
       joined_at, last_active_at, rules_revision, accepted_tax_revision
  FROM town_members;

DROP TABLE town_members;
ALTER TABLE town_members_phase6 RENAME TO town_members;
CREATE INDEX ix_town_members_page ON town_members (town_id, joined_at, player_uuid);
CREATE INDEX ix_town_members_active ON town_members (town_id, last_active_at, joined_at);
CREATE UNIQUE INDEX uq_town_members_single_mayor
    ON town_members (town_id) WHERE role = 'MAYOR';

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

CREATE TABLE town_archived_members_phase6 (
    town_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('MAYOR', 'DEPUTY_MAYOR', 'MEMBER')),
    joined_at INTEGER NOT NULL,
    last_active_at INTEGER,
    rules_revision INTEGER NOT NULL,
    archived_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    PRIMARY KEY (town_id, player_uuid),
    CONSTRAINT fk_archived_members_phase6_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

INSERT INTO town_archived_members_phase6
    (town_id, player_uuid, role, joined_at, last_active_at, rules_revision, archived_at)
SELECT town_id, player_uuid,
       CASE role WHEN 'OFFICER' THEN 'DEPUTY_MAYOR' ELSE role END,
       joined_at, last_active_at, rules_revision, archived_at
  FROM town_archived_members;

DROP TABLE town_archived_members;
ALTER TABLE town_archived_members_phase6 RENAME TO town_archived_members;

-- 按周一零点划分额度；升级时把本周已有的每日计数合并保留。
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

INSERT INTO building_refund_weekly
    (town_id, player_uuid, week_start, refund_count, last_material_key, updated_at)
SELECT town_id, player_uuid,
       date(day_key, printf('-%d days',
            (CAST(strftime('%w', day_key) AS INTEGER) + 6) % 7)),
       SUM(refund_count), MAX(last_material_key), MAX(updated_at)
  FROM building_refund_daily
 GROUP BY town_id, player_uuid,
          date(day_key, printf('-%d days',
               (CAST(strftime('%w', day_key) AS INTEGER) + 6) % 7));

DROP TABLE building_refund_daily;
CREATE INDEX ix_building_refund_cleanup ON building_refund_weekly (week_start);
CREATE INDEX ix_building_refund_player_week
    ON building_refund_weekly (player_uuid, week_start, refund_count);

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

-- SQLite 不能直接扩展 CHECK 枚举，因此重建账本表以接纳两种外部收入税。
ALTER TABLE ledger_entries RENAME TO ledger_entries_phase5;

CREATE TABLE ledger_entries (
    entry_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    entry_type TEXT NOT NULL CHECK (entry_type IN (
        'QUICKSHOP_TAX', 'JOBS_TAX', 'GLOBALMARKETPLUS_TAX',
        'DONATION', 'EXPANSION', 'EXPANSION_REFUND', 'ADMIN_ADJUSTMENT',
        'BUFF_PURCHASE', 'BUFF_REFUND', 'RESOURCE_PURCHASE', 'RESOURCE_REFUND'
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
  FROM ledger_entries_phase5;

DROP TABLE ledger_entries_phase5;
CREATE INDEX ix_ledger_town_time ON ledger_entries (town_id, created_at DESC, entry_id);
CREATE INDEX ix_ledger_created ON ledger_entries (created_at);

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

CREATE INDEX ix_external_income_tax_town_created
    ON external_income_tax_records (town_id, created_at);
CREATE INDEX ix_external_income_tax_source_created
    ON external_income_tax_records (source, created_at);
