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
ALTER TABLE town_applications ADD COLUMN application_fee_status TEXT NOT NULL DEFAULT 'UNPAID'
    CHECK (application_fee_status IN (
        'UNPAID', 'ESCROWED', 'CONSUMED', 'REFUND_PENDING', 'REFUNDED'
    ));

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
ALTER TABLE quickshop_tax_records ADD COLUMN receiver_name TEXT NOT NULL DEFAULT '';

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

ALTER TABLE territory_expansions ADD COLUMN batch_id BLOB
    REFERENCES territory_expansion_batches (batch_id);
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
