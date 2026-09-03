package cn.tianji.town.paper;

import cn.tianji.town.storage.town.TownSnapshot;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Keeps every member page on the same player-friendly, stable ordering. */
final class MemberDisplayOrder {
    private MemberDisplayOrder() {
    }

    static List<TownSnapshot.Member> sort(List<TownSnapshot.Member> members,
                                          Function<UUID, String> playerName) {
        Comparator<TownSnapshot.Member> comparator = Comparator
                .comparingInt((TownSnapshot.Member member) -> MemberRoleText.displayOrder(member.role()))
                .thenComparing(member -> sortName(member.playerId(), playerName),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(member -> member.playerId().toString());
        return members.stream().sorted(comparator).toList();
    }

    private static String sortName(UUID playerId, Function<UUID, String> playerName) {
        String name = playerName.apply(playerId);
        return name == null || name.isBlank() ? playerId.toString() : name;
    }
}
