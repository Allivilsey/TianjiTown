package cn.tianji.town.storage.phase1;

import java.time.Instant;
import java.util.UUID;

public record JoinApplicationSnapshot(
        UUID id,
        UUID townId,
        String townName,
        UUID applicantId,
        Status status,
        Instant expiresAt,
        UUID decidedBy,
        Instant decidedAt,
        Instant createdAt
) {
    public enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        CANCELLED,
        EXPIRED
    }
}
