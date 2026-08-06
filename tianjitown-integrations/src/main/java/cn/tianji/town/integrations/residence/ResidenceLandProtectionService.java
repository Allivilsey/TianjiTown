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

import java.util.Collection;
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
        Bounds bounds = bounds(territory);
        if (bounds == null) {
            return new Collision(true, "世界未加载");
        }
        ClaimedResidence collision = manager().collidesWithResidence(bounds.area());
        return collision == null ? Collision.none() : new Collision(true, collision.getName());
    }

    @Override
    public Result create(String residenceName, InitialTerritory territory, Collection<UUID> members) {
        requireMainThread();
        String name = registerManagedName(residenceName);
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
        try {
            if (!manager.addResidence(name, SYSTEM_OWNER_HINT, bounds.low(), bounds.high())) {
                return Result.failure("Residence API 拒绝创建系统领地 " + name);
            }
            ClaimedResidence created = manager.getByName(name);
            if (created == null) {
                return Result.failure("Residence 创建后无法按名称读取: " + name);
            }
            return verifyAndApply(name, created, bounds, members, true);
        } catch (RuntimeException exception) {
            return Result.failure(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    @Override
    public Result remove(String residenceName, InitialTerritory territory) {
        requireMainThread();
        String name = registerManagedName(residenceName);
        ResidenceManager manager = manager();
        ClaimedResidence existing = manager.getByName(name);
        if (existing == null) {
            return Result.ok("Residence 已不存在");
        }
        Bounds bounds = bounds(territory);
        if (bounds == null) {
            return Result.failure("目标世界未加载: " + territory.center().worldName());
        }
        if (!existing.isServerLand() || !matchesBounds(existing, bounds)) {
            return Result.failure("拒绝移除同名但并非当前小镇投影的 Residence: " + name);
        }
        try {
            manager.removeResidence(name);
            return manager.getByName(name) == null
                    ? Result.ok("Residence 已移除")
                    : Result.failure("Residence 移除后仍可读取");
        } catch (RuntimeException exception) {
            return Result.failure(exception.getClass().getSimpleName() + ": " + exception.getMessage());
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
        return (ResidenceManager) ResidenceApi.getResidenceManager();
    }

    private static boolean matchesBounds(ClaimedResidence residence, Bounds bounds) {
        return residence.getAreaCount() == 1
                && residence.getMainArea().getLowVector().equals(bounds.area().getLowVector())
                && residence.getMainArea().getHighVector().equals(bounds.area().getHighVector());
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
