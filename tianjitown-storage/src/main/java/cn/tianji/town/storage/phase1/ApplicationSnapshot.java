package cn.tianji.town.storage.phase1;

import cn.tianji.town.core.application.ApplicationStatus;
import cn.tianji.town.core.application.ApplicationText;
import cn.tianji.town.core.land.InitialTerritory;

import java.time.Instant;
import java.util.UUID;

public record ApplicationSnapshot(
        UUID id,
        UUID applicantId,
        ApplicationText text,
        ApplicationStatus status,
        InitialTerritory territory,
        Instant reservationExpiresAt,
        UUID townId,
        String reviewMessage,
        String lastError,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
