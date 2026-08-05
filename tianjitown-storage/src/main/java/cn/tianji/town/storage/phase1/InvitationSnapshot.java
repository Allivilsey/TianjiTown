package cn.tianji.town.storage.phase1;

import java.time.Instant;
import java.util.UUID;

public record InvitationSnapshot(UUID id, UUID townId, String townName, UUID invitedBy,
                                 Instant expiresAt) {
}
