package cn.tianji.town.storage.phase2;

import java.time.Instant;
import java.util.UUID;

public record TransferSnapshot(
        UUID id,
        UUID townId,
        UUID requestedBy,
        UUID candidateId,
        String status,
        Instant expiresAt,
        Instant createdAt
) {
}
