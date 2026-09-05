package org.allivlisey.tianjitown.integrations.residence;

import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import org.bukkit.Server;


final class ResidenceMutationContext {
    private final Server server;
    private final ThreadLocal<Integer> internalMutations = ThreadLocal.withInitial(() -> 0);

    ResidenceMutationContext(Server server) {
        this.server = server;
    }

    boolean internalMutation() {
        return internalMutations.get() > 0;
    }

    void requireMainThread() {
        if (!server.isPrimaryThread()) {
            throw new IllegalStateException("RESIDENCE_MAIN_THREAD_REQUIRED");
        }
    }

    void removeResidence(ResidenceManager manager, String name) {
        withInternalMutation(() -> manager.removeResidence(name));
    }

    void removeArea(ClaimedResidence residence, String areaName) {
        withInternalMutation(() -> residence.removeArea(areaName));
    }

    void withInternalMutation(Runnable action) {
        internalMutations.set(internalMutations.get() + 1);
        try {
            action.run();
        } finally {
            int remaining = internalMutations.get() - 1;
            if (remaining == 0) {
                internalMutations.remove();
            } else {
                internalMutations.set(remaining);
            }
        }
    }

}
