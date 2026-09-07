package org.allivlisey.tianjitown.integrations.quickshop;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
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

    private static final UUID BUYER = UUID.fromString("9492769c-9c90-4481-90fb-92566807d29a");
    private static final java.time.Instant FIRST = java.time.Instant.parse("2026-09-06T14:56:57.786Z");
    private static final java.time.Instant SECOND = java.time.Instant.parse("2026-09-06T14:57:57.029Z");

    private static org.allivlisey.tianjitown.core.economy.QuickShopPurchase purchase(
            long shop, UUID buyer, long tax, java.time.Instant time) {
        return new org.allivlisey.tianjitown.core.economy.QuickShopPurchase(
                shop, "SELLING", buyer, 10000, tax, time);
    }

    private QuickShopHistoryProbe probe() {
        return new QuickShopHistoryProbe(org.mockito.Mockito.mock(org.bukkit.plugin.Plugin.class),
                2, (key, parameters) -> key);
    }

    private java.sql.Connection database(String prefix) throws Exception {
        var connection = java.sql.DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID());
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + prefix + "log_purchase (id INT, shop BIGINT,"
                    + " type VARCHAR, buyer VARCHAR, money DECIMAL(32,2), tax DECIMAL(32,2), time TIMESTAMP)");
            statement.execute("CREATE TABLE " + prefix + "log_transaction (id INT)");
        }
        return connection;
    }

    private void insert(java.sql.Connection connection, String prefix, int id, String type,
                        java.time.Instant time, long tax) throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO " + prefix + "log_purchase VALUES (?,271,?,?,100.00,?,?)")) {
            statement.setInt(1, id);
            statement.setString(2, type);
            statement.setString(3, BUYER.toString());
            statement.setBigDecimal(4, java.math.BigDecimal.valueOf(tax, 2));
            statement.setTimestamp(5, java.sql.Timestamp.from(time));
            statement.executeUpdate();
        }
    }

    @Test
    void matchesReportedPurchasesEvenWhenTransactionTableIsEmpty() throws Exception {
        for (String prefix : List.of("", "qs_")) {
            try (var connection = database(prefix)) {
                insert(connection, prefix, 985, "PURCHASE_SELLING_SHOP", FIRST, 500);
                insert(connection, prefix, 986, "PURCHASE_SELLING_SHOP", SECOND, 500);
                insert(connection, prefix, 987, "PURCHASE_SELLING_SHOP", SECOND.plusSeconds(100), 0);
                insert(connection, prefix, 984, "CREATE", FIRST.minusSeconds(40), 0);
                var result = probe().inspectPurchases(connection, prefix, FIRST.minusSeconds(60),
                        List.of(purchase(271, BUYER, 500, FIRST.plusMillis(40)),
                                purchase(271, BUYER, 500, SECOND.plusMillis(2))));
                assertTrue(result.available());
                assertFalse(result.truncated());
                assertEquals(2, result.successfulTaxRecords());
                assertEquals(1000, result.taxMinor());
            }
        }
    }

    @Test
    void cannotReusePurchaseOrMatchOtherShopPlayerTaxDirectionOrTime() {
        var actual = purchase(271, BUYER, 500, FIRST);
        var local = purchase(271, BUYER, 500, FIRST.plusMillis(40));
        var duplicate = QuickShopHistoryProbe.matchPurchases(List.of(actual),
                List.of(local, local), false, "ok");
        assertEquals(1, duplicate.successfulTaxRecords());
        for (var wrong : List.of(purchase(272, BUYER, 500, FIRST),
                purchase(271, UUID.randomUUID(), 500, FIRST),
                purchase(271, BUYER, 400, FIRST),
                purchase(271, BUYER, 500, FIRST.minusSeconds(121)),
                purchase(271, BUYER, 500, FIRST.plusSeconds(121)),
                new org.allivlisey.tianjitown.core.economy.QuickShopPurchase(
                        271, "BUYING", BUYER, 10000, 500, FIRST),
                new org.allivlisey.tianjitown.core.economy.QuickShopPurchase(
                        271, "SELLING", BUYER, 20000, 500, FIRST))) {
            assertEquals(0, QuickShopHistoryProbe.matchPurchases(
                    List.of(wrong), List.of(local), false, "ok").successfulTaxRecords());
        }
    }

    @Test
    void includesWriteDelayAtWindowBoundaryAndExcludesOldHistory() throws Exception {
        try (var connection = database("")) {
            insert(connection, "", 1, "PURCHASE_SELLING_SHOP", FIRST, 500);
            var expected = List.of(purchase(271, BUYER, 500, FIRST.plusMillis(40)));
            assertEquals(1, probe().inspectPurchases(connection, "",
                    FIRST.plusMillis(20), expected).successfulTaxRecords());
            assertEquals(0, probe().inspectPurchases(connection, "",
                    FIRST.plusSeconds(121), expected).successfulTaxRecords());
        }
    }

    @Test
    void countsAllScannedRowsForHistoryLimitAndPropagatesSqlFailure() throws Exception {
        try (var connection = database("")) {
            for (int i = 1; i <= 999; i++) {
                insert(connection, "", i, "CREATE", FIRST, 0);
            }
            assertFalse(probe().inspectPurchases(connection, "", FIRST, List.of()).truncated());
            insert(connection, "", 1000, "CREATE", FIRST, 0);
            assertTrue(probe().inspectPurchases(connection, "", FIRST, List.of()).truncated());
            insert(connection, "", 1001, "CREATE", FIRST, 0);
            assertTrue(probe().inspectPurchases(connection, "", FIRST, List.of()).truncated());
            assertThrows(java.sql.SQLException.class,
                    () -> probe().inspectPurchases(connection, "missing_", FIRST, List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> probe().inspectPurchases(connection, "bad;prefix", FIRST, List.of()));
        }
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

}
