package cn.tianji.town.integrations.worldguard;

import cn.tianji.town.core.land.InitialTerritory;
import cn.tianji.town.core.ports.RegionBoundaryService;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Server;
import org.bukkit.World;

import java.util.List;
import java.util.Objects;

public final class WorldGuardRegionBoundaryService implements RegionBoundaryService {
    private final Server server;

    public WorldGuardRegionBoundaryService(Server server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public Collision findCollision(InitialTerritory territory, int bufferChunks) {
        if (!server.isPrimaryThread()) {
            throw new IllegalStateException("WorldGuard API 必须在 Paper 主线程调用");
        }
        World world = server.getWorld(territory.center().worldId());
        if (world == null) {
            world = server.getWorld(territory.center().worldName());
        }
        if (world == null) {
            return Collision.none();
        }
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(world));
        if (manager == null) {
            return Collision.none();
        }
        int bufferBlocks = Math.multiplyExact(Math.max(0, bufferChunks), 16);
        int minimumX = Math.subtractExact(Math.multiplyExact(territory.minimumChunkX(), 16),
                bufferBlocks);
        int minimumZ = Math.subtractExact(Math.multiplyExact(territory.minimumChunkZ(), 16),
                bufferBlocks);
        int maximumX = Math.addExact(Math.addExact(
                Math.multiplyExact(territory.maximumChunkX(), 16), 15), bufferBlocks);
        int maximumZ = Math.addExact(Math.addExact(
                Math.multiplyExact(territory.maximumChunkZ(), 16), 15), bufferBlocks);
        ProtectedCuboidRegion candidate = new ProtectedCuboidRegion(
                "__tianjitown_site_check__",
                BlockVector3.at(minimumX, world.getMinHeight(), minimumZ),
                BlockVector3.at(maximumX, world.getMaxHeight() - 1, maximumZ));
        List<ProtectedRegion> regions = manager.getRegions().values().stream()
                .filter(region -> !(region instanceof GlobalProtectedRegion))
                .toList();
        return candidate.getIntersectingRegions(regions).stream()
                .findFirst()
                .map(region -> new Collision(true, region.getId()))
                .orElseGet(Collision::none);
    }
}
