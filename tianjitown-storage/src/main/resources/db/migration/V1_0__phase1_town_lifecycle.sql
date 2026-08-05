CREATE TABLE towns (
    town_id BINARY(16) NOT NULL,
    name VARCHAR(24) NOT NULL,
    normalized_name VARCHAR(24) NOT NULL,
    short_name VARCHAR(8) NOT NULL,
    normalized_short_name VARCHAR(8) NOT NULL,
    description TEXT NOT NULL,
    rules_text TEXT NOT NULL,
    status ENUM('PROVISIONING', 'ACTIVE', 'ARCHIVED') NOT NULL,
    mayor_uuid BINARY(16) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (town_id),
    UNIQUE KEY uq_towns_normalized_name (normalized_name),
    UNIQUE KEY uq_towns_normalized_short_name (normalized_short_name),
    KEY ix_towns_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE town_members (
    town_id BINARY(16) NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    role ENUM('MAYOR', 'MEMBER') NOT NULL,
    joined_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_active_at TIMESTAMP(6) NULL,
    rules_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (town_id, player_uuid),
    UNIQUE KEY uq_town_members_player (player_uuid),
    KEY ix_town_members_page (town_id, joined_at, player_uuid),
    CONSTRAINT fk_town_members_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE town_applications (
    application_id BINARY(16) NOT NULL,
    applicant_uuid BINARY(16) NOT NULL,
    name VARCHAR(24) NOT NULL,
    normalized_name VARCHAR(24) NOT NULL,
    short_name VARCHAR(8) NOT NULL,
    normalized_short_name VARCHAR(8) NOT NULL,
    description TEXT NOT NULL,
    rules_text TEXT NOT NULL,
    status ENUM('DRAFT', 'SITE_SELECTED', 'SUBMITTED', 'UNDER_REVIEW', 'NEED_CHANGES',
                'APPROVED_PROVISIONING', 'ACTIVE', 'REJECTED', 'CANCELLED', 'PROVISION_FAILED') NOT NULL,
    active_applicant BINARY(16) GENERATED ALWAYS AS (
        CASE WHEN status IN ('DRAFT', 'SITE_SELECTED', 'SUBMITTED', 'UNDER_REVIEW', 'NEED_CHANGES',
                             'APPROVED_PROVISIONING', 'PROVISION_FAILED') THEN applicant_uuid ELSE NULL END
    ) STORED,
    active_name VARCHAR(24) GENERATED ALWAYS AS (
        CASE WHEN status IN ('DRAFT', 'SITE_SELECTED', 'SUBMITTED', 'UNDER_REVIEW', 'NEED_CHANGES',
                             'APPROVED_PROVISIONING', 'PROVISION_FAILED') THEN normalized_name ELSE NULL END
    ) STORED,
    active_short_name VARCHAR(8) GENERATED ALWAYS AS (
        CASE WHEN status IN ('DRAFT', 'SITE_SELECTED', 'SUBMITTED', 'UNDER_REVIEW', 'NEED_CHANGES',
                             'APPROVED_PROVISIONING', 'PROVISION_FAILED') THEN normalized_short_name ELSE NULL END
    ) STORED,
    town_id BINARY(16) NULL,
    review_message TEXT NULL,
    last_error TEXT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    submitted_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (application_id),
    UNIQUE KEY uq_applications_active_applicant (active_applicant),
    UNIQUE KEY uq_applications_active_name (active_name),
    UNIQUE KEY uq_applications_active_short_name (active_short_name),
    KEY ix_applications_review_queue (status, submitted_at),
    CONSTRAINT fk_applications_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE application_reviews (
    review_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    application_id BINARY(16) NOT NULL,
    reviewer_uuid BINARY(16) NOT NULL,
    action VARCHAR(32) NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (review_id),
    KEY ix_reviews_application (application_id, created_at),
    CONSTRAINT fk_reviews_application FOREIGN KEY (application_id)
        REFERENCES town_applications (application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE site_reservations (
    reservation_id BINARY(16) NOT NULL,
    application_id BINARY(16) NOT NULL,
    world_uuid BINARY(16) NOT NULL,
    world_name VARCHAR(128) NOT NULL,
    center_chunk_x INT NOT NULL,
    center_chunk_z INT NOT NULL,
    min_chunk_x INT NOT NULL,
    max_chunk_x INT NOT NULL,
    min_chunk_z INT NOT NULL,
    max_chunk_z INT NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    released_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (reservation_id),
    UNIQUE KEY uq_reservation_application (application_id),
    KEY ix_reservations_overlap (world_uuid, released_at, expires_at, min_chunk_x, max_chunk_x,
                                 min_chunk_z, max_chunk_z),
    CONSTRAINT fk_reservations_application FOREIGN KEY (application_id)
        REFERENCES town_applications (application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE territory_units (
    unit_id BINARY(16) NOT NULL,
    town_id BINARY(16) NOT NULL,
    world_uuid BINARY(16) NOT NULL,
    world_name VARCHAR(128) NOT NULL,
    grid_x INT NOT NULL,
    grid_z INT NOT NULL,
    center_chunk_x INT NOT NULL,
    center_chunk_z INT NOT NULL,
    residence_name VARCHAR(64) NOT NULL,
    projection_status ENUM('PENDING', 'ACTIVE', 'FAILED') NOT NULL DEFAULT 'PENDING',
    projection_error TEXT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (unit_id),
    UNIQUE KEY uq_territory_grid (town_id, grid_x, grid_z),
    UNIQUE KEY uq_territory_residence (residence_name),
    KEY ix_territory_world_center (world_uuid, center_chunk_x, center_chunk_z),
    CONSTRAINT fk_territory_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE territory_chunks (
    unit_id BINARY(16) NOT NULL,
    world_uuid BINARY(16) NOT NULL,
    chunk_x INT NOT NULL,
    chunk_z INT NOT NULL,
    PRIMARY KEY (world_uuid, chunk_x, chunk_z),
    KEY ix_territory_chunks_unit (unit_id),
    CONSTRAINT fk_territory_chunks_unit FOREIGN KEY (unit_id)
        REFERENCES territory_units (unit_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE town_invitations (
    invitation_id BINARY(16) NOT NULL,
    town_id BINARY(16) NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    invited_by BINARY(16) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    accepted_at TIMESTAMP(6) NULL,
    revoked_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (invitation_id),
    UNIQUE KEY uq_invitation_town_player (town_id, player_uuid),
    KEY ix_invitations_player (player_uuid, accepted_at, revoked_at, expires_at),
    CONSTRAINT fk_invitations_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE audit_logs (
    audit_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(128) NULL,
    actor_uuid BINARY(16) NULL,
    actor_name VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    reason TEXT NOT NULL,
    detail TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (audit_id),
    UNIQUE KEY uq_audit_idempotency (idempotency_key),
    KEY ix_audit_target (target_type, target_id, created_at),
    KEY ix_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE town_profile_sync (
    town_id BINARY(16) NOT NULL,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    checksum CHAR(64) NULL,
    sync_status ENUM('PENDING', 'SYNCED', 'FAILED', 'MANUAL_CHANGE') NOT NULL DEFAULT 'PENDING',
    last_error TEXT NULL,
    exported_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (town_id),
    CONSTRAINT fk_profile_sync_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
