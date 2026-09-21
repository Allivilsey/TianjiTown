package org.allivlisey.tianjitown.paper.bonus;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PaperBeaconActivityTest {
    @Test
    void readsLiveBeamAgainInsteadOfRememberingActivationOrSnapshot() {
        NativeBeacon entity = new NativeBeacon();
        CraftBeacon state = new CraftBeacon(entity);
        assertFalse(PaperBeaconActivity.hasBeam(state));
        entity.sections = List.of(new Object());
        assertTrue(PaperBeaconActivity.hasBeam(state));
        entity.sections = List.of();
        assertFalse(PaperBeaconActivity.hasBeam(state));
    }

    @Test
    void unsupportedServerShapeFailsClosed() {
        assertThrows(IllegalStateException.class, () -> PaperBeaconActivity.hasBeam(new Object()));
    }

    public static final class CraftBeacon {
        private final NativeBeacon entity;
        CraftBeacon(NativeBeacon entity) { this.entity = entity; }
        public NativeBeacon getBlockEntity() { return entity; }
    }

    public static final class NativeBeacon {
        private List<Object> sections = List.of();
        public List<Object> getBeamSections() { return sections; }
    }
}
