CREATE TABLE building_refund_daily (
    town_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    day_key TEXT NOT NULL,
    refund_count INTEGER NOT NULL DEFAULT 0 CHECK (refund_count >= 0),
    last_material_key TEXT NOT NULL,
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    PRIMARY KEY (town_id, player_uuid, day_key),
    CONSTRAINT fk_building_refund_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

CREATE INDEX ix_building_refund_cleanup ON building_refund_daily (day_key);
CREATE INDEX ix_building_refund_player_day
    ON building_refund_daily (player_uuid, day_key, refund_count);

CREATE TRIGGER tr_building_refund_updated_at
AFTER UPDATE ON building_refund_daily
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE building_refund_daily
       SET updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
     WHERE town_id = NEW.town_id
       AND player_uuid = NEW.player_uuid
       AND day_key = NEW.day_key;
END;
