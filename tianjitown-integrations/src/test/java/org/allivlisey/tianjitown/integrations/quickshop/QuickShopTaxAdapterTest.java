package cn.tianji.town.integrations.quickshop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickShopTaxAdapterTest {
    @Test
    void acceptsQuickShopVersionAtLeastSixThree() {
        assertTrue(QuickShopTaxAdapter.isAtLeastMinimum("6.3.0.0"));
        assertFalse(QuickShopTaxAdapter.isAtLeastMinimum("6.2.0.11"));
        assertFalse(QuickShopTaxAdapter.isAtLeastMinimum("build-7"));
        assertTrue(QuickShopTaxAdapter.isAtLeastMinimum("6.3.0.0-SNAPSHOT-12"));
        assertTrue(QuickShopTaxAdapter.isAtLeastMinimum("6.3.0.1"));
    }
}
