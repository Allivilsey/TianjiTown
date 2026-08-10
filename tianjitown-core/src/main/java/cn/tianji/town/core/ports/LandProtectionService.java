package cn.tianji.town.core.ports;

import cn.tianji.town.core.land.InitialTerritory;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LandProtectionService {
    Collision findCollision(InitialTerritory territory);

    Inspection inspect(String residenceName, InitialTerritory territory,
                       Collection<UUID> members);

    Result create(String residenceName, InitialTerritory territory, Collection<UUID> members);

    Result remove(String residenceName, InitialTerritory territory);

    Result reconcile(String residenceName, InitialTerritory territory,
                     Collection<UUID> members, boolean repair);

    default Inspection inspect(String residenceName, List<Area> areas,
                               Collection<UUID> members) {
        if (areas.size() != 1) {
            return Inspection.invalid("当前领地适配器不支持多区域 Residence");
        }
        return inspect(residenceName, areas.getFirst().territory(), members);
    }

    default Result addArea(String residenceName, Area area, Collection<UUID> members) {
        return Result.failure("当前领地适配器不支持扩张区域");
    }

    default Result removeArea(String residenceName, String areaName) {
        return Result.failure("当前领地适配器不支持移除扩张区域");
    }

    default Result reconcile(String residenceName, List<Area> areas,
                             Collection<UUID> members, boolean repair) {
        if (areas.size() != 1) {
            return Result.failure("当前领地适配器不支持多区域 Residence");
        }
        return reconcile(residenceName, areas.getFirst().territory(), members, repair);
    }

    record Area(String name, InitialTerritory territory) {
    }

    record Collision(boolean occupied, String residenceName) {
        public static Collision none() {
            return new Collision(false, null);
        }
    }

    enum ProjectionState {
        HEALTHY,
        MISSING,
        INVALID
    }

    record Inspection(ProjectionState state, String message) {
        public static Inspection healthy(String message) {
            return new Inspection(ProjectionState.HEALTHY, message);
        }

        public static Inspection missing(String message) {
            return new Inspection(ProjectionState.MISSING, message);
        }

        public static Inspection invalid(String message) {
            return new Inspection(ProjectionState.INVALID, message);
        }
    }

    record Result(boolean success, String message) {
        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result failure(String message) {
            return new Result(false, message);
        }
    }
}
