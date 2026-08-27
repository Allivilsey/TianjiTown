CREATE TABLE town_visitors (
    town_id BLOB NOT NULL,
    player_uuid BLOB NOT NULL,
    invited_by BLOB NOT NULL,
    added_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    PRIMARY KEY (town_id, player_uuid),
    CONSTRAINT fk_town_visitors_town FOREIGN KEY (town_id) REFERENCES towns (town_id)
);

CREATE INDEX ix_town_visitors_page
    ON town_visitors (town_id, added_at, player_uuid);

-- 玩家成为本镇正式成员时，不再保留重复的访客身份。
CREATE TRIGGER tr_town_members_remove_visitor
AFTER INSERT ON town_members
FOR EACH ROW
BEGIN
    DELETE FROM town_visitors
     WHERE town_id = NEW.town_id AND player_uuid = NEW.player_uuid;
END;
