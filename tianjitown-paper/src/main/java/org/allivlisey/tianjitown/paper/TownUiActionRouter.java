package org.allivlisey.tianjitown.paper;

import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** The sole action dispatch boundary. Every action has one feature owner. */
final class TownUiActionRouter {
    private final Map<String, ActionOwner> owners;
    private final UnknownActionOwner unknownActionOwner;
    private final ModeGate modeGate;

    private TownUiActionRouter(Map<String, ActionOwner> owners, UnknownActionOwner unknownActionOwner,
                               ModeGate modeGate) {
        this.owners = Map.copyOf(owners);
        this.unknownActionOwner = Objects.requireNonNull(unknownActionOwner, "unknownActionOwner");
        this.modeGate = Objects.requireNonNull(modeGate, "modeGate");
    }

    void route(Player player, String action, String target) {
        if (modeGate.blocked(player, action)) {
            return;
        }
        ActionOwner owner = owners.get(action);
        if (owner == null) {
            unknownActionOwner.handle(player);
            return;
        }
        owner.handle(player, target);
    }

    static Builder builder(ModeGate modeGate, UnknownActionOwner unknownActionOwner) {
        return new Builder(modeGate, unknownActionOwner);
    }

    static final class Builder {
        private final Map<String, ActionOwner> owners = new LinkedHashMap<>();
        private final UnknownActionOwner unknownActionOwner;
        private final ModeGate modeGate;

        private Builder(ModeGate modeGate, UnknownActionOwner unknownActionOwner) {
            this.unknownActionOwner = unknownActionOwner;
            this.modeGate = modeGate;
        }

        Builder register(String action, ActionOwner owner) {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(owner, "owner");
            if (owners.putIfAbsent(action, owner) != null) {
                throw new IllegalStateException("duplicate UI action owner: " + action);
            }
            return this;
        }

        Builder registerAll(FeatureOwner owner, String... actions) {
            Objects.requireNonNull(owner, "owner");
            for (String action : actions) {
                register(action, (player, target) -> owner.route(player, action, target));
            }
            return this;
        }

        TownUiActionRouter build() {
            return new TownUiActionRouter(owners, unknownActionOwner, modeGate);
        }
    }

    @FunctionalInterface
    interface ActionOwner {
        void handle(Player player, String target);
    }

    @FunctionalInterface
    interface UnknownActionOwner {
        void handle(Player player);
    }

    @FunctionalInterface
    interface FeatureOwner {
        void route(Player player, String action, String target);
    }

    @FunctionalInterface
    interface ModeGate {
        boolean blocked(Player player, String action);
    }
}
