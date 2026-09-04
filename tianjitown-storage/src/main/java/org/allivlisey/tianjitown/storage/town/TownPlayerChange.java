package org.allivlisey.tianjitown.storage.town;

import java.util.Objects;
import java.util.UUID;

/**
 * Result of a membership or visitor mutation, including the town name observed by
 * the same database transaction that committed the mutation.
 */
public record TownPlayerChange(UUID townId, UUID playerId, String townName) {
    public TownPlayerChange {
        townId = Objects.requireNonNull(townId, "townId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        townName = Objects.requireNonNull(townName, "townName");
        if (townName.isBlank()) {
            throw new IllegalArgumentException("townName 不能为空");
        }
    }
}
