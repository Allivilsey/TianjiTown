package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemberDisplayOrderTest {
    @Test
    void ordersRolesThenNamesIgnoringCaseWithStableUuidFallback() {
        UUID mayor = UUID.fromString("00000000-0000-0000-0000-000000000005");
        UUID deputyZed = UUID.fromString("00000000-0000-0000-0000-000000000004");
        UUID deputyAmy = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID memberSameNameLow = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID memberSameNameHigh = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID unnamed = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        Map<UUID, String> names = Map.of(mayor, "zoe", deputyZed, "Zed", deputyAmy, "amy",
                memberSameNameLow, "Bob", memberSameNameHigh, "bob");
        List<TownSnapshot.Member> sorted = MemberDisplayOrder.sort(List.of(
                memberSameNameHigh, unnamed, deputyZed, mayor, memberSameNameLow, deputyAmy)
                .stream().map(id -> new TownSnapshot.Member(id,
                        id.equals(mayor) ? MemberRole.MAYOR
                                : id.equals(deputyZed) || id.equals(deputyAmy)
                                ? MemberRole.DEPUTY_MAYOR : MemberRole.MEMBER,
                        Instant.EPOCH)).toList(), names::get);

        assertEquals(List.of(mayor, deputyAmy, deputyZed, memberSameNameLow,
                memberSameNameHigh, unnamed), sorted.stream().map(TownSnapshot.Member::playerId).toList());
    }
}
