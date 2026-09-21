package org.allivlisey.tianjitown.core.ports;

import org.allivlisey.tianjitown.core.land.InitialTerritory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public interface LandProtectionService {
    default Result setMessages(String residenceName, String enterMessage, String leaveMessage) {
        return Result.failureCode(ResultCode.RESIDENCE_API_UNAVAILABLE,
                Map.of("detail", "领地实现不支持消息同步"));
    }

    default Collision findNameCollision(String residenceName) {
        return Collision.none();
    }

    Collision findCollision(InitialTerritory territory);

    Inspection inspect(String residenceName, InitialTerritory territory,
                       Collection<UUID> members);

    Result create(String residenceName, InitialTerritory territory, Collection<UUID> members);

    Result remove(String residenceName, InitialTerritory territory);

    Result reconcile(String residenceName, InitialTerritory territory,
                     Collection<UUID> members, boolean repair);

    default boolean contains(String residenceName, UUID worldId,
                             int blockX, int blockY, int blockZ) {
        return false;
    }

    /**
     * Distinguishes a malformed system projection from an unrelated player
     * Residence that happens to use the same name.  Recovery may remove only
     * the former.
     */
    default boolean isControlledProjection(String residenceName) {
        return false;
    }

    default Result setTeleportPoint(String residenceName, UUID worldId, String worldName,
                                    double x, double y, double z, float yaw, float pitch) {
        return Result.failureCode(ResultCode.UNSUPPORTED_TELEPORT_POINT);
    }

    default Inspection inspect(String residenceName, List<Area> areas,
                               Collection<UUID> members) {
        if (areas.size() != 1) {
            return Inspection.invalidCode(ResultCode.UNSUPPORTED_MULTI_AREA);
        }
        return inspect(residenceName, areas.getFirst().territory(), members);
    }

    default Result addArea(String residenceName, Area area, Collection<UUID> members) {
        return Result.failureCode(ResultCode.UNSUPPORTED_ADD_AREA);
    }

    /** Returns whether the named Residence area already exists. */
    default boolean hasArea(String residenceName, String areaName) {
        return false;
    }

    default Result removeArea(String residenceName, String areaName) {
        return Result.failureCode(ResultCode.UNSUPPORTED_REMOVE_AREA);
    }

    /** Removes only a system-owned area whose world and bounds match the persisted operation. */
    default Result removeArea(String residenceName, Area expectedArea) {
        return Result.failureCode(ResultCode.UNSUPPORTED_REMOVE_AREA);
    }

    default Result reconcile(String residenceName, List<Area> areas,
                             Collection<UUID> members, boolean repair) {
        if (areas.size() != 1) {
            return Result.failureCode(ResultCode.UNSUPPORTED_MULTI_AREA);
        }
        return reconcile(residenceName, areas.getFirst().territory(), members, repair);
    }

    record Area(String name, InitialTerritory territory) {
    }

    record Collision(boolean occupied, String residenceName, ResultCode code,
                     Map<String, String> parameters) {
        public Collision {
            parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
        }

        public Collision(boolean occupied, String residenceName) {
            this(occupied, residenceName, null, Map.of());
        }

        public static Collision none() {
            return new Collision(false, null);
        }

        public static Collision failureCode(ResultCode code) {
            return failureCode(code, Map.of());
        }

        public static Collision failureCode(ResultCode code, Map<String, ?> parameters) {
            return new Collision(true, null, Objects.requireNonNull(code, "code"),
                    stringParameters(parameters));
        }
    }

    enum ProjectionState {
        HEALTHY,
        MISSING,
        INVALID
    }

    enum ResultCode {
        UNSUPPORTED_TELEPORT_POINT,
        UNSUPPORTED_MULTI_AREA,
        UNSUPPORTED_ADD_AREA,
        UNSUPPORTED_REMOVE_AREA,
        WORLD_UNLOADED,
        RESIDENCE_API_UNAVAILABLE,
        PROJECTION_MISSING,
        INITIAL_PROJECTION_COLLISION,
        PROJECTION_CREATE_REJECTED,
        PROJECTION_CREATE_READBACK_FAILED,
        PROJECTION_ALREADY_ABSENT,
        PROJECTION_REMOVED,
        PROJECTION_STILL_PRESENT,
        PROJECTION_REMOVE_REJECTED,
        REBUILD_OWNER_MISMATCH,
        CONTROLLED_PROJECTION_MISSING,
        TELEPORT_POINT_OUTSIDE_PROJECTION,
        TELEPORT_POINT_UPDATED,
        EXPANSION_PROJECTION_MISSING,
        PROJECTION_OWNER_NOT_CONTROLLED,
        AREA_OWNER_NOT_CONTROLLED,
        AREA_BOUNDS_MISMATCH,
        EXPANSION_COLLISION,
        AREA_ADD_REJECTED,
        AREA_ADD_ROLLBACK_FAILED,
        EXPANSION_AREA_ALREADY_ABSENT,
        MAIN_AREA_REMOVAL_REJECTED,
        AREA_STILL_PRESENT,
        EXPANSION_AREA_REMOVED,
        PROJECTION_REBUILT_FROM_DATABASE,
        MAIN_AREA_MISSING,
        DATABASE_AREA_CREATE_FAILED,
        DATABASE_REBUILD_COLLISION,
        DATABASE_REBUILD_REMOVE_FAILED,
        PROJECTION_AUTO_REPAIRED,
        AREA_COUNT_MISMATCH,
        AREA_MISSING_OR_BOUNDS_MISMATCH,
        PROJECTION_BOUNDS_OR_AREA_COUNT_MISMATCH,
        PROJECTION_BOUNDARY_MISMATCH,
        EXPLOSION_FLAG_MISMATCH,
        EXPLOSION_FLAG_WRITE_FAILED,
        MONSTER_SPAWN_FLAG_MISMATCH,
        MONSTER_SPAWN_FLAG_WRITE_FAILED,
        MONSTER_ENTRY_FLAG_MISMATCH,
        MONSTER_ENTRY_FLAG_WRITE_FAILED,
        MEMBERSHIP_MISMATCH,
        MEMBER_PADD_PERMISSION_MISMATCH,
        MEMBER_IGNITE_PERMISSION_MISMATCH,
        MEMBER_VEHICLE_DESTROY_PERMISSION_MISMATCH,
        MEMBER_PADD_PERMISSION_WRITE_FAILED,
        MEMBER_IGNITE_PERMISSION_WRITE_FAILED,
        MEMBER_VEHICLE_DESTROY_PERMISSION_WRITE_FAILED,
        PROJECTION_HEALTHY,
        PROVISION_SITE_VALIDATION_DETAIL,
        PROVISION_OPERATION_FAILED,
        PROVISION_DEFAULT_TELEPORT_WORLD_DETAIL,
        PROVISION_DEFAULT_TELEPORT_HEIGHT_DETAIL,
        PROVISION_DEFAULT_TELEPORT_SPACE_DETAIL,
        PROVISION_DEFAULT_TELEPORT_ROLLED_BACK_DETAIL,
        PROVISION_DEFAULT_TELEPORT_ROLLBACK_FAILED_DETAIL,
        PROVISION_LAND_WITH_TELEPORT_DETAIL
    }

    record Inspection(ProjectionState state, ResultCode code,
                      Map<String, String> parameters) {
        public Inspection {
            state = Objects.requireNonNull(state, "state");
            parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
            code = Objects.requireNonNull(code, "code");
        }

        public static Inspection healthyCode(ResultCode code) {
            return healthyCode(code, Map.of());
        }

        public static Inspection healthyCode(ResultCode code, Map<String, ?> parameters) {
            return new Inspection(ProjectionState.HEALTHY, Objects.requireNonNull(code, "code"),
                    stringParameters(parameters));
        }

        public static Inspection missingCode(ResultCode code) {
            return missingCode(code, Map.of());
        }

        public static Inspection missingCode(ResultCode code, Map<String, ?> parameters) {
            return new Inspection(ProjectionState.MISSING, Objects.requireNonNull(code, "code"),
                    stringParameters(parameters));
        }

        public static Inspection invalidCode(ResultCode code) {
            return invalidCode(code, Map.of());
        }

        public static Inspection invalidCode(ResultCode code, Map<String, ?> parameters) {
            return new Inspection(ProjectionState.INVALID, Objects.requireNonNull(code, "code"),
                    stringParameters(parameters));
        }

    }

    record Result(boolean success, ResultCode code,
                  Map<String, String> parameters) {
        public Result {
            parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
            code = Objects.requireNonNull(code, "code");
        }

        public static Result successCode(ResultCode code) {
            return successCode(code, Map.of());
        }

        public static Result successCode(ResultCode code, Map<String, ?> parameters) {
            return new Result(true, Objects.requireNonNull(code, "code"),
                    stringParameters(parameters));
        }

        public static Result fromHealthyInspection(Inspection inspection) {
            Objects.requireNonNull(inspection, "inspection");
            if (inspection.state() != ProjectionState.HEALTHY) {
                throw new IllegalArgumentException("inspection must be healthy");
            }
            return new Result(true, inspection.code(), inspection.parameters());
        }

        public static Result failureCode(ResultCode code) {
            return failureCode(code, Map.of());
        }

        public static Result failureCode(ResultCode code, Map<String, ?> parameters) {
            return new Result(false, Objects.requireNonNull(code, "code"),
                    stringParameters(parameters));
        }

    }

    private static Map<String, String> stringParameters(Map<String, ?> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        parameters.forEach((key, value) -> values.put(Objects.requireNonNull(key, "parameter key"),
                Objects.requireNonNull(value, "parameter value").toString()));
        return Map.copyOf(values);
    }
}
