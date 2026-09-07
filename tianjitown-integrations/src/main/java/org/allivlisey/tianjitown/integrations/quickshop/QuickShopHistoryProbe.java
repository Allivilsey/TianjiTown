package org.allivlisey.tianjitown.integrations.quickshop;

import org.allivlisey.tianjitown.core.economy.MoneyAmount;
import org.bukkit.plugin.Plugin;

import org.allivlisey.tianjitown.core.economy.QuickShopPurchase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.time.Duration;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

public final class QuickShopHistoryProbe {
    private final Plugin quickShop;
    private static final int HISTORY_LIMIT = 1_000;
    private static final Duration WRITE_DELAY = Duration.ofMinutes(2);
    private final int moneyScale;
    private final BiFunction<String, Map<String, ?>, String> messageResolver;

    public QuickShopHistoryProbe(Plugin quickShop, int moneyScale) {
        this(quickShop, moneyScale, (key, placeholders) -> key);
    }

    public QuickShopHistoryProbe(Plugin quickShop, int moneyScale,
                                 BiFunction<String, Map<String, ?>, String> messageResolver) {
        this.quickShop = Objects.requireNonNull(quickShop, "quickShop");
        this.moneyScale = moneyScale;
        this.messageResolver = Objects.requireNonNull(messageResolver, "messageResolver");
    }

    public Result inspect(Instant since, List<QuickShopPurchase> expected) {
        Objects.requireNonNull(since, "since");
        try {
            if (!QuickShopTaxAdapter.isAtLeastMinimum(
                    quickShop.getPluginMeta().getVersion())) {
                return Result.unavailable(resolveMessage(
                        "diagnostic.quick-shop.history-version-unsupported", Map.of()));
            }
            ClassLoader loader = quickShop.getClass().getClassLoader();
            Class<?> entryPointType = Class.forName(
                    "com.ghostchu.quickshop.QuickShopBukkit", false, loader);
            Object quickShopCore = callApi(quickShop, entryPointType, "getQuickShop");
            Class<?> apiType = Class.forName("com.ghostchu.quickshop.api.QuickShopAPI",
                    false, loader);
            Object database = callApi(quickShopCore, apiType, "getDatabaseHelper");
            // MetricQuery swallows SQL failures and reads log_transaction. Purchase logs
            // are independent; use a bounded SELECT so failures cannot look like empty history.
            Object manager = call(database, "getManager");
            Class<?> managerApi = database.getClass().getMethod("getManager").getReturnType();
            String prefix = (String) call(database, "getPrefix");
            try (Connection connection = (Connection) callApi(manager, managerApi, "getConnection")) {
                return inspectPurchases(connection, prefix, since, expected);
            }
        } catch (ReflectiveOperationException | SQLException | LinkageError | RuntimeException exception) {
            Throwable cause = exception instanceof InvocationTargetException invocation
                    && invocation.getCause() != null ? invocation.getCause() : exception;
            String detail = switch (cause) {
                case ApiTargetTypeMismatchException mismatch -> resolveMessage(
                        "diagnostic.quick-shop.api-target-type-mismatch",
                        Map.of("type", mismatch.apiTypeName()));
                default -> message(cause);
            };
            return Result.unavailable(resolveMessage(
                    "diagnostic.quick-shop.history-read-failure", Map.of("detail", detail)));
        }
    }

