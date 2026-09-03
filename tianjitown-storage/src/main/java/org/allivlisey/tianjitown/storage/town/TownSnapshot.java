package org.allivlisey.tianjitown.storage.town;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.land.TownResidenceName;
import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.core.town.TownStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TownSnapshot(
        UUID id,
        ApplicationText profile,
        TownStatus status,
        UUID mayorId,
        long rulesRevision,
        long version,
        Instant createdAt,
        InitialTerritory territory,
        String projectionStatus,
        String projectionError
) {
    public String residenceName() {
        return TownResidenceName.initial(profile.residenceName());
    }

    public record Member(UUID playerId, MemberRole role, Instant joinedAt) {
    }

    public record Page(List<Member> members, boolean hasNext) {
        public Page {
            members = List.copyOf(members);
        }
    }

    public record Visitor(UUID playerId, UUID invitedBy, Instant addedAt) {
    }

    public record VisitorPage(List<Visitor> visitors, boolean hasNext) {
        public VisitorPage {
            visitors = List.copyOf(visitors);
        }
    }
}
