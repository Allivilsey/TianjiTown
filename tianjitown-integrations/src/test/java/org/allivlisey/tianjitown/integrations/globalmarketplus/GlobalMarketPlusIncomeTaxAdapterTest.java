package org.allivlisey.tianjitown.integrations.globalmarketplus;

import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

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

    @Test
    void allocatesHistoricalUploadTaxBySoldQuantityWithoutIntermediateRounding() {
        assertEquals(2.0, GlobalMarketPlusIncomeTaxAdapter.allocatedPrepaidTax(10, 100, 20));
        double third = GlobalMarketPlusIncomeTaxAdapter.allocatedPrepaidTax(1, 3, 1);
        assertEquals(1.0, third * 3, 1e-12);
        assertEquals(90.0, GlobalMarketPlusIncomeTaxAdapter.amountAfterNativeTax(100,
                GlobalMarketPlusIncomeTaxAdapter.allocatedPrepaidTax(10, 100, 100)));
        assertThrows(IllegalArgumentException.class,
                () -> GlobalMarketPlusIncomeTaxAdapter.allocatedPrepaidTax(10, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> GlobalMarketPlusIncomeTaxAdapter.allocatedPrepaidTax(10, 2, 3));
    }

    @Test
    void auctionIncludesPrepaidTaxAndSubtractsAnyNativeTaxRefund() {
        assertEquals(80, GlobalMarketPlusIncomeTaxAdapter.amountAfterNativeTax(100, 10 + 10));
        assertEquals(95, GlobalMarketPlusIncomeTaxAdapter.amountAfterNativeTax(100, 10 - 5));
    }

    @Test
    void uploadTaxUsesStoredAmountAndNeverReadsSellersChangedTaxRate() throws Exception {
        NativeConfig.prepaid = true;
        var adapter = new GlobalMarketPlusIncomeTaxAdapter(mock(Plugin.class), mock(Plugin.class),
                () -> true, ignored -> {}, (key, values) -> key);
        var configMethod = GlobalMarketPlusIncomeTaxAdapter.class.getDeclaredField("getNativeConfig");
        configMethod.setAccessible(true);
        configMethod.set(adapter, NativeConfig.class.getMethod("getConfig", String.class));
        var calculate = GlobalMarketPlusIncomeTaxAdapter.class.getDeclaredMethod("amountAfterSellingTax",
                Object.class, Object.class, Object.class, double.class,
                GlobalMarketPlusIncomeTaxAdapter.NativeTaxSnapshot.class);
        calculate.setAccessible(true);
        // Stored upload tax was 10 for 100 units; current group access deliberately fails.
        assertEquals(18.0, (double) calculate.invoke(adapter, new ChangedMerchant(),
                new PrepaidMerchandise(), new PartialSale(), 20.0,
                new GlobalMarketPlusIncomeTaxAdapter.NativeTaxSnapshot(true, true,
                        20, 20, 10, 0, 2)));
    }

    @Test
    void retailUsesRecordedTaxIncrementAndRejectsChangedOrCombinedTransactions() throws Exception {
        NativeConfig.prepaid = false;
        var adapter = adapter();
        var snapshot = new GlobalMarketPlusIncomeTaxAdapter.NativeTaxSnapshot(false, true,
                20, 20, 8, 0.1, 2);
        assertEquals(18.0, calculate(adapter, new ChangedMerchant(), new PrepaidMerchandise(), snapshot));
        var changed = new GlobalMarketPlusIncomeTaxAdapter.NativeTaxSnapshot(false, true,
                20, 20, 7, 0.1, 2);
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> calculate(adapter, new ChangedMerchant(), new PrepaidMerchandise(), changed));
    }

    @Test
    void wholesaleUsesTransactionQuoteAndRejectsLaterRateChanges() throws Exception {
        NativeConfig.prepaid = false;
        var adapter = adapter();
        var snapshot = new GlobalMarketPlusIncomeTaxAdapter.NativeTaxSnapshot(false, false,
                20, 20, 0, 0.1, 2);
        var merchant = new SellingMerchant();
        assertEquals(18, calculate(adapter, merchant, new PrepaidMerchandise(), snapshot));
        merchant.group.rate = 0.2;
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> calculate(adapter, merchant, new PrepaidMerchandise(), snapshot));
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> calculate(adapter, merchant, new PrepaidMerchandise(), null));
    }

    @Test
    void capturesQuoteBeforeGmpUpdatesItsCumulativeRetailTax() throws Exception {
        NativeConfig.prepaid = false;
        var adapter = adapter();
        var event = new BeforeTransactionEvent();
        var method = GlobalMarketPlusIncomeTaxAdapter.class.getDeclaredMethod("beforeTransaction", Event.class);
        method.setAccessible(true);
        method.invoke(adapter, event);
        var field = GlobalMarketPlusIncomeTaxAdapter.class.getDeclaredField("nativeTaxSnapshots");
        field.setAccessible(true);
        var snapshots = (java.util.Map<?, ?>) field.get(adapter);
        var snapshot = (GlobalMarketPlusIncomeTaxAdapter.NativeTaxSnapshot)
                snapshots.get(event.getTransaction());
        assertEquals(10, snapshot.paidBefore());
        assertEquals(2, snapshot.nativeTax());
        event.merchandise.paid = 12;
        assertEquals(18, calculate(adapter, event.merchandise.getMerchant(), event.merchandise, snapshot));
    }

    private GlobalMarketPlusIncomeTaxAdapter adapter() throws Exception {
        var adapter = new GlobalMarketPlusIncomeTaxAdapter(mock(Plugin.class), mock(Plugin.class),
                () -> true, ignored -> {}, (key, values) -> key);
        var field = GlobalMarketPlusIncomeTaxAdapter.class.getDeclaredField("getNativeConfig");
        field.setAccessible(true);
        field.set(adapter, NativeConfig.class.getMethod("getConfig", String.class));
        return adapter;
    }

    private double calculate(GlobalMarketPlusIncomeTaxAdapter adapter, Object merchant,
                              Object merchandise, GlobalMarketPlusIncomeTaxAdapter.NativeTaxSnapshot snapshot)
            throws Exception {
        var method = GlobalMarketPlusIncomeTaxAdapter.class.getDeclaredMethod("amountAfterSellingTax",
                Object.class, Object.class, Object.class, double.class,
                GlobalMarketPlusIncomeTaxAdapter.NativeTaxSnapshot.class);
        method.setAccessible(true);
        return (double) method.invoke(adapter, merchant, merchandise, new PartialSale(), 20D, snapshot);
    }

    public static final class NativeConfig {
        static boolean prepaid;
        public static YamlConfiguration getConfig(String name) {
            assertEquals("GlobalMarket.yml", name);
            var config = new YamlConfiguration();
            config.set("Transaction-After-Taxes", prepaid);
            return config;
        }
    }
    public static final class ChangedMerchant {
        public Object getGroup() { throw new AssertionError("Historical tax must not use current group"); }
    }
    public static final class PrepaidMerchandise {
        double paid = 10;
        public double getTaxed() { return paid; }
        public int getInitialAmount() { return 100; }
        public Options getMerchandiseOption() { return new Options(); }
        public Currency getCurrency() { return new Currency(); }
        public String getMerchandiseType() { return "SELLING"; }
        public SellingMerchant getMerchant() { return new SellingMerchant(); }
        public double getRetailPrice() { return 1; }
        public double getPrice() { return 20; }
    }
    public static final class Options {
        public boolean isUnlimited() { return false; }
    }
    public static final class PartialSale {
        public int getAmount() { return 20; }
    }
    public static final class Currency { public String getName() { return "Vault"; } }
    public static final class SellingMerchant {
        final Group group = new Group();
        public Group getGroup() { return group; }
    }
    public static final class Group {
        double rate = 0.1;
        public double getTaxRate_Selling(Currency currency) { return rate; }
    }
    public static final class BeforeTransactionEvent extends Event {
        final PrepaidMerchandise merchandise = new PrepaidMerchandise();
        final NativeTransaction transaction = new NativeTransaction();
        public PrepaidMerchandise getMerchandise() { return merchandise; }
        public NativeTransaction getTransaction() { return transaction; }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }
    public static final class NativeTransaction {
        public boolean isRetail() { return true; }
        public int getAmount() { return 20; }
    }
}
