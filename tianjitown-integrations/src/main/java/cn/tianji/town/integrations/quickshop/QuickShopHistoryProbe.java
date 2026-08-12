package cn.tianji.town.integrations.quickshop;

import cn.tianji.town.core.economy.MoneyAmount;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class QuickShopHistoryProbe {
    private final Plugin quickShop;
    private final UUID settlementAccountId;
    private final int moneyScale;

    public QuickShopHistoryProbe(Plugin quickShop, UUID settlementAccountId, int moneyScale) {
        this.quickShop = Objects.requireNonNull(quickShop, "quickShop");
        this.settlementAccountId = Objects.requireNonNull(settlementAccountId,
                "settlementAccountId");
        this.moneyScale = moneyScale;
    }

    public Result inspect(Instant since) {
        Objects.requireNonNull(since, "since");
        if (!QuickShopTaxAdapter.SUPPORTED_VERSION.equals(
                quickShop.getPluginMeta().getVersion())) {
            return Result.unavailable("QuickShop 版本未通过交易历史适配器验证");
        }
        try {
            ClassLoader loader = quickShop.getClass().getClassLoader();
            Object quickShopCore = quickShop.getClass().getMethod("getQuickShop").invoke(quickShop);
            Object database = quickShopCore.getClass().getMethod("getDatabaseHelper")
                    .invoke(quickShopCore);
            Class<?> queryType = Class.forName("com.ghostchu.quickshop.database.MetricQuery",
                    true, loader);
            Constructor<?> constructor = java.util.Arrays.stream(queryType.getConstructors())
                    .filter(value -> value.getParameterCount() == 2)
                    .filter(value -> value.getParameterTypes()[0].isInstance(quickShopCore))
                    .filter(value -> value.getParameterTypes()[1].isInstance(database))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchMethodException("MetricQuery 构造器不存在"));
            Object query = constructor.newInstance(quickShopCore, database);
            Method queryTransactions = queryType.getMethod("queryTransactions", Date.class,
                    long.class, boolean.class);
            Object raw = queryTransactions.invoke(query, Date.from(since), 0L, true);
            if (!(raw instanceof List<?> records)) {
                return Result.unavailable("QuickShop 交易历史返回类型异常");
            }
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
                    "已读取 QuickShop transaction metric 历史");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            Throwable cause = exception instanceof InvocationTargetException invocation
                    && invocation.getCause() != null ? invocation.getCause() : exception;
            return Result.unavailable("QuickShop 交易历史读取失败: " + message(cause));
        }
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }

    private static String message(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getSimpleName() : value;
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