    static Object callApi(Object target, Class<?> apiType, String name)
            throws ReflectiveOperationException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(apiType, "apiType");
        Objects.requireNonNull(name, "name");
        if (!apiType.isInstance(target)) {
            throw new ApiTargetTypeMismatchException(apiType.getName());
        }
        return apiType.getMethod(name).invoke(target);
    }

    Result inspectPurchases(Connection connection, String prefix, Instant since,
                            List<QuickShopPurchase> expected) throws SQLException {
        if (prefix == null || !prefix.matches("[A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid QuickShop table prefix");
        }
        List<QuickShopPurchase> purchases = new ArrayList<>();
        int scanned = 0;
        try (var statement = connection.prepareStatement(
                "SELECT shop, type, buyer, money, tax, time FROM " + prefix
                        + "log_purchase WHERE time >= ? ORDER BY id DESC LIMIT " + HISTORY_LIMIT)) {
            statement.setTimestamp(1, Timestamp.from(since.minus(WRITE_DELAY)));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    scanned++;
                    String type = rows.getString("type");
                    if (!"PURCHASE_SELLING_SHOP".equals(type)
                            && !"PURCHASE_BUYING_SHOP".equals(type)) {
                        continue;
                    }
                    BigDecimal tax = rows.getBigDecimal("tax");
                    if (tax == null || tax.signum() <= 0) {
                        continue;
                    }
                    purchases.add(new QuickShopPurchase(rows.getLong("shop"),
                            type.equals("PURCHASE_SELLING_SHOP") ? "SELLING" : "BUYING",
                            UUID.fromString(rows.getString("buyer")),
                            MoneyAmount.rounded(rows.getBigDecimal("money"), moneyScale,
                                    RoundingMode.HALF_UP).minorUnits(),
                            MoneyAmount.rounded(tax, moneyScale, RoundingMode.HALF_UP).minorUnits(),
                            rows.getTimestamp("time").toInstant()));
                }
            }
        }
        return matchPurchases(purchases, expected, scanned >= HISTORY_LIMIT,
                resolveMessage("diagnostic.quick-shop.history-success", Map.of()));
    }

    static Result matchPurchases(List<QuickShopPurchase> purchases,
                                 List<QuickShopPurchase> expected, boolean truncated,
                                 String detail) {
        // Match multiplicities, not just totals. A purchase cannot prove two local entries.
        // Scope is the local tax records: unrelated purchases cannot be attributed to this
        // settlement account because log_purchase does not persist the dynamic taxer.
        List<QuickShopPurchase> remaining = new ArrayList<>(purchases);
        long count = 0;
        long taxMinor = 0;
        for (QuickShopPurchase local : expected.stream()
                .sorted(java.util.Comparator.comparing(QuickShopPurchase::createdAt)).toList()) {
            QuickShopPurchase match = remaining.stream()
                    .filter(row -> row.shopId() == local.shopId()
                            && row.shopType().equals(local.shopType())
                            && row.interactingId().equals(local.interactingId())
                            && row.grossMinor() == local.grossMinor()
                            && row.taxMinor() == local.taxMinor()
                            && Duration.between(row.createdAt(), local.createdAt()).abs()
                                    .compareTo(WRITE_DELAY) <= 0)
                    .min(java.util.Comparator.comparing(QuickShopPurchase::createdAt))
                    .orElse(null);
            if (match != null) {
                remaining.remove(match);
                count++;
                taxMinor = Math.addExact(taxMinor, match.taxMinor());
            }
        }
        return Result.available(count, taxMinor, truncated, detail);
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }

    private static String message(Throwable throwable) {
        String value = throwable.getMessage();
        String detail = value == null || value.isBlank()
                ? throwable.getClass().getSimpleName() : value;
        return detail.replace('&', '＆').replace('§', '�');
    }

    private String resolveMessage(String key, Map<String, ?> placeholders) {
        return resolveMessage(messageResolver, key, placeholders);
    }

    private static String resolveMessage(BiFunction<String, Map<String, ?>, String> resolver,
                                         String key, Map<String, ?> placeholders) {
        try {
            String resolved = resolver.apply(key, placeholders);
            return resolved == null || resolved.isBlank() ? key : resolved;
        } catch (RuntimeException | LinkageError exception) {
            return key + " " + placeholders;
        }
    }

    private static final class ApiTargetTypeMismatchException extends IllegalArgumentException {
        private final String apiTypeName;

        private ApiTargetTypeMismatchException(String apiTypeName) {
            this.apiTypeName = apiTypeName;
        }

        private String apiTypeName() {
            return apiTypeName;
        }
    }

    public record Result(boolean available, long successfulTaxRecords, long taxMinor,
                         boolean truncated, String detail) {
        public static Result available(long records, long taxMinor, boolean truncated,
                                       String detail) {
            return new Result(true, records, taxMinor, truncated, detail);
        }

        public static Result unavailable(String detail) {
            return new Result(false, 0, 0, false, detail);
        }
    }
}
