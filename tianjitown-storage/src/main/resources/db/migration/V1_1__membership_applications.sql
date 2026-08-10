CREATE TABLE town_join_applications (
    join_application_id BLOB NOT NULL PRIMARY KEY,
    town_id BLOB NOT NULL,
    applicant_uuid BLOB NOT NULL,
    status TEXT NOT NULL CHECK (status IN (
        'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED'
    )),
    active_town_id BLOB GENERATED ALWAYS AS (
        CASE WHEN status = 'PENDING' THEN town_id ELSE NULL END
    ) STORED,
    active_applicant_uuid BLOB GENERATED ALWAYS AS (
        CASE WHEN status = 'PENDING' THEN applicant_uuid ELSE NULL END
    ) STORED,
    expires_at INTEGER NOT NULL,
    decided_by BLOB,
    decided_at INTEGER,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT uq_join_application_active_town_player
        UNIQUE (active_town_id, active_applicant_uuid),
    CONSTRAINT fk_join_applications_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

CREATE INDEX ix_join_applications_applicant
    ON town_join_applications (applicant_uuid, status, expires_at);

CREATE INDEX ix_join_applications_town
    ON town_join_applications (town_id, status, created_at);

CREATE TABLE town_member_departures (
    departure_id INTEGER PRIMARY KEY AUTOINCREMENT,
    town_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    departure_type TEXT NOT NULL CHECK (departure_type IN ('VOLUNTARY', 'REMOVED')),
    departed_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    CONSTRAINT fk_member_departures_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

CREATE INDEX ix_member_departures_player
    ON town_member_departures (player_uuid, departure_type, departed_at);

-- 升级后旧邀请立即失效，避免邀请制与申请制同时存在。
UPDATE town_invitations
   SET revoked_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
 WHERE accepted_at IS NULL AND revoked_at IS NULL;

CREATE TRIGGER tr_join_applications_updated_at
AFTER UPDATE ON town_join_applications
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE town_join_applications
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE join_application_id = NEW.join_application_id;
END;
