package org.allivlisey.tianjitown.paper.buff;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PotionEffectTrackerTest {
    private static final String FIRE = "minecraft:fire_resistance";
    private static final PotionOwnership OWN = new PotionOwnership(0, 1200, true, false, true);
    private static final PotionOwnership VANILLA = new PotionOwnership(0, 600, false, true, true);
    private final Map<String, PotionOwnership> owned = new HashMap<>();
    private final Map<String, PotionOwnership> active = new HashMap<>();
    private final List<String> removed = new ArrayList<>();
    private final PotionEffectTracker.Effects effects = new PotionEffectTracker.Effects() {
        public PotionOwnership current(String key) { return active.get(key); }
        public boolean apply(String key, PotionOwnership effect) { active.put(key, effect); return true; }
        public void remove(String key) { removed.add(key); active.remove(key); }
    };

    private void refresh(Map<String, PotionOwnership> desired) {
        PotionEffectTracker.reconcile(owned, desired, effects, state -> {
            owned.clear(); owned.putAll(state);
        });
    }

    @Test void playerWithoutPurchasedBuffsKeepsAllVanillaPotions() {
        active.put(FIRE, VANILLA);
        active.put("minecraft:night_vision", VANILLA);
        active.put("minecraft:water_breathing", VANILLA);
        refresh(Map.of());
        assertEquals(3, active.size());
        assertTrue(removed.isEmpty());
        assertTrue(owned.isEmpty());
    }

    @Test void expirationRemovesAnEffectActuallyAppliedByTown() {
        refresh(Map.of(FIRE, OWN));
        assertEquals(OWN, owned.get(FIRE));
        active.put(FIRE, new PotionOwnership(0, 40, true, false, true));
        refresh(Map.of());
        assertEquals(List.of(FIRE), removed);
        assertTrue(active.isEmpty());
        assertTrue(owned.isEmpty());
    }

    @Test void vanillaReplacementSurvivesExpirationAndShutdown() {
        refresh(Map.of(FIRE, OWN));
        active.put(FIRE, VANILLA);
        refresh(Map.of());
        assertEquals(VANILLA, active.get(FIRE));
        assertTrue(removed.isEmpty());
    }

    @Test void strongerExternalEffectIsNeverClaimedOrCleared() {
        var stronger = new PotionOwnership(1, -1, false, true, true);
        active.put(FIRE, stronger);
        refresh(Map.of(FIRE, OWN));
        assertTrue(owned.isEmpty());
        refresh(Map.of());
        assertEquals(stronger, active.get(FIRE));
    }
}
