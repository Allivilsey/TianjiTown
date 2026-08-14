package cn.tianji.town.storage.phase1;

import java.time.Instant;
import java.util.UUID;

public record InitialMemberConfirmation(UUID playerId, Status status, Instant respondedAt) {
    public enum Status {
        PENDING,
        CONFIRMED,
        REJECTED
    }
}
