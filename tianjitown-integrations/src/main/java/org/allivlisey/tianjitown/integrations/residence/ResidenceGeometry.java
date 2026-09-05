package org.allivlisey.tianjitown.integrations.residence;

import org.allivlisey.tianjitown.core.land.InitialTerritory;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;


final class ResidenceGeometry {
    private final Server server;

    ResidenceGeometry(Server server) {
        this.server = server;
    }

    Bounds bounds(InitialTerritory territory) {
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

    static boolean matchesBounds(ClaimedResidence residence, Bounds bounds) {
        return residence.getAreaCount() == 1
                && matchesBounds(residence.getMainArea(), bounds);
    }

    static boolean matchesMainBounds(ClaimedResidence residence, Bounds bounds) {
        return matchesBounds(residence.getMainArea(), bounds);
    }

    static boolean matchesBounds(CuboidArea area, Bounds bounds) {
        return area.getLowVector().equals(bounds.area().getLowVector())
                && area.getHighVector().equals(bounds.area().getHighVector());
    }

    record Bounds(Location low, Location high, CuboidArea area) {
    }}
