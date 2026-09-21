package org.allivlisey.tianjitown.paper.runtime;

import java.util.UUID;
import org.allivlisey.tianjitown.paper.land.ProvisionCoordinator;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;

/** Serializes town archival with the full approval/projection lifecycle. Worker-thread only. */
final class TownProvisionDeletionGuard {
    private final TownRepository repository;
    private final ProvisionCoordinator provisions;

    TownProvisionDeletionGuard(TownRepository repository, ProvisionCoordinator provisions) {
        this.repository = repository;
        this.provisions = provisions;
    }

    TownSnapshot delete(UUID townId, long expectedVersion, UUID actor, String actorName, String reason) {
        UUID applicationId = repository.applicationIdForTown(townId).orElse(null);
        if (applicationId != null && !provisions.tryBegin(applicationId))
            throw new TownRepository.ConflictException("该小镇正在创建或恢复，请等待当前操作结束后再删除");
        try {
            TownSnapshot current = repository.findTown(townId).orElseThrow(
                    () -> new TownRepository.ConflictException("小镇不存在"));
            if (current.version() != expectedVersion)
                throw new TownRepository.ConflictException("小镇状态已变化，请重新确认删除");
            repository.deleteTown(townId, actor, actorName, reason);
            return current;
        } finally {
            if (applicationId != null) provisions.finish(applicationId);
        }
    }
}
