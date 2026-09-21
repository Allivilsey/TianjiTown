-- 1.0.0 release: extend the published 1.0 schema without changing its checksum.
-- Legacy application fees remain in town_applications; the repository reads those states
-- until a new operation is recorded. Historical tax records are not replayed.

CREATE TABLE application_fee_operations (
    application_id BLOB NOT NULL PRIMARY KEY REFERENCES town_applications(application_id),
    amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
    state TEXT NOT NULL CHECK (state IN (
        'COLLECTING', 'COLLECTION_UNKNOWN', 'PLAYER_REFUND_PENDING', 'PLAYER_REFUNDING',
        'PLAYER_REFUND_UNKNOWN', 'ESCROWED', 'REFUND_PENDING', 'REFUNDING',
        'REFUND_UNKNOWN', 'REFUNDED', 'UNPAID'
    )),
    detail TEXT NOT NULL DEFAULT '',
    version INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER))
);

-- Jobs/GMP 收税在 Vault 调用前落盘；结果未知时仅核账，不自动重放。
CREATE TABLE income_tax_collections (
    operation_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL REFERENCES towns (town_id),
    business_key TEXT NOT NULL UNIQUE,
    source TEXT NOT NULL CHECK (source IN ('JOBS', 'GLOBALMARKETPLUS')),
    receiver_uuid BLOB NOT NULL,
    receiver_name TEXT NOT NULL,
    gross_minor INTEGER NOT NULL CHECK (gross_minor > 0),
    tax_rate_bps INTEGER NOT NULL CHECK (tax_rate_bps BETWEEN 0 AND 10000),
    tax_minor INTEGER NOT NULL CHECK (tax_minor > 0 AND tax_minor < gross_minor),
    status TEXT NOT NULL CHECK (status IN ('PREPARED', 'ATTEMPTED', 'SUCCEEDED', 'RECORDED',
        'FAILED', 'REFUND_REQUIRED', 'REFUND_ATTEMPTED', 'REFUNDED', 'AMBIGUOUS')),
    last_error TEXT NOT NULL DEFAULT '',
    version INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER))
);
CREATE INDEX ix_income_tax_collection_recovery ON income_tax_collections (status, created_at);
