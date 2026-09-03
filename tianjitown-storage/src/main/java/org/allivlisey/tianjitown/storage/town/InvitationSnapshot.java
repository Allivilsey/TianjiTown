package org.allivlisey.tianjitown.storage.town;

import java.time.Instant;
import java.util.UUID;

public record InvitationSnapshot(UUID id, UUID townId, String townName, UUID invitedBy,
                                 Instant expiresAt) {
}
