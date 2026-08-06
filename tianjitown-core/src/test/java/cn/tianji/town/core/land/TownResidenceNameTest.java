package cn.tianji.town.core.land;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TownResidenceNameTest {
    @Test
    void normalizesDedicatedEnglishName() {
        assertEquals("sky", TownResidenceName.initial(" SKY "));
        assertEquals("a", TownResidenceName.initial("A"));
        assertEquals("townland", TownResidenceName.initial("TownLand"));
        assertThrows(IllegalArgumentException.class, () -> TownResidenceName.initial("S K Y"));
        assertThrows(IllegalArgumentException.class, () -> TownResidenceName.initial("SKY-1"));
    }

    @Test
    void readsOnlyCurrentPlainEnglishFormat() {
        assertEquals("sky", TownResidenceName.key("SKY"));
        assertThrows(IllegalArgumentException.class,
                () -> TownResidenceName.key("tt_sky_0_0"));
        assertThrows(NullPointerException.class, () -> TownResidenceName.key(null));
    }
}
