-- Events roll back with the underlying membership transaction. No historical backfill.
CREATE TABLE player_change_notifications (
    notification_id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid BLOB NOT NULL,
    town_name TEXT NOT NULL,
    old_role TEXT NOT NULL,
    new_role TEXT NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER))
);
CREATE INDEX ix_player_change_pending ON player_change_notifications(player_uuid, notification_id);

DROP TRIGGER tr_town_members_remove_visitor;
CREATE TRIGGER tr_town_members_remove_visitor
AFTER INSERT ON town_members
FOR EACH ROW
BEGIN
    INSERT INTO player_change_notifications(player_uuid, town_name, old_role, new_role)
    SELECT NEW.player_uuid, name,
           CASE WHEN EXISTS (SELECT 1 FROM town_visitors WHERE town_id = NEW.town_id AND player_uuid = NEW.player_uuid)
                THEN 'VISITOR' ELSE 'NONE' END, NEW.role
      FROM towns WHERE town_id = NEW.town_id;
    DELETE FROM town_visitors WHERE town_id = NEW.town_id AND player_uuid = NEW.player_uuid;
END;

CREATE TRIGGER tr_notify_town_members_delete
AFTER DELETE ON town_members
FOR EACH ROW
BEGIN
    INSERT INTO player_change_notifications(player_uuid, town_name, old_role, new_role)
    SELECT OLD.player_uuid, name, OLD.role, 'NONE' FROM towns WHERE town_id = OLD.town_id;
END;

CREATE TRIGGER tr_notify_town_members_update
AFTER UPDATE OF role ON town_members
FOR EACH ROW WHEN OLD.role <> NEW.role
BEGIN
    INSERT INTO player_change_notifications(player_uuid, town_name, old_role, new_role)
    SELECT NEW.player_uuid, name, OLD.role, NEW.role FROM towns WHERE town_id = NEW.town_id;
END;

CREATE TRIGGER tr_notify_town_visitors_insert
AFTER INSERT ON town_visitors
FOR EACH ROW
BEGIN
    INSERT INTO player_change_notifications(player_uuid, town_name, old_role, new_role)
    SELECT NEW.player_uuid, name, 'NONE', 'VISITOR' FROM towns WHERE town_id = NEW.town_id;
END;

CREATE TRIGGER tr_notify_town_visitors_delete
AFTER DELETE ON town_visitors
FOR EACH ROW WHEN NOT EXISTS (SELECT 1 FROM town_members WHERE town_id = OLD.town_id AND player_uuid = OLD.player_uuid)
BEGIN
    INSERT INTO player_change_notifications(player_uuid, town_name, old_role, new_role)
    SELECT OLD.player_uuid, name, 'VISITOR', 'NONE' FROM towns WHERE town_id = OLD.town_id;
END;
