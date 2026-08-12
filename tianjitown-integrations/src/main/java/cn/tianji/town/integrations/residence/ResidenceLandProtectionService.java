package cn.tianji.town.integrations.residence;

import cn.tianji.town.core.land.InitialTerritory;
import cn.tianji.town.core.land.TownResidenceName;
import cn.tianji.town.core.ports.LandProtectionService;
import com.bekvon.bukkit.residence.api.ResidenceApi;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ResidenceLandProtectionService implements LandProtectionService {
    private static final String SYSTEM_OWNER_HINT = "TianjiTownSystem";
    private static final Collection<String> MEMBER_FLAGS =
            java.util.List.of("build", "destroy", "place", "container", "use", "move");
    private final Server server;
    private final Set<String> managedNames;

    public ResidenceLandProtectionService(Server server, Set<String> managedNames) {
        this.server = Objects.requireNonNull(server, "server");
        this.managedNames = Objects.requireNonNull(managedNames, "managedNames");
    }

    @Override
    public Collision findCollision(InitialTerritory territory) {
        requireMainThread();
        try {
            Bounds bounds = bounds(territory);
            if (bounds == null) {
                return new Collision(true, "世界未加载");
            }
            ClaimedResidence collision = manager().collidesWithResidence(bounds.area());
            return collision == null ? Collision.none() : new Collision(true, collision.getName());
        } catch (RuntimeException | LinkageError exception) {
            return new Collision(true, dependencyError(exception));
        }
    }

    @Override
    public Inspection inspect(String residenceName, InitialTerritory territory,
                              Collection<UUID> members) {
        requireMainThread();
        String name = registerManagedName(residenceName);
        Bounds bounds = bounds(territory);
        if (bounds == null) {
            return Inspection.invalid("目标世界未加载: " + territory.center().worldName());
        }
        ClaimedResidence residence = manager().getByName(name);
        if (residence == null) {
            return Inspection.missing("缺少 Residence 投影 " + name);
        }
        Result verification = verifyAndApply(name, residence, bounds, members, false);
        return verification.success()
                ? Inspection.healthy(verification.message())
                : Inspection.invalid(verification.message());
    }

    @Override
    public Result create(String residenceName, InitialTerritory territory, Collection<UUID> members) {
        requireMainThread();
        String name = registerManagedName(residenceName);
        try {
            ResidenceManager manager = manager();
            Bounds bounds = bounds(territory);
            if (bounds == null) {
                return Result.failure("目标世界未加载: " + territory.center().worldName());
            }
            ClaimedResidence existing = manager.getByName(name);
            if (existing != null) {
                return verifyAndApply(name, existing, bounds, members, true);
            }
            ClaimedResidence collision = manager.collidesWithResidence(bounds.area());
            if (collision != null) {
                return Result.failure("目标 3×3 区块与 Residence 冲突: " + collision.getName());
            }
            if (!manager.addResidence(name, SYSTEM_OWNER_HINT, bounds.low(), bounds.high())) {
                return Result.failure("Residence API 拒绝创建系统领地 " + name);
            }
            ClaimedResidence created = manager.getByName(name);
            if (created == null) {
                return Result.failure("Residence 创建后无法按名称读取: " + name);
            }
            return verifyAndApply(name, created, bounds, members, true);
        } catch (RuntimeException | LinkageError exception) {
            return Result.failure(dependencyError(exception));
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
                return Result.ok("Residence 已不存在");
            }
            Bounds bounds = bounds(territory);
            if (bounds == null) {
                return Result.failure("目标世界未加载: " + territory.center().worldName());
            }
            if (!existing.isServerLand() || !matchesMainBounds(existing, bounds)) {
                return Result.failure("拒绝移除同名但并非当前小镇投影的 Residence: " + name);
            }
            manager.removeResidence(name);
            return manager.getByName(name) == null
                    ? Result.ok("Residence 已移除")
                    : Result.failure("Residence 移除后仍可读取");
        } catch (RuntimeException | LinkageError exception) {
            return Result.failure(dependencyError(exception));
        }
    }

    @Override
    public Result reconcile(String residenceName, InitialTerritory territory, Collection<UUID> members,
                            boolean repair) {
        requireMainThread();
        String name = registerManagedName(residenceName);
        Bounds bounds = bounds(territory);
        if (bounds == null) {
            return Result.failure("目标世界未加载: " + territory.center().worldName());
        }
        ResidenceManager manager = manager();
        ClaimedResidence residence = manager.getByName(name);
        if (residence == null) {
            return repair ? create(name, territory, members)
                    : Result.failure("缺少 Residence 投影 " + name);
        }
        Result verification = verifyAndApply(name, residence, bounds, members, repair);
        if (verification.success() || !repair) {
            return verification;
        }
        if (!residence.isServerLand()) {
            return Result.failure("同名 Residence 不属于受控服务端账户，拒绝重建: " + name);
        }
        // 名称来自数据库登记清单；重建前仍要求现有投影属于受控服务端账户。
        manager.removeResidence(name);
        return create(name, territory, members);
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
            ClaimedResidence residence = manager().getByName(name);
            return residence != null && residence.isServerLand()
                    && residence.containsLoc(new Location(world, blockX + 0.5D,
                    blockY + 0.5D, blockZ + 0.5D));
        } catch (RuntimeException | LinkageError exception) {
            return false;
        }
    }

    @Override
    public Inspection inspect(String residenceName, List<Area> areas, Collection<UUID> members) {
        requireMainThread();
        String name = registerManagedName(residenceName);
        ClaimedResidence residence = manager().getByName(name);
        if (residence == null) {
            return Inspection.missing("缺少 Residence 投影 " + name);
        }
        Result result = verifyAndApply(name, residence, areas, members, false);
        return result.success() ? Inspection.healthy(result.message())
                : Inspection.invalid(result.message());
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
                return Result.failure("缺少待扩张的 Residence 投影 " + name);
            }
            if (!residence.isServerLand()) {
                return Result.failure("Residence 所有者不是受控服务端账户");
            }
            Bounds bounds = bounds(area.territory());
            if (bounds == null) {
                return Result.failure("目标世界未加载: " + area.territory().center().worldName());
            }
            CuboidArea existing = residence.getArea(area.name());
            if (existing != null) {
                return matchesBounds(existing, bounds)
                        ? verifyPermissions(name, residence, members, true)
                        : Result.failure("同名 Residence 区域边界不一致: " + area.name());
            }
            String collision = manager.checkAreaCollision(bounds.area(), residence);
            if (collision != null) {
                return Result.failure("目标 3×3 区块与 Residence 冲突: " + collision);
            }
            if (!residence.addArea(bounds.area(), area.name())) {
                return Result.failure("Residence API 拒绝添加区域 " + area.name());
            }
            areaAdded = true;
            manager.calculateChunks(residence);
            Result permissions = verifyPermissions(name, residence, members, true);
            if (!permissions.success()) {
                residence.removeArea(area.name());
                manager.calculateChunks(residence);
            }
            return permissions;
        } catch (RuntimeException | LinkageError exception) {
            String cleanup = "";
            if (areaAdded && residence != null) {
                try {
                    residence.removeArea(area.name());
                    if (manager != null) {
                        manager.calculateChunks(residence);
                    }
                } catch (RuntimeException | LinkageError cleanupFailure) {
                    cleanup = "；新增区域回滚失败: " + dependencyError(cleanupFailure);
                }
            }
            return Result.failure(dependencyError(exception) + cleanup);
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
                return Result.ok("Residence 扩张区域已不存在");
            }
            if (!residence.isServerLand()) {
                return Result.failure("同名 Residence 不属于受控服务端账户，拒绝移除区域");
            }
            if (residence.getArea(areaName) == residence.getMainArea()) {
                return Result.failure("拒绝移除 Residence 主区域");
            }
            residence.removeArea(areaName);
            manager.calculateChunks(residence);
            return residence.getArea(areaName) != null
                    ? Result.failure("Residence 区域移除后仍可读取")
                    : Result.ok("Residence 扩张区域已移除");
        } catch (RuntimeException | LinkageError exception) {
            return Result.failure(dependencyError(exception));
        }
    }

    @Override
    public Result reconcile(String residenceName, List<Area> areas, Collection<UUID> members,
                            boolean repair) {
        requireMainThread();
        String name = registerManagedName(residenceName);
        ClaimedResidence residence = manager().getByName(name);
        if (residence == null) {
            if (!repair) {
                return Result.failure("缺少 Residence 投影 " + name);
            }
            Area main = areas.stream().filter(area -> area.name().equalsIgnoreCase("main"))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "多区域 Residence 缺少 main 区域"));
            Result created = create(name, main.territory(), members);
            if (!created.success()) {
                return created;
            }
            for (Area area : areas) {
                if (area == main) {
                    continue;
                }
                Result added = addArea(name, area, members);
                if (!added.success()) {
                    return added;
                }
            }
            residence = manager().getByName(name);
        }
        Result current = verifyAndApply(name, residence, areas, members, repair);
        if (current.success() || !repair) {
            return current;
        }
        for (Area area : areas) {
            Bounds bounds = bounds(area.territory());
            if (bounds == null) {
                return Result.failure("目标世界未加载: " + area.territory().center().worldName());
            }
            CuboidArea actual = residence.getArea(area.name());
            if (actual == null) {
                Result added = addArea(name, area, members);
                if (!added.success()) {
                    return added;
                }
            } else if (!matchesBounds(actual, bounds)) {
                return Result.failure("区域边界不一致，拒绝自动替换: " + area.name());
            }
        }
        return verifyAndApply(name, residence, areas, members, true);
    }

    private Result verifyAndApply(String name, ClaimedResidence residence, List<Area> areas,
                                  Collection<UUID> members, boolean applyPermissions) {
        if (!residence.isServerLand()) {
            return Result.failure("Residence 所有者不是受控服务端账户: " + residence.getOwner());
        }
        if (residence.getAreaCount() != areas.size()) {
            return Result.failure("Residence 区域数量与数据库不一致");
        }
        Map<String, CuboidArea> actual = residence.getAreaMap();
        for (Area area : areas) {
            Bounds bounds = bounds(area.territory());
            if (bounds == null) {
                return Result.failure("目标世界未加载: " + area.territory().center().worldName());
            }
            CuboidArea cuboid = actual.get(area.name().toLowerCase(java.util.Locale.ROOT));
            if (cuboid == null) {
                cuboid = residence.getArea(area.name());
            }
            if (cuboid == null || !matchesBounds(cuboid, bounds)) {
                return Result.failure("Residence 区域缺失或边界不一致: " + area.name());
            }
        }
        return verifyPermissions(name, residence, members, applyPermissions);
    }

    private Result verifyAndApply(String name, ClaimedResidence residence, Bounds bounds,
                                  Collection<UUID> members, boolean applyPermissions) {
        ResidenceManager manager = manager();
        if (!matchesBounds(residence, bounds)) {
            return Result.failure("Residence 边界或区域数量不一致");
        }
        // Residence 会按服务端 UUID 动态返回 Server_Land 等展示名，不能依赖展示名判断所有权。
        if (!residence.isServerLand()) {
            return Result.failure("Residence 所有者不是受控服务端账户: " + residence.getOwner());
        }
        Location[] checks = {bounds.low(), bounds.high(),
                new Location(bounds.low().getWorld(), bounds.low().getX(), bounds.low().getY(),
                        bounds.high().getZ()),
                new Location(bounds.low().getWorld(), bounds.high().getX(), bounds.high().getY(),
                        bounds.low().getZ())};
        for (Location check : checks) {
            ClaimedResidence found = manager.getByLoc(check);
            if (found == null || !name.equalsIgnoreCase(found.getName())) {
                return Result.failure("Residence 边界不覆盖预期的 3×3 区块");
            }
        }
        return verifyPermissions(name, residence, members, applyPermissions);
    }

    private Result verifyPermissions(String name, ClaimedResidence residence,
                                     Collection<UUID> members, boolean applyPermissions) {
        java.util.Set<UUID> existingPlayers = java.util.Set.copyOf(
                residence.getPermissions().getPlayerFlags().keySet());
        if (!applyPermissions && !existingPlayers.equals(java.util.Set.copyOf(members))) {
            return Result.failure("Residence 成员权限列表与数据库不一致");
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
            for (String flag : MEMBER_FLAGS) {
                if (!applyPermissions && !Boolean.TRUE.equals(
                        residence.getPermissions().getPlayerFlags(member).get(flag))) {
                    return Result.failure("成员 " + member + " 的 " + flag + " 权限不一致");
                }
                if (applyPermissions && !residence.getPermissions().setPlayerFlag(member, flag,
                        FlagPermissions.FlagState.TRUE)) {
                    return Result.failure("无法写入成员 " + member + " 的 " + flag + " 权限");
                }
            }
        }
        return Result.ok("Residence 投影正常: " + name);
    }

    private Bounds bounds(InitialTerritory territory) {
        World world = server.getWorld(territory.center().worldId());
        if (world == null) {
            world = server.getWorld(territory.center().worldName());
        }
        if (world == null) {
            return null;
        }
        int minimumX = Math.multiplyExact(territory.minimumChunkX(), 16);
        int minimumZ = Math.multiplyExact(territory.minimumChunkZ(), 16);
        int maximumX = Math.addExact(Math.multiplyExact(territory.maximumChunkX(), 16), 15);
        int maximumZ = Math.addExact(Math.multiplyExact(territory.maximumChunkZ(), 16), 15);
        Location low = new Location(world, minimumX, world.getMinHeight(), minimumZ);
        Location high = new Location(world, maximumX, world.getMaxHeight() - 1, maximumZ);
        return new Bounds(low, high, new CuboidArea(low, high));
    }

    private ResidenceManager manager() {
        Plugin residence = server.getPluginManager().getPlugin("Residence");
        if (residence == null || !residence.isEnabled()) {
            throw new IllegalStateException("Residence 插件当前不可用；恢复后必须完整重启服务端");
        }
        try {
            ResidenceManager manager = (ResidenceManager) ResidenceApi.getResidenceManager();
            if (manager == null) {
                throw new IllegalStateException("ResidenceManager 当前不可用");
            }
            return manager;
        } catch (RuntimeException | LinkageError exception) {
            throw new IllegalStateException("Residence API 类加载器不可用；必须完整重启服务端",
                    exception);
        }
    }

    private static String dependencyError(Throwable throwable) {
        String message = throwable.getMessage();
        return "Residence API 不可用: " + (message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message);
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
            throw new IllegalStateException("Residence API 必须在 Paper 主线程调用");
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
