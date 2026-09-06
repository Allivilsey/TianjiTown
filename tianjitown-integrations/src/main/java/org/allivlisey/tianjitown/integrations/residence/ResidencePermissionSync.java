package org.allivlisey.tianjitown.integrations.residence;

import com.bekvon.bukkit.residence.commands.padd;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import org.bukkit.Server;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.allivlisey.tianjitown.core.ports.LandProtectionService.*;
import static org.allivlisey.tianjitown.integrations.residence.ResidenceLandProtectionService.safeText;

final class ResidencePermissionSync {
    private static final String MONSTER_SPAWN_FLAG = "monsters";
    private static final String IGNITE_FLAG = "ignite";
    private static final String VEHICLE_DESTROY_FLAG = "vehicledestroy";
    private static final List<String> PROTECTED_EXPLOSION_FLAGS = List.of(
            "explode", "tnt", "creeper");
    private final Server server;
    private final ResidenceMutationContext context;

    ResidencePermissionSync(Server server, ResidenceMutationContext context) {
        this.server = server;
        this.context = context;
    }

    Result verifyPermissions(String name, ClaimedResidence residence,
                                     Collection<UUID> members, boolean applyPermissions) {
        context.requireMainThread();
        for (String flag : PROTECTED_EXPLOSION_FLAGS) {
            if (!applyPermissions && residence.getPermissions().has(flag, true)) {
                return Result.failureCode(ResultCode.EXPLOSION_FLAG_MISMATCH,
                        Map.of("flag", safeText(flag)));
            }
            if (applyPermissions && !residence.getPermissions().setFlag(
                    server.getConsoleSender(), flag, FlagPermissions.FlagState.FALSE, true,
                     false)) {
                return Result.failureCode(ResultCode.EXPLOSION_FLAG_WRITE_FAILED,
                        Map.of("flag", safeText(flag)));
            }
        }
        if (!applyPermissions && residence.getPermissions().has(MONSTER_SPAWN_FLAG, true)) {
            return Result.failureCode(ResultCode.MONSTER_SPAWN_FLAG_MISMATCH);
        }
        if (applyPermissions && !residence.getPermissions().setFlag(
                server.getConsoleSender(), MONSTER_SPAWN_FLAG, FlagPermissions.FlagState.FALSE, true,
                false)) {
            return Result.failureCode(ResultCode.MONSTER_SPAWN_FLAG_WRITE_FAILED);
        }
        java.util.Set<UUID> existingPlayers = java.util.Set.copyOf(
                residence.getPermissions().getPlayerFlags().keySet());
        if (!applyPermissions && !existingPlayers.equals(java.util.Set.copyOf(members))) {
            return Result.failureCode(ResultCode.MEMBERSHIP_MISMATCH);
        }
        // 清理已离镇玩家的权限，Residence 权限完全由数据库成员关系投影。
        if (applyPermissions) {
            for (UUID existing : existingPlayers) {
                if (!members.contains(existing)) {
                    residence.getPermissions().removeAllPlayerFlags(existing);
                }
            }
        }
        for (UUID member : members) {
            Map<String, Boolean> playerFlags = residence.getPermissions().getPlayerFlags(member);
            if (!applyPermissions && !residence.isTrusted(member)) {
                return Result.failureCode(ResultCode.MEMBER_PADD_PERMISSION_MISMATCH,
                        Map.of("member", safeText(member)));
            }
            if (!applyPermissions && !Boolean.TRUE.equals(playerFlags.get(IGNITE_FLAG))) {
                return Result.failureCode(ResultCode.MEMBER_IGNITE_PERMISSION_MISMATCH,
                        Map.of("member", safeText(member)));
            }
            if (!applyPermissions && !Boolean.TRUE.equals(playerFlags.get(VEHICLE_DESTROY_FLAG))) {
                return Result.failureCode(ResultCode.MEMBER_VEHICLE_DESTROY_PERMISSION_MISMATCH,
                        Map.of("member", safeText(member)));
            }
            if (applyPermissions && !residence.getPermissions().setFlagGroupOnPlayer(
                     server.getConsoleSender(), member, padd.groupedFlag, "true", true)) {
                return Result.failureCode(ResultCode.MEMBER_PADD_PERMISSION_WRITE_FAILED,
                        Map.of("member", safeText(member)));
            }
            if (applyPermissions && !residence.getPermissions().setPlayerFlag(member, IGNITE_FLAG,
                    FlagPermissions.FlagState.TRUE)) {
                return Result.failureCode(ResultCode.MEMBER_IGNITE_PERMISSION_WRITE_FAILED,
                        Map.of("member", safeText(member)));
            }
            if (applyPermissions && !residence.getPermissions().setPlayerFlag(member, VEHICLE_DESTROY_FLAG,
                    FlagPermissions.FlagState.TRUE)) {
                return Result.failureCode(ResultCode.MEMBER_VEHICLE_DESTROY_PERMISSION_WRITE_FAILED,
                        Map.of("member", safeText(member)));
            }
        }
        return Result.successCode(ResultCode.PROJECTION_HEALTHY,
                Map.of("residence", safeText(name)));
    }

}
