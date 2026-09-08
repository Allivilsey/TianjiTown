package org.allivlisey.tianjitown.storage.town;

import java.sql.PreparedStatement;
import java.util.UUID;
import org.allivlisey.tianjitown.core.application.ApplicationText;

import static org.allivlisey.tianjitown.storage.town.TownPersistence.RULE_SEPARATOR;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.uuid;

import org.allivlisey.tianjitown.storage.town.TownRepository.*;

final class TownProfileStore {
    private final TownDatabase database;

    TownProfileStore(TownDatabase database) {
        this.database = database;
    }

    public TownSnapshot updateTownProfile(UUID townId, ApplicationText profile, long expectedVersion,
                                          UUID actorId, String actorName, String reason) {
        database.requireWorkerThread();
        profile.requireValid();
        return database.transaction(connection -> {
            TownPersistence.requireManager(connection, townId, actorId);
            TownSnapshot current = TownPersistence.requireTown(connection, townId);
            if (!current.profile().name().equals(profile.name())
                    || !current.profile().normalizedResidenceName()
                    .equals(profile.normalizedResidenceName())) {
                throw new ConflictException("玩家资料入口不能修改小镇名称或小镇代码");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE towns SET description = ?, rules_text = ?,
                        rules_revision = rules_revision + CASE WHEN rules_text <> ? THEN 1 ELSE 0 END,
                        version = version + 1
                     WHERE town_id = ? AND version = ? AND status <> 'ARCHIVED'
                    """)) {
                statement.setString(1, profile.description());
                statement.setString(2, String.join(RULE_SEPARATOR, profile.rules()));
                statement.setString(3, String.join(RULE_SEPARATOR, profile.rules()));
                statement.setBytes(4, uuid(townId));
                statement.setLong(5, expectedVersion);
                TownPersistence.requireUpdated(statement, "小镇资料已被其他操作修改，请重新读取后再试");
            }
            TownPersistence.audit(connection, null, actorId, actorName, "PROFILE_UPDATE", "TOWN", townId.toString(),
                    reason, current.profile().name());
            return TownPersistence.requireTown(connection, townId);
        });
    }

}
