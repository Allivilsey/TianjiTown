package cn.tianji.town.storage.town;

import cn.tianji.town.core.application.ApplicationText;
import cn.tianji.town.core.land.InitialTerritory;
import cn.tianji.town.core.land.TownResidenceName;
import cn.tianji.town.core.town.MemberRole;
import cn.tianji.town.core.town.TownStatus;

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
}
