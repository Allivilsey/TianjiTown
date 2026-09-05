package org.allivlisey.tianjitown.integrations.residence;

import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.bekvon.bukkit.residence.protection.ResidenceManager;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.allivlisey.tianjitown.core.ports.LandProtectionService.*;
import org.allivlisey.tianjitown.integrations.residence.ResidenceGeometry.Bounds;
import static org.allivlisey.tianjitown.integrations.residence.ResidenceGeometry.*;
import static org.allivlisey.tianjitown.integrations.residence.ResidenceLandProtectionService.*;

final class ResidenceProjectionRepair {
    private final ResidenceLandProtectionService service;
    private final ResidenceMutationContext context;
    private final ResidenceGeometry geometry;

    ResidenceProjectionRepair(ResidenceLandProtectionService service, ResidenceMutationContext context, ResidenceGeometry geometry) {
        this.service = service;
        this.context = context;
        this.geometry = geometry;
    }

    public Result reconcile(String residenceName, List<Area> areas, Collection<UUID> members,
                            boolean repair) {
        context.requireMainThread();
        String name = service.registerManagedName(residenceName);
        try {
            ResidenceManager manager = service.manager();
            ClaimedResidence residence = manager.getByName(name);
            if (residence == null) {
                if (!repair) {
                    return Result.failureCode(ResultCode.PROJECTION_MISSING,
                            Map.of("residence", safeText(name)));
                }
                return createFromDatabase(name, areas, members);
            }
            Result current = service.verifyAndApply(name, residence, areas, members, repair);
            if (current.success() || !repair) {
                return current;
            }
            for (Area area : areas) {
                Bounds bounds = geometry.bounds(area.territory());
                if (bounds == null) {
                    return Result.failureCode(ResultCode.WORLD_UNLOADED,
                            Map.of("world", safeText(area.territory().center().worldName())));
                }
                CuboidArea actual = residence.getArea(area.name());
                if (actual == null) {
                    Result added = service.addArea(name, area, members);
                    if (!added.success()) {
                        return added;
                    }
                } else if (!matchesBounds(actual, bounds)) {
                    return rebuildFromDatabase(manager, name, residence, areas, members);
                }
            }
            Result repaired = service.verifyAndApply(name, residence, areas, members, true);
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
        Result created = service.create(name, main.territory(), members);
        if (!created.success()) {
            return created;
        }
        for (Area area : areas) {
            if (area.equals(main)) {
                continue;
            }
            Result added = service.addArea(name, area, members);
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
            Bounds bounds = geometry.bounds(area.territory());
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
        context.removeResidence(manager, name);
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
        ResidenceManager manager = service.manager();
        ClaimedResidence incomplete = manager.getByName(name);
        if (incomplete != null && incomplete.isServerLand()) {
            context.removeResidence(manager, name);
        }
    }

}
