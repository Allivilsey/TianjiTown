package cn.tianji.town.paper;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ProvisionCoordinator {
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    boolean tryBegin(UUID applicationId) {
        return inFlight.add(applicationId);
    }

    void finish(UUID applicationId) {
        inFlight.remove(applicationId);
    }
}
