package cn.tianji.town.core.ports;

import cn.tianji.town.core.land.InitialTerritory;

import java.util.Collection;
import java.util.UUID;

public interface LandProtectionService {
    Collision findCollision(InitialTerritory territory);

    Result create(UUID townId, InitialTerritory territory, Collection<UUID> members);

    Result remove(UUID townId, InitialTerritory territory);

    Result reconcile(UUID townId, InitialTerritory territory, Collection<UUID> members, boolean repair);

    record Collision(boolean occupied, String residenceName) {
        public static Collision none() {
            return new Collision(false, null);
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
