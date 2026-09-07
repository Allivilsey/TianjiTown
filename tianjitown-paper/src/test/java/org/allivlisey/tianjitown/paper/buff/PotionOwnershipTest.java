package org.allivlisey.tianjitown.paper.buff;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PotionOwnershipTest {
    @Test void ownershipSurvivesPersistenceButDoesNotMatchExternalReplacement() {
        PotionOwnership owned = new PotionOwnership(0, 1200, true, false, true);
        assertEquals(owned, PotionOwnership.decode(owned.encode()));
        assertTrue(owned.matches(0, 1100, true, false, true));
        assertFalse(owned.matches(0, 1100, false, true, true));
        assertFalse(owned.matches(1, 1100, true, false, true));
        assertFalse(owned.matches(0, 1300, true, false, true));
        assertFalse(owned.matches(0, -1, true, false, true));
    }

    @Test void aPotionNameAloneCannotAuthorizeRemoval() {
        assertThrows(IllegalArgumentException.class, () -> PotionOwnership.decode("minecraft:fire_resistance"));
    }
}
