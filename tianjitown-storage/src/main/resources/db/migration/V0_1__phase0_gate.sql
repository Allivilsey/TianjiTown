CREATE TABLE IF NOT EXISTS phase0_installation_gate (
    id TINYINT UNSIGNED NOT NULL,
    verified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT chk_phase0_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO phase0_installation_gate (id) VALUES (1)
ON DUPLICATE KEY UPDATE verified_at = CURRENT_TIMESTAMP(6);
