ALTER TABLE town_applications
    ADD COLUMN residence_name VARCHAR(12) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL
        AFTER normalized_short_name,
    ADD COLUMN active_residence_name VARCHAR(12) CHARACTER SET ascii COLLATE ascii_general_ci
        GENERATED ALWAYS AS (
            CASE WHEN status IN ('DRAFT', 'SITE_SELECTED', 'SUBMITTED', 'UNDER_REVIEW',
                                 'NEED_CHANGES', 'APPROVED_PROVISIONING', 'PROVISION_FAILED')
                THEN LOWER(residence_name) ELSE NULL END
        ) STORED,
    ADD UNIQUE KEY uq_applications_active_residence_name (active_residence_name);
