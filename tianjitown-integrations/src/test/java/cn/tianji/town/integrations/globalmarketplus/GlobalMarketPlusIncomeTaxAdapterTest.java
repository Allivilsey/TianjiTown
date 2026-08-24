package cn.tianji.town.integrations.globalmarketplus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalMarketPlusIncomeTaxAdapterTest {
    @Test
    void calculatesTownTaxFromActualIncomeAfterNativeTax() {
        assertEquals(90.0D,
                GlobalMarketPlusIncomeTaxAdapter.amountAfterNativeTax(100.0D, 10.0D));
        assertEquals(0.0D,
                GlobalMarketPlusIncomeTaxAdapter.amountAfterNativeTax(5.0D, 10.0D));
    }

    @Test
    void rejectsNonFiniteSettlementAmounts() {
        assertEquals(0.0D,
                GlobalMarketPlusIncomeTaxAdapter.amountAfterNativeTax(Double.NaN, 1.0D));
        assertEquals(0.0D,
                GlobalMarketPlusIncomeTaxAdapter.amountAfterNativeTax(10.0D,
                        Double.POSITIVE_INFINITY));
    }
}
