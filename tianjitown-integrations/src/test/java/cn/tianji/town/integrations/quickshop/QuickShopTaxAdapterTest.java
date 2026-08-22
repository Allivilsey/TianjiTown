package cn.tianji.town.integrations.quickshop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickShopTaxAdapterTest {
    @Test
    void requiresQuickShopVersionStrictlyAboveSixThree() {
        assertFalse(QuickShopTaxAdapter.isNewerThanMinimum("6.3.0.0"));
        assertFalse(QuickShopTaxAdapter.isNewerThanMinimum("6.2.0.11"));
        assertFalse(QuickShopTaxAdapter.isNewerThanMinimum("build-7"));
        assertTrue(QuickShopTaxAdapter.isNewerThanMinimum("6.3.0.0-SNAPSHOT-12"));
        assertTrue(QuickShopTaxAdapter.isNewerThanMinimum("6.3.0.1"));
    }
}
