package cn.tianji.town.integrations.residence;

import cn.tianji.town.core.land.InitialTerritory;
import cn.tianji.town.core.land.TownResidenceName;
import cn.tianji.town.core.ports.LandProtectionService;
import com.bekvon.bukkit.residence.api.ResidenceApi;
import com.bekvon.bukkit.residence.commands.padd;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ResidenceLandProtectionService implements LandProtectionService {
    private static final String SYSTEM_OWNER_HINT = "TianjiTownSystem";
    private static final String IGNITE_FLAG = "ignite";
    private static final List<String> PROTECTED_EXPLOSION_FLAGS = List.of(
            "explode", "tnt", "creeper");
    private final Server server;
    private final Set<String> managedNames;
    private final ThreadLocal<Integer> internalMutations = ThreadLocal.withInitial(() -> 0);

    public ResidenceLandProtectionService(Server server, Set<String> managedNames) {
        this.server = Objects.requireNonNull(server, "server");
        this.managedNames = Objects.requireNonNull(managedNames, "managedNames");
    }

    public boolean internalMutation() {
        return internalMutations.get() > 0;
    }

    @Override
    public Collision findNameCollision(String residenceName) {
        requireMainThread();
        String name = TownResidenceName.initial(residenceName);
        try {
            ClaimedResidence existing = manager().getByName(name);
            return existing == null ? Collision.none()
                    : new Collision(true, safeText(existing.getName()));
        } catch (RuntimeException | LinkageError exception) {
            return Collision.failureCode(ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", dependencyDetail(exception)));
        }
    }

    @Override
    public Collision findCollision(InitialTerritory territory) {
        requireMainThread();
        try {
            Bounds bounds = bounds(territory);
            if (bounds == null) {
                return Collision.failureCode(ResultCode.WORLD_UNLOADED,
                        Map.of("world", safeText(territory.center().worldName())));
            }
            ClaimedResidence collision = manager().collidesWithResidence(bounds.area());
            return collision == null ? Collision.none()
                    : new Collision(true, safeText(collision.getName()));
        } catch (RuntimeException | LinkageError exception) {
            return Collision.failureCode(ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", dependencyDetail(exception)));
        }
    }

    @Override
    public Inspection inspect(String residenceName, InitialTerritory territory,
                              Collection<UUID> members) {
        requireMainThread();
        String name = registerManagedName(residenceName);
        Bounds bounds = bounds(territory);
        if (bounds == null) {
            return Inspection.invalidCode(ResultCode.WORLD_UNLOADED,
                    Map.of("world", safeText(territory.center().worldName())));
        }
        try {
            ClaimedResidence residence = manager().getByName(name);
            if (residence == null) {
                return Inspection.missingCode(ResultCode.PROJECTION_MISSING,
                        Map.of("residence", safeText(name)));
            }
            Result verification = verifyAndApply(name, residence, bounds, members, false);
            return new Inspection(verification.success() ? ProjectionState.HEALTHY
                    : ProjectionState.INVALID, verification.code(), verification.parameters(),
                    verification.legacyMessage());
        } catch (RuntimeException | LinkageError exception) {
            return Inspection.invalidCode(ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", dependencyDetail(exception)));
        }
    }

    @Override
    public Result create(String residenceName, InitialTerritory territory, Collection<UUID> members) {
        requireMainThread();
        String name = registerManagedName(residenceName);
        try {
            ResidenceManager manager = manager();
            Bounds bounds = bounds(territory);
            if (bounds == null) {
                return Result.failureCode(ResultCode.WORLD_UNLOADED,
                        Map.of("world", safeText(territory.center().worldName())));
            }
            ClaimedResidence existing = manager.getByName(name);
            if (existing != null) {
                return verifyAndApply(name, existing, bounds, members, true);
            }
            ClaimedResidence collision = manager.collidesWithResidence(bounds.area());
            if (collision != null) {
                return Result.failureCode(ResultCode.INITIAL_PROJECTION_COLLISION,
                        Map.of("residence", safeText(collision.getName())));
            }
            if (!manager.addResidence(name, SYSTEM_OWNER_HINT, bounds.low(), bounds.high())) {
                return Result.failureCode(ResultCode.PROJECTION_CREATE_REJECTED,
                        Map.of("residence", safeText(name)));
            }
            ClaimedResidence created = manager.getByName(name);
            if (created == null) {
                return Result.failureCode(ResultCode.PROJECTION_CREATE_READBACK_FAILED,
                        Map.of("residence", safeText(name)));
            }
            return verifyAndApply(name, created, bounds, members, true);
        } catch (RuntimeException | LinkageError exception) {
            return Result.failureCode(ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", dependencyDetail(exception)));
        }
    }

    @Override
    public Result remove(String residenceName, InitialTerritory territory) {
        requireMainThread();
        String name = registerManagedName(residenceName);
        try {
            ResidenceManager manager = manager();
            ClaimedResidence existing = manager.getByName(name);
            if (existing == null) {
                return Result.successCode(ResultCode.PROJECTION_ALREADY_ABSENT);
            }
            Bounds bounds = bounds(territory);
            if (bounds == null) {
                return Result.failureCode(ResultCode.WORLD_UNLOADED,
                        Map.of("world", safeText(territory.center().worldName())));
            }
            if (!existing.isServerLand() || !matchesMainBounds(existing, bounds)) {
                return Result.failureCode(ResultCode.PROJECTION_REMOVE_REJECTED,
                        Map.of("residence", safeText(name)));
            }
            removeResidence(manager, name);
            return manager.getByName(name) == null
                    ? Result.successCode(ResultCode.PROJECTION_REMOVED)
                    : Result.failureCode(ResultCode.PROJECTION_STILL_PRESENT,
                    Map.of("residence", safeText(name)));
        } catch (RuntimeException | LinkageError exception) {
            return Result.failureCode(ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", dependencyDetail(exception)));
        }
    }

    @Override
    public Result reconcile(String residenceName, InitialTerritory territory, Collection<UUID> members,
                            boolean repair) {
        requireMainThread();
        String name = registerManagedName(residenceName);
        Bounds bounds = bounds(territory);
        if (bounds == null) {
            return Result.failureCode(ResultCode.WORLD_UNLOADED,
                    Map.of("world", safeText(territory.center().worldName())));
        }
        try {
            ResidenceManager manager = manager();
            ClaimedResidence residence = manager.getByName(name);
            if (residence == null) {
                return repair ? create(name, territory, members)
                        : Result.failureCode(ResultCode.PROJECTION_MISSING,
                        Map.of("residence", safeText(name)));
            }
            Result verification = verifyAndApply(name, residence, bounds, members, repair);
            if (verification.success() || !repair) {
                return verification;
            }
            if (!residence.isServerLand()) {
                return Result.failureCode(ResultCode.REBUILD_OWNER_MISMATCH,
                        Map.of("residence", safeText(name)));
            }
            // 名称来自数据库登记清单；重建前仍要求现有投影属于受控服务端账户。
            removeResidence(manager, name);
            return create(name, territory, members);
        } catch (RuntimeException | LinkageError exception) {
            return Result.failureCode(ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", dependencyDetail(exception)));
        }
    }

    @Override
    public boolean contains(String residenceName, UUID worldId,
                            int blockX, int blockY, int blockZ) {
        requireMainThread();
        String name = registerManagedName(residenceName);
        World world = server.getWorld(worldId);
        if (world == null) {
            return false;
        }
        try {
            ResidenceManager manager = manager();
            ClaimedResidence residence = manager.getByName(name);
            return residence != null && residence.isServerLand()
                    && residence.containsLoc(new Location(world, blockX + 0.5D,
                    blockY + 0.5D, blockZ + 0.5D));
        } catch (RuntimeException | LinkageError exception) {
            return false;
        }
    }

    @Override
    public boolean isControlledProjection(String residenceName) {
        requireMainThread();
        String name = TownResidenceName.initial(residenceName);
        ClaimedResidence residence = manager().getByName(name);
        return residence != null && residence.isServerLand();
    }

    @Override
    public Result setTeleportPoint(String residenceName, UUID worldId, String worldName,
                                   double x, double y, double z, float yaw, float pitch) {
        requireMainThread();
        String name = registerManagedName(residenceName);
        try {
            World world = server.getWorld(worldId);
            if (world == null) {
                world = server.getWorld(worldName);
            }
            if (world == null) {
                return Result.failureCode(ResultCode.WORLD_UNLOADED,
                        Map.of("world", safeText(worldName)));
            }
            ResidenceManager manager = manager();
            ClaimedResidence residence = manager.getByName(name);
            if (residence == null || !residence.isServerLand()) {
                return Result.failureCode(ResultCode.CONTROLLED_PROJECTION_MISSING,
                        Map.of("residence", safeText(name)));
            }
            Location location = new Location(world, x, y, z, yaw, pitch);
            if (!residence.containsLoc(location)) {
                return Result.failureCode(ResultCode.TELEPORT_POINT_OUTSIDE_PROJECTION);
            }
            // Residence 6 的公开 API 只提供基于 Player 当前位置的 setTpLoc；公开的
            // tpLoc 数据字段是同一 API 对非玩家调用方提供的坐标入口。
            residence.tpLoc = location.toVector();
            residence.PitchYaw = new Vector(location.getPitch(), location.getYaw(), 0.0D);
            // 立即持久化，避免仅修改内存对象在 Residence 重载或崩溃后丢失传送点。
            manager.save();
            return Result.successCode(ResultCode.TELEPORT_POINT_UPDATED,
                    Map.of("residence", safeText(name)));
        } catch (RuntimeException | LinkageError exception) {
            return Result.failureCode(ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", dependencyDetail(exception)));
        }
    }

    @Override
    public Inspection inspect(String residenceName, List<Area> areas, Collection<UUID> members) {
        requireMainThread();
        String name = registerManagedName(residenceName);
        try {
            ClaimedResidence residence = manager().getByName(name);
            if (residence == null) {
                return Inspection.missingCode(ResultCode.PROJECTION_MISSING,
                        Map.of("residence", safeText(name)));
            }
            Result result = verifyAndApply(name, residence, areas, members, false);
            return new Inspection(result.success() ? ProjectionState.HEALTHY
                    : ProjectionState.INVALID, result.code(), result.parameters(),
                    result.legacyMessage());
        } catch (RuntimeException | LinkageError exception) {
            return Inspection.invalidCode(ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", dependencyDetail(exception)));
        }
    }

    @Override
    public Result addArea(String residenceName, Area area, Collection<UUID> members) {
        requireMainThread();
        String name = registerManagedName(residenceName);
        ResidenceManager manager = null;
        ClaimedResidence residence = null;
        boolean areaAdded = false;
        try {
            manager = manager();
            residence = manager.getByName(name);
            if (residence == null) {
                return Result.failureCode(ResultCode.EXPANSION_PROJECTION_MISSING,
                        Map.of("residence", safeText(name)));
            }
            if (!residence.isServerLand()) {
                return Result.failureCode(ResultCode.PROJECTION_OWNER_NOT_CONTROLLED,
                        Map.of("owner", safeText(residence.getOwner())));
            }
            Bounds bounds = bounds(area.territory());
            if (bounds == null) {
                return Result.failureCode(ResultCode.WORLD_UNLOADED,
                        Map.of("world", safeText(area.territory().center().worldName())));
            }
            CuboidArea existing = residence.getArea(area.name());
            if (existing != null) {
                return matchesBounds(existing, bounds)
                        ? verifyPermissions(name, residence, members, true)
                        : Result.failureCode(ResultCode.AREA_BOUNDS_MISMATCH,
                        Map.of("area", safeText(area.name())));
            }
            String collision = manager.checkAreaCollision(bounds.area(), residence);
            if (collision != null) {
                return Result.failureCode(ResultCode.EXPANSION_COLLISION,
                        Map.of("residence", safeText(collision)));
            }
            if (!residence.addArea(bounds.area(), area.name())) {
                return Result.failureCode(ResultCode.AREA_ADD_REJECTED,
                        Map.of("area", safeText(area.name())));
            }
            areaAdded = true;
            manager.calculateChunks(residence);
            Result permissions = verifyPermissions(name, residence, members, true);
            if (!permissions.success()) {
                removeArea(residence, area.name());
                manager.calculateChunks(residence);
            }
            return permissions;
        } catch (RuntimeException | LinkageError exception) {
            if (areaAdded && residence != null) {
                try {
                    removeArea(residence, area.name());
                    if (manager != null) {
                        manager.calculateChunks(residence);
                    }
                } catch (RuntimeException | LinkageError cleanupFailure) {
                    return Result.failureCode(ResultCode.AREA_ADD_ROLLBACK_FAILED,
                            Map.of("detail", dependencyDetail(exception),
                                    "cleanup", dependencyDetail(cleanupFailure)));
                }
            }
            return Result.failureCode(ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", dependencyDetail(exception)));
        }
    }

    @Override
    public Result removeArea(String residenceName, String areaName) {
        requireMainThread();
        String name = registerManagedName(residenceName);
        try {
            ResidenceManager manager = manager();
            ClaimedResidence residence = manager.getByName(name);
            if (residence == null || residence.getArea(areaName) == null) {
                return Result.successCode(ResultCode.EXPANSION_AREA_ALREADY_ABSENT);
            }
            if (!residence.isServerLand()) {
                return Result.failureCode(ResultCode.AREA_OWNER_NOT_CONTROLLED);
            }
            if (residence.getArea(areaName) == residence.getMainArea()) {
                return Result.failureCode(ResultCode.MAIN_AREA_REMOVAL_REJECTED);
            }
            removeArea(residence, areaName);
            manager.calculateChunks(residence);
            return residence.getArea(areaName) != null
                    ? Result.failureCode(ResultCode.AREA_STILL_PRESENT,
                    Map.of("area", safeText(areaName)))
                    : Result.successCode(ResultCode.EXPANSION_AREA_REMOVED,
                    Map.of("area", safeText(areaName)));
        } catch (RuntimeException | LinkageError exception) {
            return Result.failureCode(ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", dependencyDetail(exception)));
        }
    }

    @Override
    public boolean hasArea(String residenceName, String areaName) {
        requireMainThread();
        String name = registerManagedName(residenceName);
        ClaimedResidence residence = manager().getByName(name);
        return residence != null && residence.getArea(areaName) != null;
    }

    @Override
    public Result reconcile(String residenceName, List<Area> areas, Collection<UUID> members,
                            boolean repair) {
        requireMainThread();
        String name = registerManagedName(residenceName);
        try {
            ResidenceManager manager = manager();
            ClaimedResidence residence = manager.getByName(name);
            if (residence == null) {
                if (!repair) {
                    return Result.failureCode(ResultCode.PROJECTION_MISSING,
                            Map.of("residence", safeText(name)));
                }
                return createFromDatabase(name, areas, members);
            }
            Result current = verifyAndApply(name, residence, areas, members, repair);
            if (current.success() || !repair) {
                return current;
            }
            for (Area area : areas) {
                Bounds bounds = bounds(area.territory());
                if (bounds == null) {
                    return Result.failureCode(ResultCode.WORLD_UNLOADED,
                            Map.of("world", safeText(area.territory().center().worldName())));
                }
                CuboidArea actual = residence.getArea(area.name());
                if (actual == null) {
                    Result added = addArea(name, area, members);
                    if (!added.success()) {
                        return added;
                    }
                } else if (!matchesBounds(actual, bounds)) {
                    return rebuildFromDatabase(manager, name, residence, areas, members);
                }
            }
            Result repaired = verifyAndApply(name, residence, areas, members, true);
            return repaired.success() ? repaired
                    : rebuildFromDatabase(manager, name, residence, areas, members);
        } catch (RuntimeException | LinkageError exception) {
            return Result.failureCode(ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", dependencyDetail(exception)));
        }
    }

    private Result createFromDatabase(String name, List<Area> areas,
                                      Collection<UUID> members) {
        Area main = areas.stream().filter(area -> area.name().equalsIgnoreCase("main"))
                .findFirst().orElse(null);
        if (main == null) {
            return Result.failureCode(ResultCode.MAIN_AREA_MISSING);
        }
        Result created = create(name, main.territory(), members);
        if (!created.success()) {
            return created;
        }
        for (Area area : areas) {
            if (area.equals(main)) {
                continue;
            }
            Result added = addArea(name, area, members);
            if (!added.success()) {
                cleanupIncompleteProjection(name);
                return Result.failureCode(ResultCode.DATABASE_AREA_CREATE_FAILED,
                        Map.of("area", safeText(area.name())));
            }
        }
        return Result.successCode(ResultCode.PROJECTION_REBUILT_FROM_DATABASE,
                Map.of("residence", safeText(name)));
    }

    private Result rebuildFromDatabase(ResidenceManager manager, String name,
                                       ClaimedResidence residence, List<Area> areas,
                                       Collection<UUID> members) {
        if (!residence.isServerLand()) {
            return Result.failureCode(ResultCode.REBUILD_OWNER_MISMATCH,
                    Map.of("residence", safeText(name)));
        }
        for (Area area : areas) {
            Bounds bounds = bounds(area.territory());
            if (bounds == null) {
                return Result.failureCode(ResultCode.WORLD_UNLOADED,
                        Map.of("world", safeText(area.territory().center().worldName())));
            }
            String collision = manager.checkAreaCollision(bounds.area(), residence);
            if (collision != null) {
                return Result.failureCode(ResultCode.DATABASE_REBUILD_COLLISION,
                        Map.of("residence", safeText(collision)));
            }
        }
        // 只有受控投影且数据库目标区域无外部冲突时，才允许以数据库版本整体替换。
        removeResidence(manager, name);
        if (manager.getByName(name) != null) {
            return Result.failureCode(ResultCode.DATABASE_REBUILD_REMOVE_FAILED,
                    Map.of("residence", safeText(name)));
        }
        Result rebuilt = createFromDatabase(name, areas, members);
        return rebuilt.success()
                ? Result.successCode(ResultCode.PROJECTION_AUTO_REPAIRED,
                Map.of("residence", safeText(name)))
                : rebuilt;
    }

    private void cleanupIncompleteProjection(String name) {
        ResidenceManager manager = manager();
        ClaimedResidence incomplete = manager.getByName(name);
        if (incomplete != null && incomplete.isServerLand()) {
            removeResidence(manager, name);
        }
    }

    private Result verifyAndApply(String name, ClaimedResidence residence, List<Area> areas,
                                  Collection<UUID> members, boolean applyPermissions) {
        if (!residence.isServerLand()) {
            return Result.failureCode(ResultCode.PROJECTION_OWNER_NOT_CONTROLLED,
                    Map.of("owner", safeText(residence.getOwner())));
        }
        if (residence.getAreaCount() != areas.size()) {
            return Result.failureCode(ResultCode.AREA_COUNT_MISMATCH);
        }
        Map<String, CuboidArea> actual = residence.getAreaMap();
        for (Area area : areas) {
            Bounds bounds = bounds(area.territory());
            if (bounds == null) {
                return Result.failureCode(ResultCode.WORLD_UNLOADED,
                        Map.of("world", safeText(area.territory().center().worldName())));
            }
            CuboidArea cuboid = actual.get(area.name().toLowerCase(java.util.Locale.ROOT));
            if (cuboid == null) {
                cuboid = residence.getArea(area.name());
            }
            if (cuboid == null || !matchesBounds(cuboid, bounds)) {
                return Result.failureCode(ResultCode.AREA_MISSING_OR_BOUNDS_MISMATCH,
                        Map.of("area", safeText(area.name())));
            }
        }
        return verifyPermissions(name, residence, members, applyPermissions);
    }

    private Result verifyAndApply(String name, ClaimedResidence residence, Bounds bounds,
                                  Collection<UUID> members, boolean applyPermissions) {
        ResidenceManager manager = manager();
        if (!matchesBounds(residence, bounds)) {
            return Result.failureCode(ResultCode.PROJECTION_BOUNDS_OR_AREA_COUNT_MISMATCH);
        }
        // Residence 会按服务端 UUID 动态返回 Server_Land 等展示名，不能依赖展示名判断所有权。
        if (!residence.isServerLand()) {
            return Result.failureCode(ResultCode.PROJECTION_OWNER_NOT_CONTROLLED,
                    Map.of("owner", safeText(residence.getOwner())));
        }
        Location[] checks = {bounds.low(), bounds.high(),
                new Location(bounds.low().getWorld(), bounds.low().getX(), bounds.low().getY(),
                        bounds.high().getZ()),
                new Location(bounds.low().getWorld(), bounds.high().getX(), bounds.high().getY(),
                        bounds.low().getZ())};
        for (Location check : checks) {
            ClaimedResidence found = manager.getByLoc(check);
            if (found == null || !name.equalsIgnoreCase(found.getName())) {
                return Result.failureCode(ResultCode.PROJECTION_BOUNDARY_MISMATCH);
            }
        }
        return verifyPermissions(name, residence, members, applyPermissions);
    }

    private Result verifyPermissions(String name, ClaimedResidence residence,
                                     Collection<UUID> members, boolean applyPermissions) {
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
        }
        return Result.successCode(ResultCode.PROJECTION_HEALTHY,
                Map.of("residence", safeText(name)));
    }

    private Bounds bounds(InitialTerritory territory) {
        World world = server.getWorld(territory.center().worldId());
        if (world == null) {
            world = server.getWorld(territory.center().worldName());
        }
        if (world == null) {
            return null;
        }
        int minimumX = territory.minimumBlockX();
        int minimumZ = territory.minimumBlockZ();
        int maximumX = territory.maximumBlockX();
        int maximumZ = territory.maximumBlockZ();
        Location low = new Location(world, minimumX, world.getMinHeight(), minimumZ);
        Location high = new Location(world, maximumX, world.getMaxHeight() - 1, maximumZ);
        return new Bounds(low, high, new CuboidArea(low, high));
    }

    private ResidenceManager manager() {
        Plugin residence = server.getPluginManager().getPlugin("Residence");
        if (residence == null || !residence.isEnabled()) {
            throw new IllegalStateException("RESIDENCE_PLUGIN_UNAVAILABLE");
        }
        ResidenceManager manager;
        try {
            manager = (ResidenceManager) ResidenceApi.getResidenceManager();
        } catch (RuntimeException | LinkageError exception) {
            throw new IllegalStateException("RESIDENCE_API_CLASSLOADER_UNAVAILABLE", exception);
        }
        if (manager == null) {
            throw new IllegalStateException("RESIDENCE_MANAGER_UNAVAILABLE");
        }
        return manager;
    }

    private static String dependencyDetail(Throwable throwable) {
        String message = throwable.getMessage();
        String detail = message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
        return safeText(detail);
    }

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    private static boolean matchesBounds(ClaimedResidence residence, Bounds bounds) {
        return residence.getAreaCount() == 1
                && matchesBounds(residence.getMainArea(), bounds);
    }

    private static boolean matchesMainBounds(ClaimedResidence residence, Bounds bounds) {
        return matchesBounds(residence.getMainArea(), bounds);
    }

    private static boolean matchesBounds(CuboidArea area, Bounds bounds) {
        return area.getLowVector().equals(bounds.area().getLowVector())
                && area.getHighVector().equals(bounds.area().getHighVector());
    }

    private void requireMainThread() {
        if (!server.isPrimaryThread()) {
            throw new IllegalStateException("RESIDENCE_MAIN_THREAD_REQUIRED");
        }
    }

    private void removeResidence(ResidenceManager manager, String name) {
        withInternalMutation(() -> manager.removeResidence(name));
    }

    private void removeArea(ClaimedResidence residence, String areaName) {
        withInternalMutation(() -> residence.removeArea(areaName));
    }

    private void withInternalMutation(Runnable action) {
        internalMutations.set(internalMutations.get() + 1);
        try {
            action.run();
        } finally {
            int remaining = internalMutations.get() - 1;
            if (remaining == 0) {
                internalMutations.remove();
            } else {
                internalMutations.set(remaining);
            }
        }
    }

    private String registerManagedName(String residenceName) {
        String normalized = TownResidenceName.initial(residenceName);
        managedNames.add(normalized);
        return normalized;
    }

    private record Bounds(Location low, Location high, CuboidArea area) {
    }
}
