package org.allivlisey.tianjitown.paper.station;

import org.allivlisey.tianjitown.storage.station.StationRecord;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.persistence.PersistentDataType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class StationRegistry {
    private final StationDirectory directory;
    private final NamespacedKey stationKey;
    private final Set<Location> pending = new HashSet<>();

    StationRegistry(StationDirectory directory, NamespacedKey stationKey) {
        this.directory = directory;
        this.stationKey = stationKey;
    }

    boolean beginChange(Block block) { return pending.add(location(block)); }
    void endChange(Block block) { pending.remove(location(block)); }
    boolean isPending(Block block) { return block != null && pending.contains(location(block)); }
    boolean isProtected(Block block) { return isPending(block) || isValidStation(block); }

    private static Location location(Block block) {
        return new Location(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
    private record Location(UUID world, int x, int y, int z) {}

    StationRecord stationAt(Block block) {
        return stationRecords().stream().filter(station -> station.sameLocation(
                block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()))
                .findFirst().orElse(null);
    }

    static StationRecord registeredStation(Block block, String stationId, List<StationRecord> stations) {
        return stations.stream().filter(station -> station.id().equals(stationId)
                && station.sameLocation(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()))
                .findFirst().orElse(null);
    }

    List<StationRecord> stationRecords() { return directory.records(); }

    boolean hasStationMarkerOrRegistration(Block block) {
        if (!(block.getState() instanceof Lectern lectern)) {
            return false;
        }
        return isPending(block) || lectern.getPersistentDataContainer().has(stationKey, PersistentDataType.STRING)
                || stationAt(block) != null;
    }

    boolean isValidStation(Block block) {
        if (block == null) {
            return false;
        }
        if (!(block.getState() instanceof Lectern lectern)) {
            return false;
        }
        String stationId = lectern.getPersistentDataContainer().get(stationKey,
                PersistentDataType.STRING);
        return stationId != null && !stationId.isBlank()
                && registeredStation(block, stationId, stationRecords()) != null;
    }

}
