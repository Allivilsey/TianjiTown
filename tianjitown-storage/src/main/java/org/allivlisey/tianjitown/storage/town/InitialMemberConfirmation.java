package org.allivlisey.tianjitown.storage.town;

import java.time.Instant;
import java.util.UUID;

public record InitialMemberConfirmation(UUID playerId, Status status, Instant respondedAt, UUID invitationToken) {
    public InitialMemberConfirmation(UUID playerId, Status status, Instant respondedAt) {
        this(playerId, status, respondedAt, null);
    }
    public enum Status {
        PENDING,
        CONFIRMED,
        REJECTED
    }
}
