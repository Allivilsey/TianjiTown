package org.allivlisey.tianjitown.paper.station;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class StationRegistry {
    private final TianjiTownPlugin plugin;
    private final NamespacedKey stationKey;

    StationRegistry(TianjiTownPlugin plugin, NamespacedKey stationKey) {
        this.plugin = plugin;
        this.stationKey = stationKey;
    }

    void registerStation(Block block, String stationId, UUID townId, String townName) {
        List<StationRecord> stations = new ArrayList<>(stationRecords());
        stations.removeIf(station -> station.id().equals(stationId)
                || station.sameLocation(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()));
        stations.add(new StationRecord(stationId, block.getWorld().getUID(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), townId, townName));
        saveStationRecords(stations);
    }

    void unregisterStationAt(Block block) {
        List<StationRecord> stations = new ArrayList<>(stationRecords());
        stations.removeIf(station -> station.sameLocation(block.getWorld().getUID(), block.getX(),
                block.getY(), block.getZ()));
        saveStationRecords(stations);
    }

    StationRecord stationAt(Block block) {
        return stationRecords().stream().filter(station -> station.sameLocation(
                block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()))
                .findFirst().orElse(null);
    }

    static StationRecord registeredStation(Block block, String stationId,
                                                     List<StationRecord> stations) {
        return stations.stream().filter(station -> station.id().equals(stationId)
                        && station.sameLocation(block.getWorld().getUID(), block.getX(), block.getY(),
                        block.getZ()))
                .findFirst().orElse(null);
    }

    List<StationRecord> stationRecords() {
        List<StationRecord> result = new ArrayList<>();
        for (Map<?, ?> raw : plugin.getConfig().getMapList("town.service-stations")) {
            try {
                Object townId = raw.get("town-id");
                result.add(new StationRecord(String.valueOf(raw.get("id")),
                        UUID.fromString(String.valueOf(raw.get("world-uuid"))),
                        String.valueOf(raw.get("world")), number(raw, "x"), number(raw, "y"),
                        number(raw, "z"), townId == null ? null : UUID.fromString(String.valueOf(townId)),
                        raw.get("town-name") == null ? null : String.valueOf(raw.get("town-name"))));
            } catch (MissingIntegerException exception) {
                plugin.getLogger().warning(plugin.messages().plainText(
                        "log.station.invalid-record-missing-integer",
                        Map.of("key", safeText(exception.key()))));
            } catch (StationRecord.ValidationException exception) {
                plugin.getLogger().warning(plugin.messages().plainText(
                        "log.station.invalid-record",
                        Map.of("detail", plugin.messages().plainText(exception.messageKey()))));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning(plugin.messages().plainText(
                        "log.station.invalid-record",
                        Map.of("detail", safeText(safeMessage(exception)))));
            }
        }
        return List.copyOf(result);
    }

    private void saveStationRecords(List<StationRecord> stations) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (StationRecord station : stations) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", station.id());
            value.put("world-uuid", station.worldId().toString());
            value.put("world", station.worldName());
            value.put("x", station.x());
            value.put("y", station.y());
            value.put("z", station.z());
            if (station.townId() != null) {
                value.put("town-id", station.townId().toString());
                value.put("town-name", station.townName());
            }
            serialized.add(value);
        }
        plugin.getConfig().set("town.service-stations", serialized);
        plugin.saveConfig();
    }

    private static int number(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        if (!(value instanceof Number number)) {
            throw new MissingIntegerException(key);
        }
        return number.intValue();
    }

    boolean hasStationMarkerOrRegistration(Block block) {
        if (!(block.getState() instanceof Lectern lectern)) {
            return false;
        }
        return lectern.getPersistentDataContainer().has(stationKey, PersistentDataType.STRING)
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

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    private static final class MissingIntegerException extends IllegalArgumentException {
        private final String key;

        private MissingIntegerException(String key) {
            super("missing-integer:" + key);
            this.key = key;
        }

        private String key() {
            return key;
        }
    }

    record StationRecord(String id, UUID worldId, String worldName, int x, int y, int z,
                                 UUID townId, String townName) {
        StationRecord {
            if (id == null || id.isBlank()) {
                throw new ValidationException("log.station.record-id-empty");
            }
            Objects.requireNonNull(worldId, "worldId");
            if (worldName == null || worldName.isBlank()) {
                throw new ValidationException("log.station.record-world-name-empty");
            }
        }

        private static final class ValidationException extends IllegalArgumentException {
            private final String messageKey;

            private ValidationException(String messageKey) {
                super(messageKey);
                this.messageKey = messageKey;
            }

            private String messageKey() {
                return messageKey;
            }
        }

        boolean sameLocation(UUID candidateWorld, int candidateX, int candidateY, int candidateZ) {
            return worldId.equals(candidateWorld) && x == candidateX && y == candidateY
                    && z == candidateZ;
        }
    }}
