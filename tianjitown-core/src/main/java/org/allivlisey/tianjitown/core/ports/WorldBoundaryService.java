package org.allivlisey.tianjitown.core.ports;

import org.allivlisey.tianjitown.core.land.InitialTerritory;

public interface WorldBoundaryService {
    Check check(InitialTerritory territory, int bufferChunks);

    record Check(boolean configured, boolean inside) {
        public static Check missing() {
            return new Check(false, false);
        }

        public static Check configuredInside() {
            return new Check(true, true);
        }

        public static Check configuredOutside() {
            return new Check(true, false);
        }
    }
}
