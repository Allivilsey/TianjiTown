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
    private static final String MONSTER_ENTRY_FLAG = "nomobs";
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
        if (!applyPermissions && !residence.getPermissions().has(MONSTER_ENTRY_FLAG, false)) {
            return Result.failureCode(ResultCode.MONSTER_ENTRY_FLAG_MISMATCH);
        }
        if (applyPermissions && !residence.getPermissions().setFlag(
                server.getConsoleSender(), MONSTER_ENTRY_FLAG, FlagPermissions.FlagState.TRUE, true,
                false)) {
            return Result.failureCode(ResultCode.MONSTER_ENTRY_FLAG_WRITE_FAILED);
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
            if (applyPermissions && !applyPaddSilently(residence, member)) {
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

    private boolean applyPaddSilently(ClaimedResidence residence, UUID member) {
        Map<String, FlagPermissions.FlagState> flags = paddFlags();
        if (flags.isEmpty()) return false;
        for (var flag : flags.entrySet()) {
            if (!setGroupedFlagSilently(residence.getPermissions(), member, flag.getKey(), flag.getValue())) {
                return false;
            }
        }
        var player = com.bekvon.bukkit.residence.containers.ResidencePlayer.get(member);
        if (player != null) player.addTrustedResidence(residence);
        return true;
    }

    private boolean setGroupedFlagSilently(com.bekvon.bukkit.residence.protection.ResidencePermissions permissions,
            UUID member, String flag, FlagPermissions.FlagState state) {
        try {
            // The published compile API predates the typed seven-argument setter in 6.0.2.4.
            var method = permissions.getClass().getMethod("setPlayerFlag",
                    org.bukkit.command.CommandSender.class, UUID.class, String.class,
                    FlagPermissions.FlagState.class, boolean.class, boolean.class, boolean.class);
            boolean ignoreAccess = (boolean) FlagPermissions.class.getMethod("isIgnoreGroupedFlagsAccess").invoke(null);
            return (boolean) method.invoke(permissions, server.getConsoleSender(), member,
                    flag, state, true, false, !ignoreAccess);
        } catch (NoSuchMethodException exception) {
            return permissions.setPlayerFlag(server.getConsoleSender(), member, flag,
                    state.name().toLowerCase(java.util.Locale.ROOT), true, false);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Residence 静默授权失败", exception);
        }
    }

    static Map<String, FlagPermissions.FlagState> paddFlags() {
        try {
            var field = FlagPermissions.class.getDeclaredField("validFlagGroups");
            field.setAccessible(true);
            Object group = ((Map<?, ?>) field.get(null)).get(padd.groupedFlag);
            Map<String, FlagPermissions.FlagState> result = new java.util.LinkedHashMap<>();
            if (group instanceof Map<?, ?> values) {
                values.forEach((key, value) -> result.put((String) key, (FlagPermissions.FlagState) value));
            } else if (group instanceof Collection<?> values) {
                // Public 6.0.0.1 API describes groups as sets of true flags.
                values.forEach(key -> result.put((String) key, FlagPermissions.FlagState.TRUE));
            }
            return Map.copyOf(result);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("无法读取 Residence padd 权限组", exception);
        }
    }
}
