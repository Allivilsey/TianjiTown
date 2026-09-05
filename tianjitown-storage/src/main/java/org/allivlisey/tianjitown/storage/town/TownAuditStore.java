package org.allivlisey.tianjitown.storage.town;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.allivlisey.tianjitown.storage.town.TownPersistence.RULE_SEPARATOR;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.uuid;

import org.allivlisey.tianjitown.storage.town.TownRepository.*;

final class TownAuditStore {
    private final TownDatabase database;

    TownAuditStore(TownDatabase database) {
        this.database = database;
    }

    public List<AuditSnapshot> auditLog(int limit) {
        database.requireWorkerThread();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return database.query(connection -> {
            List<AuditSnapshot> records = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT audit_id, actor_name, action, target_type, target_id, reason, detail, created_at
                      FROM audit_logs ORDER BY audit_id DESC LIMIT ?
                    """)) {
                statement.setInt(1, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        records.add(new AuditSnapshot(result.getLong("audit_id"),
                                result.getString("actor_name"), result.getString("action"),
                                result.getString("target_type"), result.getString("target_id"),
                                result.getString("reason"), result.getString("detail"),
                                result.getTimestamp("created_at").toInstant()));
                    }
                }
            }
            return List.copyOf(records);
        });
    }

    public void recordAudit(UUID actorId, String actorName, String action, String targetType,
                            String targetId, String reason, String detail) {
        database.requireWorkerThread();
        database.transaction(connection -> {
            TownPersistence.audit(connection, null, actorId, actorName, action, targetType, targetId, reason, detail);
            return null;
        });
    }

}
