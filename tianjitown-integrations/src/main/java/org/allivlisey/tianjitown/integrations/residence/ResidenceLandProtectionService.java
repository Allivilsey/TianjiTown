package org.allivlisey.tianjitown.integrations.residence;

import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.land.TownResidenceName;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import com.bekvon.bukkit.residence.api.ResidenceApi;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import org.allivlisey.tianjitown.core.land.TownReservation;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.allivlisey.tianjitown.integrations.residence.ResidenceGeometry.Bounds;
import static org.allivlisey.tianjitown.integrations.residence.ResidenceGeometry.*;

public final class ResidenceLandProtectionService implements LandProtectionService {
    private static final String SYSTEM_OWNER_HINT = "TianjiTownSystem";
    private final Map<String, TownReservation> reservations = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean reservationsLoaded;

    public void reserve(String name, InitialTerritory territory) {
        reservations.put(TownResidenceName.initial(name), new TownReservation(territory));
    }

    public void reservationsLoaded() { reservationsLoaded = true; }
    boolean reservationsReady() { return reservationsLoaded; }

    String reservationCollision(CuboidArea area, String ignoredName) {
        for (var entry : reservations.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(ignoredName)) continue;
            if (entry.getValue().overlaps(area.getWorld().getUID(),
                    Math.floorDiv(area.getLowVector().getBlockX(), 16),
                    Math.floorDiv(area.getHighVector().getBlockX(), 16),
                    Math.floorDiv(area.getLowVector().getBlockZ(), 16),
                    Math.floorDiv(area.getHighVector().getBlockZ(), 16))) return entry.getKey();
        }
        return null;
    }

    private final Server server;
    private final Set<String> managedNames;
    private final ResidenceMutationContext context;
    private final ResidenceGeometry geometry;
    private final ResidencePermissionSync permissions;
    private final ResidenceProjectionRepair repairs;

    public ResidenceLandProtectionService(Server server, Set<String> managedNames) {
        this.server = Objects.requireNonNull(server, "server");
        this.context = new ResidenceMutationContext(server);
        this.geometry = new ResidenceGeometry(server);
        this.permissions = new ResidencePermissionSync(server, context);
        this.repairs = new ResidenceProjectionRepair(this, context, geometry);
        this.managedNames = Objects.requireNonNull(managedNames, "managedNames");
    }

    public boolean internalMutation() {
        return context.internalMutation();
    }

    @Override
    public Result setMessages(String residenceName, String enterMessage, String leaveMessage) {
        context.requireMainThread();
        String name = TownResidenceName.initial(residenceName);
        try {
            ClaimedResidence residence = manager().getByName(name);
            if (residence == null || !managedNames.contains(name) || !residence.isServerLand()) {
                return Result.failureCode(ResultCode.CONTROLLED_PROJECTION_MISSING);
            }
            residence.setEnterMessage(enterMessage);
            residence.setLeaveMessage(leaveMessage);
            return Result.successCode(ResultCode.PROJECTION_HEALTHY, Map.of("residence", name));
        } catch (RuntimeException | LinkageError exception) {
            return Result.failureCode(ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", dependencyDetail(exception)));
        }
    }

    @Override
    public Collision findNameCollision(String residenceName) {
        context.requireMainThread();
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
        context.requireMainThread();
        try {
            Bounds bounds = geometry.bounds(territory);
            if (bounds == null) {
                return Collision.failureCode(ResultCode.WORLD_UNLOADED,
                        Map.of("world", safeText(territory.center().worldName())));
            }
            String reserved = reservationCollision(bounds.area(), null);
            if (reserved != null) return new Collision(true, reserved);
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
        context.requireMainThread();
        String name = registerManagedName(residenceName);
        Bounds bounds = geometry.bounds(territory);
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
                    : ProjectionState.INVALID, verification.code(), verification.parameters());
        } catch (RuntimeException | LinkageError exception) {
            return Inspection.invalidCode(ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", dependencyDetail(exception)));
        }
    }

    @Override
    public Result create(String residenceName, InitialTerritory territory, Collection<UUID> members) {
        context.requireMainThread();
        String name = registerManagedName(residenceName);
        try {
            ResidenceManager manager = manager();
            Bounds bounds = geometry.bounds(territory);
            if (bounds == null) {
                return Result.failureCode(ResultCode.WORLD_UNLOADED,
                        Map.of("world", safeText(territory.center().worldName())));
            }
            ClaimedResidence existing = manager.getByName(name);
            // Reserve the entire grid before creating the initial protected area.
            for (InitialTerritory unit : new TownReservation(territory).units()) {
                Bounds reservedBounds = geometry.bounds(unit);
                String collisionName = reservationCollision(reservedBounds.area(), name);
                if (collisionName == null) {
                    ClaimedResidence other = manager.collidesWithResidence(reservedBounds.area());
                    if (other != null && other != existing) collisionName = other.getName();
                }
                if (collisionName != null) return Result.failureCode(ResultCode.INITIAL_PROJECTION_COLLISION,
                        Map.of("residence", safeText(collisionName)));
            }
            reserve(name, territory);
            if (existing != null) {
                return verifyAndApply(name, existing, bounds, members, true);
            }
            ClaimedResidence collision = manager.collidesWithResidence(bounds.area());
            if (collision != null) {
                return Result.failureCode(ResultCode.INITIAL_PROJECTION_COLLISION,
                        Map.of("residence", safeText(collision.getName())));
            }
            boolean[] createdSuccessfully = {false};
            context.withInternalMutation(() -> createdSuccessfully[0] =
                    manager.addResidence(name, SYSTEM_OWNER_HINT, bounds.low(), bounds.high()));
            if (!createdSuccessfully[0]) {
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
        context.requireMainThread();
        String name = registerManagedName(residenceName);
        try {
            ResidenceManager manager = manager();
            ClaimedResidence existing = manager.getByName(name);
            if (existing == null) {
                reservations.remove(name);
                return Result.successCode(ResultCode.PROJECTION_ALREADY_ABSENT);
            }
            Bounds bounds = geometry.bounds(territory);
            if (bounds == null) {
                return Result.failureCode(ResultCode.WORLD_UNLOADED,
                        Map.of("world", safeText(territory.center().worldName())));
            }
            if (!existing.isServerLand() || !matchesMainBounds(existing, bounds)) {
                return Result.failureCode(ResultCode.PROJECTION_REMOVE_REJECTED,
                        Map.of("residence", safeText(name)));
            }
            context.removeResidence(manager, name);
            if (manager.getByName(name) == null) reservations.remove(name);
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
        context.requireMainThread();
        String name = registerManagedName(residenceName);
        Bounds bounds = geometry.bounds(territory);
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
            context.removeResidence(manager, name);
            return create(name, territory, members);
        } catch (RuntimeException | LinkageError exception) {
            return Result.failureCode(ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", dependencyDetail(exception)));
        }
    }

    @Override
    public boolean contains(String residenceName, UUID worldId,
                            int blockX, int blockY, int blockZ) {
        context.requireMainThread();
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
        context.requireMainThread();
        String name = TownResidenceName.initial(residenceName);
        ClaimedResidence residence = manager().getByName(name);
        return residence != null && residence.isServerLand();
    }

    @Override
    public Result setTeleportPoint(String residenceName, UUID worldId, String worldName,
                                   double x, double y, double z, float yaw, float pitch) {
        context.requireMainThread();
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
        context.requireMainThread();
        String name = registerManagedName(residenceName);
        try {
            ClaimedResidence residence = manager().getByName(name);
            if (residence == null) {
                return Inspection.missingCode(ResultCode.PROJECTION_MISSING,
                        Map.of("residence", safeText(name)));
            }
            Result result = verifyAndApply(name, residence, areas, members, false);
            return new Inspection(result.success() ? ProjectionState.HEALTHY
                    : ProjectionState.INVALID, result.code(), result.parameters());
        } catch (RuntimeException | LinkageError exception) {
            return Inspection.invalidCode(ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", dependencyDetail(exception)));
        }
    }

    @Override
    public Result addArea(String residenceName, Area area, Collection<UUID> members) {
        context.requireMainThread();
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
            Bounds bounds = geometry.bounds(area.territory());
            if (bounds == null) {
                return Result.failureCode(ResultCode.WORLD_UNLOADED,
                        Map.of("world", safeText(area.territory().center().worldName())));
            }
            CuboidArea existing = residence.getArea(area.name());
            if (existing != null) {
                return matchesBounds(existing, bounds)
                        ? this.permissions.verifyPermissions(name, residence, members, true)
                        : Result.failureCode(ResultCode.AREA_BOUNDS_MISMATCH,
                        Map.of("area", safeText(area.name())));
            }
            String collision = manager.checkAreaCollision(bounds.area(), residence);
            if (collision != null) {
                return Result.failureCode(ResultCode.EXPANSION_COLLISION,
                        Map.of("residence", safeText(collision)));
            }
            String reservedCollision = reservationCollision(bounds.area(), name);
            if (reservedCollision != null) return Result.failureCode(ResultCode.EXPANSION_COLLISION,
                    Map.of("residence", safeText(reservedCollision)));
            boolean[] addedSuccessfully = {false};
            ClaimedResidence targetResidence = residence;
            context.withInternalMutation(() -> addedSuccessfully[0] =
                    targetResidence.addArea(bounds.area(), area.name()));
            if (!addedSuccessfully[0]) {
                return Result.failureCode(ResultCode.AREA_ADD_REJECTED,
                        Map.of("area", safeText(area.name())));
            }
            areaAdded = true;
            manager.calculateChunks(residence);
            Result permissions = this.permissions.verifyPermissions(name, residence, members, true);
            if (!permissions.success()) {
                context.removeArea(residence, area.name());
                manager.calculateChunks(residence);
            }
            return permissions;
        } catch (RuntimeException | LinkageError exception) {
            if (areaAdded && residence != null) {
                try {
                    context.removeArea(residence, area.name());
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
        context.requireMainThread();
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
            context.removeArea(residence, areaName);
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
        context.requireMainThread();
        String name = registerManagedName(residenceName);
        ClaimedResidence residence = manager().getByName(name);
        return residence != null && residence.getArea(areaName) != null;
    }

    @Override
    public Result reconcile(String residenceName, List<Area> areas, Collection<UUID> members, boolean repair) {
        return repairs.reconcile(residenceName, areas, members, repair);
    }

    Result verifyAndApply(String name, ClaimedResidence residence, List<Area> areas,
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
            Bounds bounds = geometry.bounds(area.territory());
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
        return permissions.verifyPermissions(name, residence, members, applyPermissions);
    }

    Result verifyAndApply(String name, ClaimedResidence residence, Bounds bounds,
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
        return permissions.verifyPermissions(name, residence, members, applyPermissions);
    }

    ResidenceManager manager() {
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

    static String dependencyDetail(Throwable throwable) {
        String message = throwable.getMessage();
        String detail = message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
        return safeText(detail);
    }

    static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    String registerManagedName(String residenceName) {
        String normalized = TownResidenceName.initial(residenceName);
        managedNames.add(normalized);
        return normalized;
    }


}
