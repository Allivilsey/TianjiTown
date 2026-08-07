ALTER TABLE towns
    ADD COLUMN reuse_blocked BOOLEAN NOT NULL DEFAULT TRUE AFTER status,
    ADD COLUMN reserved_normalized_name VARCHAR(24)
        GENERATED ALWAYS AS (
            CASE WHEN status <> 'ARCHIVED' OR reuse_blocked THEN normalized_name ELSE NULL END
        ) STORED,
    ADD COLUMN reserved_normalized_short_name VARCHAR(8)
        GENERATED ALWAYS AS (
            CASE WHEN status <> 'ARCHIVED' OR reuse_blocked THEN normalized_short_name ELSE NULL END
        ) STORED,
    DROP INDEX uq_towns_normalized_name,
    DROP INDEX uq_towns_normalized_short_name,
    ADD UNIQUE KEY uq_towns_reserved_normalized_name (reserved_normalized_name),
    ADD UNIQUE KEY uq_towns_reserved_normalized_short_name (reserved_normalized_short_name);

ALTER TABLE territory_units
    ADD COLUMN reuse_blocked BOOLEAN NOT NULL DEFAULT TRUE AFTER projection_error,
    ADD COLUMN reserved_residence_name VARCHAR(64)
        GENERATED ALWAYS AS (CASE WHEN reuse_blocked THEN residence_name ELSE NULL END) STORED,
    DROP INDEX uq_territory_residence,
    ADD UNIQUE KEY uq_territory_reserved_residence (reserved_residence_name);

-- 历史 ARCHIVED 记录可能来自 Residence 移除失败，升级时保持锁定。
-- 管理员可重新执行带确认的删除命令；只有投影确认移除后才释放复用锁。
