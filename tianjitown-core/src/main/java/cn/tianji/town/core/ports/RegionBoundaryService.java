package cn.tianji.town.core.ports;

import cn.tianji.town.core.land.InitialTerritory;

public interface RegionBoundaryService {
    Collision findCollision(InitialTerritory territory, int bufferChunks);

    record Collision(boolean occupied, String regionName) {
        public static Collision none() {
            return new Collision(false, null);
        }
    }
}
