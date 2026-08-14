package cn.tianji.town.storage.town;

import java.time.Instant;

public record AuditSnapshot(long id, String actorName, String action, String targetType,
                            String targetId, String reason, String detail, Instant createdAt) {
}
