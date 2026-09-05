package org.allivlisey.tianjitown.paper.land;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ProvisionCoordinator {
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public boolean tryBegin(UUID applicationId) {
        return inFlight.add(applicationId);
    }

    public void finish(UUID applicationId) {
        inFlight.remove(applicationId);
    }
}
