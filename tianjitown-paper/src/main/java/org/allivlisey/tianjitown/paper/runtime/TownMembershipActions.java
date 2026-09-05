package org.allivlisey.tianjitown.paper.runtime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.storage.town.JoinApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownPlayerChange;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.allivlisey.tianjitown.paper.runtime.TownActionSupport.*;
import static org.allivlisey.tianjitown.paper.runtime.TownActionSupport.*;

final class TownMembershipActions {
    private final TownActionSupport support;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;

    TownMembershipActions(TownActionSupport support) {
        this.support = support;
        this.plugin = support.plugin;
        this.runtime = support.runtime;
    }

    public void applyToTown(Player actor, UUID townId,
                     Consumer<TownActionOutcome<JoinApplicationSnapshot>> completion) {
        Duration lifetime = support.durationHours("application-lifetime-hours", 48);
        Duration rejectionCooldown = support.durationHours("rejection-cooldown-hours", 24);
        Duration leaveCooldown = support.durationHours("leave-cooldown-hours", 24);
        int maximumPending = plugin.getConfig().getInt(
                "town.membership.maximum-pending-applications", 3);
        support.write("JOIN_APPLY", actor, () -> runtime.repository().applyToTown(townId,
                        actor.getUniqueId(), lifetime, rejectionCooldown, leaveCooldown,
                        maximumPending),
                application -> support.joinData(application), completion);
    }

    public void cancelJoinApplication(Player actor, UUID applicationId,
                               Consumer<TownActionOutcome<JoinApplicationSnapshot>> completion) {
        support.write("JOIN_CANCEL", actor,
                () -> runtime.repository().cancelJoinApplication(applicationId,
                        actor.getUniqueId()), TownActionSupport::joinData, completion);
    }

    public void approveJoinApplication(Player actor, UUID applicationId,
                                Consumer<TownActionOutcome<JoinApplicationSnapshot>> completion) {
        support.write("JOIN_APPROVE", actor,
                () -> runtime.repository().approveJoinApplication(applicationId,
                        actor.getUniqueId()), application -> {
                    support.syncResidence(actor, application.townId());
                    return support.joinData(application);
                }, completion);
    }

    public void rejectJoinApplication(Player actor, UUID applicationId,
                               Consumer<TownActionOutcome<JoinApplicationSnapshot>> completion) {
        support.write("JOIN_REJECT", actor,
                () -> runtime.repository().rejectJoinApplication(applicationId,
                        actor.getUniqueId()), TownActionSupport::joinData, completion);
    }

    public void changeMemberRole(Player actor, UUID townId, UUID targetId, MemberRole role,
                          Consumer<TownActionOutcome<MemberRole>> completion) {
        support.write("MEMBER_ROLE_CHANGE", actor,
                () -> runtime.governance().changeRoleByMayor(townId, targetId, role,
                        actor.getUniqueId(), actor.getName()), changed -> {
                    support.syncResidence(actor, townId);
                    return Map.of("town_id", townId, "target_id", targetId, "role", changed);
                }, completion);
    }

    public void kickMember(Player actor, UUID townId, UUID targetId,
                    Consumer<TownActionOutcome<TownPlayerChange>> completion) {
        support.write("MEMBER_KICK", actor, () -> {
            return runtime.governance().removeMemberByMayor(townId, targetId,
                    actor.getUniqueId(), actor.getName());
        }, change -> {
            Player removed = plugin.getServer().getPlayer(change.playerId());
            if (removed != null) {
                runtime.buffs().refreshPlayer(removed);
            }
            support.syncResidence(actor, change.townId());
            return Map.of("town_id", change.townId(), "target_id", change.playerId(),
                    "town_name", change.townName());
        }, completion);
    }

    public void addVisitor(Player actor, UUID townId, UUID targetId,
                    Consumer<TownActionOutcome<TownPlayerChange>> completion) {
        support.write("VISITOR_ADD", actor,
                () -> runtime.repository().addVisitorWithTownName(townId, targetId,
                        actor.getUniqueId(), actor.getName()), visitor -> {
                    support.syncResidence(actor, visitor.townId());
                    return Map.of("town_id", visitor.townId(), "target_id", visitor.playerId(),
                            "town_name", visitor.townName());
                }, completion);
    }

    public void removeVisitor(Player actor, UUID townId, UUID targetId,
                       Consumer<TownActionOutcome<TownPlayerChange>> completion) {
        support.write("VISITOR_REMOVE", actor,
                () -> runtime.repository().removeVisitorWithTownName(townId, targetId,
                        actor.getUniqueId(), actor.getName()), removed -> {
                    support.syncResidence(actor, removed.townId());
                    return Map.of("town_id", removed.townId(), "target_id", removed.playerId(),
                            "town_name", removed.townName());
                }, completion);
    }

    public void leaveTown(Player actor, UUID townId,
                   Consumer<TownActionOutcome<UUID>> completion) {
        support.write("TOWN_LEAVE", actor, () -> {
            runtime.repository().leaveTown(actor.getUniqueId());
            return townId;
        }, changedTown -> {
            runtime.buffs().refreshPlayer(actor);
            support.syncResidence(actor, changedTown);
            return Map.of("town_id", changedTown, "player_id", actor.getUniqueId());
        }, completion);
    }

}
