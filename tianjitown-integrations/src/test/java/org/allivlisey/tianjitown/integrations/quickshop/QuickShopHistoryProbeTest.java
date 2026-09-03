package org.allivlisey.tianjitown.integrations.quickshop;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QuickShopHistoryProbeTest {
    @Test
    void callsStableApiWithoutResolvingOptionalConcreteMethodTypes() throws Exception {
        URL classes = QuickShopHistoryProbeTest.class.getProtectionDomain().getCodeSource()
                .getLocation();
        String implementationName = LinkageFixtureImplementation.class.getName();
        String optionalTypeName = LinkageFixtureOptionalType.class.getName();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes},
                QuickShopHistoryProbeTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                if (name.equals(optionalTypeName)) {
                    throw new ClassNotFoundException(name);
                }
                if (!name.equals(implementationName)) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
        }) {
            Object implementation = loader.loadClass(implementationName)
                    .getConstructor().newInstance();

            assertThrows(NoClassDefFoundError.class,
                    () -> implementation.getClass().getMethod("value"));
            assertEquals("available", QuickShopHistoryProbe.callApi(implementation,
                    LinkageFixtureApi.class, "value"));
        }
    }

    @Test
    void marksExactHistoryLimitAndOverLimitAsTruncated() throws Exception {
        UUID settlement = UUID.randomUUID();

        QuickShopHistoryProbe.Result below = QuickShopHistoryProbe.summarize(
                records(999, settlement), settlement, 2);
        QuickShopHistoryProbe.Result exact = QuickShopHistoryProbe.summarize(
                records(1_000, settlement), settlement, 2);
        QuickShopHistoryProbe.Result above = QuickShopHistoryProbe.summarize(
                records(1_001, settlement), settlement, 2);

        assertFalse(below.truncated());
        assertEquals(999, below.successfulTaxRecords());
        assertEquals(99_900, below.taxMinor());
        assertTrue(exact.truncated());
        assertEquals(1_000, exact.successfulTaxRecords());
        assertEquals(100_000, exact.taxMinor());
        assertTrue(above.truncated());
        assertEquals(1_001, above.successfulTaxRecords());
        assertEquals(100_100, above.taxMinor());
    }

    @Test
    void excludesForeignFailedAndInvalidHistoryRows() throws Exception {
        UUID settlement = UUID.randomUUID();
        List<Object> records = List.of(
                new MetricRecord(settlement, null, 1.005D),
                new MetricRecord(UUID.randomUUID(), null, 8.00D),
                new MetricRecord(settlement, "failed", 9.00D),
                new MetricRecord(settlement, null, Double.NaN),
                new MetricRecord(settlement, null, 0D));

        QuickShopHistoryProbe.Result result = QuickShopHistoryProbe.summarize(
                records, settlement, 2);

        assertTrue(result.available());
        assertFalse(result.truncated());
        assertEquals(1, result.successfulTaxRecords());
        assertEquals(101, result.taxMinor());
    }

    @Test
    void resolvesSuccessfulHistoryDetailThroughInjectedMessageResolver() throws Exception {
        UUID settlement = UUID.randomUUID();

        QuickShopHistoryProbe.Result result = QuickShopHistoryProbe.summarize(
                records(1, settlement), settlement, 2, (key, placeholders) -> {
                    assertEquals("diagnostic.quick-shop.history-success", key);
                    assertTrue(placeholders.isEmpty());
                    return "custom history detail";
                });

        assertEquals("custom history detail", result.detail());
    }

    private static List<Object> records(int count, UUID settlement) {
        List<Object> records = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            records.add(new MetricRecord(settlement, null, 1.00D));
        }
        return List.copyOf(records);
    }

    public interface LinkageFixtureApi {
        String value();
    }

    public static class LinkageFixtureImplementation implements LinkageFixtureApi {
        public LinkageFixtureImplementation() {
        }

        @Override
        public String value() {
            return "available";
        }

        public LinkageFixtureOptionalType optionalValue() {
            return null;
        }
    }

    public static class LinkageFixtureOptionalType {
    }

    public static final class MetricRecord {
        private final UUID taxAccount;
        private final String error;
        private final double taxAmount;

        MetricRecord(UUID taxAccount, String error, double taxAmount) {
            this.taxAccount = taxAccount;
            this.error = error;
            this.taxAmount = taxAmount;
        }

        public UUID getTaxAccount() {
            return taxAccount;
        }

        public String getError() {
            return error;
        }

        public double getTaxAmount() {
            return taxAmount;
        }
    }
}
