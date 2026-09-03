package org.allivlisey.tianjitown.integrations.quickshop;

import org.allivlisey.tianjitown.core.economy.MoneyAmount;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

public final class QuickShopHistoryProbe {
    private final Plugin quickShop;
    private final UUID settlementAccountId;
    private final int moneyScale;
    private final BiFunction<String, Map<String, ?>, String> messageResolver;

    public QuickShopHistoryProbe(Plugin quickShop, UUID settlementAccountId, int moneyScale) {
        this(quickShop, settlementAccountId, moneyScale, (key, placeholders) -> key);
    }

    public QuickShopHistoryProbe(Plugin quickShop, UUID settlementAccountId, int moneyScale,
                                 BiFunction<String, Map<String, ?>, String> messageResolver) {
        this.quickShop = Objects.requireNonNull(quickShop, "quickShop");
        this.settlementAccountId = Objects.requireNonNull(settlementAccountId,
                "settlementAccountId");
        this.moneyScale = moneyScale;
        this.messageResolver = Objects.requireNonNull(messageResolver, "messageResolver");
    }

    public Result inspect(Instant since) {
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
            Class<?> queryType = Class.forName("com.ghostchu.quickshop.database.MetricQuery",
                    true, loader);
            Constructor<?> constructor = java.util.Arrays.stream(queryType.getConstructors())
                    .filter(value -> value.getParameterCount() == 2)
                    .filter(value -> value.getParameterTypes()[0].isInstance(quickShopCore))
                    .filter(value -> value.getParameterTypes()[1].isInstance(database))
                    .findFirst()
                    .orElseThrow(MetricQueryConstructorMissingException::new);
            Object query = constructor.newInstance(quickShopCore, database);
            Method queryTransactions = queryType.getMethod("queryTransactions", Date.class,
                    long.class, boolean.class);
            Object raw = queryTransactions.invoke(query, Date.from(since), 0L, true);
            if (!(raw instanceof List<?> records)) {
                return Result.unavailable(resolveMessage(
                        "diagnostic.quick-shop.history-result-type-invalid", Map.of()));
            }
            return summarize(records, settlementAccountId, moneyScale, messageResolver);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            Throwable cause = exception instanceof InvocationTargetException invocation
                    && invocation.getCause() != null ? invocation.getCause() : exception;
            String detail = switch (cause) {
                case MetricQueryConstructorMissingException ignored -> resolveMessage(
                        "diagnostic.quick-shop.history-query-constructor-missing", Map.of());
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

    static Result summarize(List<?> records, UUID settlementAccountId, int moneyScale)
            throws ReflectiveOperationException {
        return summarize(records, settlementAccountId, moneyScale, (key, placeholders) -> key);
    }

    static Result summarize(List<?> records, UUID settlementAccountId, int moneyScale,
                            BiFunction<String, Map<String, ?>, String> messageResolver)
            throws ReflectiveOperationException {
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(settlementAccountId, "settlementAccountId");
        Objects.requireNonNull(messageResolver, "messageResolver");
        long successfulTaxRecords = 0;
        long taxMinor = 0;
        for (Object record : records) {
            UUID taxAccount = (UUID) call(record, "getTaxAccount");
            String error = (String) call(record, "getError");
            if (!settlementAccountId.equals(taxAccount)
                    || error != null && !error.isBlank()) {
                continue;
            }
            double value = ((Number) call(record, "getTaxAmount")).doubleValue();
            if (!Double.isFinite(value) || value <= 0) {
                continue;
            }
            successfulTaxRecords++;
            taxMinor = Math.addExact(taxMinor, MoneyAmount.rounded(BigDecimal.valueOf(value),
                    moneyScale, RoundingMode.HALF_UP).minorUnits());
        }
        return Result.available(successfulTaxRecords, taxMinor, records.size() >= 1_000,
                resolveMessage(messageResolver, "diagnostic.quick-shop.history-success", Map.of()));
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

    private static final class MetricQueryConstructorMissingException
            extends ReflectiveOperationException {
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
