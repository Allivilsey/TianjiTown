package cn.tianji.town.integrations.quickshop;

import cn.tianji.town.core.economy.MoneyAmount;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

public final class QuickShopTaxAdapter {
    public static final String SUPPORTED_VERSION = "6.2.0.10";
    private static final String TAX_EVENT =
            "com.ghostchu.quickshop.api.event.economy.ShopTaxEvent";
    private static final String TRANSACTION_EVENT =
            "com.ghostchu.quickshop.api.event.economy.EconomyTransactionEvent";
    private static final String SUCCESS_EVENT =
            "com.ghostchu.quickshop.api.event.economy.ShopSuccessPurchaseEvent";
    private static final String QUSER_IMPL = "com.ghostchu.quickshop.obj.QUserImpl";
    private final Plugin owner;
    private final Plugin quickShop;
    private final BooleanSupplier taxEnabled;
    private final Function<UUID, TaxPolicy> policyLookup;
    private final Consumer<SuccessfulTax> successConsumer;
    private final String settlementAccount;
    private final UUID settlementAccountId;
    private final int moneyScale;
    private final ThreadLocal<PendingTax> pending = new ThreadLocal<>();
    private final Set<Event> seenSuccessEvents = Collections.newSetFromMap(
            Collections.synchronizedMap(new WeakHashMap<>()));
    private final AtomicLong successSequence = new AtomicLong();
    private final UUID startupId = UUID.randomUUID();
    private Object settlementUser;
    private Class<?> qUserType;

    public QuickShopTaxAdapter(Plugin owner, Plugin quickShop,
                               BooleanSupplier taxEnabled,
                               Function<UUID, TaxPolicy> policyLookup,
                               Consumer<SuccessfulTax> successConsumer,
                               String settlementAccount, UUID settlementAccountId,
                               int moneyScale) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.quickShop = Objects.requireNonNull(quickShop, "quickShop");
        this.taxEnabled = Objects.requireNonNull(taxEnabled, "taxEnabled");
        this.policyLookup = Objects.requireNonNull(policyLookup, "policyLookup");
        this.successConsumer = Objects.requireNonNull(successConsumer, "successConsumer");
        this.settlementAccount = Objects.requireNonNull(settlementAccount, "settlementAccount");
        this.settlementAccountId = Objects.requireNonNull(settlementAccountId,
                "settlementAccountId");
        this.moneyScale = moneyScale;
    }

    public Capability register() {
        String version = quickShop.getPluginMeta().getVersion();
        if (!SUPPORTED_VERSION.equals(version)) {
            return Capability.failure("仅支持经验证的 QuickShop-Hikari " + SUPPORTED_VERSION
                    + "，当前为 " + version + "；动态税已保持关闭");
        }
        try {
            ClassLoader loader = quickShop.getClass().getClassLoader();
            Class<? extends Event> taxEvent = eventClass(loader, TAX_EVENT);
            Class<? extends Event> transactionEvent = eventClass(loader, TRANSACTION_EVENT);
            Class<? extends Event> successEvent = eventClass(loader, SUCCESS_EVENT);
            qUserType = Class.forName("com.ghostchu.quickshop.api.obj.QUser", true, loader);
            verifyApi(taxEvent, transactionEvent, successEvent, qUserType);
            Class<?> qUser = Class.forName(QUSER_IMPL, true, loader);
            settlementUser = qUser.getMethod("createFullFilled", UUID.class, String.class,
                    boolean.class).invoke(null, settlementAccountId, settlementAccount, true);
            Listener listener = new Listener() {
            };
            owner.getServer().getPluginManager().registerEvent(taxEvent, listener,
                    EventPriority.HIGHEST, filteredExecutor(taxEvent, this::onTax), owner, true);
            owner.getServer().getPluginManager().registerEvent(transactionEvent, listener,
                    EventPriority.HIGHEST, filteredExecutor(transactionEvent,
                            this::onTransaction), owner, true);
            owner.getServer().getPluginManager().registerEvent(successEvent, listener,
                    EventPriority.MONITOR, filteredExecutor(successEvent, this::onSuccess), owner,
                    true);
            return Capability.success("QuickShop " + version
                    + " 税率、交易账户、精确事件分流和成功事件签名已通过能力检查");
        } catch (ReflectiveOperationException | LinkageError exception) {
            return Capability.failure("QuickShop 税务 API 能力检查失败: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private void onTax(Event event) {
        if (!taxEnabled.getAsBoolean()) {
            pending.remove();
            return;
        }
        try {
            Object shop = call(event, "getShop");
            Object interacting = call(event, "getUser");
            boolean selling = (boolean) call(shop, "isSelling");
            Object receiver = selling ? call(shop, "getOwner") : interacting;
            UUID receiverId = (UUID) call(receiver, "getUniqueId");
            UUID interactingId = (UUID) call(interacting, "getUniqueId");
            TaxPolicy policy = policyLookup.apply(receiverId);
            int basisPoints = policy == null ? 0 : policy.basisPoints();
            call(event, "setTax", double.class, basisPoints / 10_000D);
            if (policy == null || basisPoints == 0) {
                pending.remove();
                return;
            }
            pending.set(new PendingTax(policy.townId(), receiverId, interactingId,
                    (UUID) call(shop, "getRuntimeRandomUniqueId"),
                    ((Number) call(shop, "getShopId")).longValue(),
                    selling ? "SELLING" : "BUYING", basisPoints));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            pending.remove();
            owner.getLogger().severe("QuickShop 税率事件处理失败: " + message(exception));
        }
    }

    private void onTransaction(Event event) {
        if (!taxEnabled.getAsBoolean()) {
            pending.remove();
            return;
        }
        PendingTax tax = pending.get();
        if (tax == null) {
            return;
        }
        try {
            Object transaction = call(event, "getTransaction");
            Object recipient = call(transaction, "getTo");
            UUID recipientId = recipient == null ? null : (UUID) call(recipient, "getUniqueId");
            if (!tax.receiverId().equals(recipientId)) {
                return;
            }
            call(transaction, "setTaxer", qUserType, settlementUser);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            pending.remove();
            owner.getLogger().severe("QuickShop 税款账户切换失败: " + message(exception));
        }
    }

    private void onSuccess(Event event) {
        if (!taxEnabled.getAsBoolean()) {
            pending.remove();
            return;
        }
        PendingTax tax = pending.get();
        pending.remove();
        if (tax == null || !seenSuccessEvents.add(event)) {
            return;
        }
        try {
            Object shop = call(event, "getShop");
            Object purchaser = call(event, "getPurchaser");
            UUID shopRuntimeId = (UUID) call(shop, "getRuntimeRandomUniqueId");
            UUID purchaserId = (UUID) call(purchaser, "getUniqueId");
            if (!tax.shopRuntimeId().equals(shopRuntimeId)
                    || !tax.interactingId().equals(purchaserId)) {
                return;
            }
            double taxValue = ((Number) call(event, "getTax")).doubleValue();
            double balance = Math.abs(((Number) call(event, "getBalance")).doubleValue());
            double beforeTax = Math.abs(((Number) call(event, "getBalanceWithoutTax")).doubleValue());
            MoneyAmount taxAmount = money(taxValue);
            MoneyAmount gross = money(Math.max(balance, beforeTax));
            if (taxAmount.minorUnits() <= 0 || gross.minorUnits() <= 0) {
                return;
            }
            Location location = (Location) call(shop, "getLocation");
            String businessKey = "quickshop:" + startupId + ":"
                    + successSequence.incrementAndGet();
            successConsumer.accept(new SuccessfulTax(tax.townId(), businessKey, tax.shopId(),
                    tax.shopType(), tax.receiverId(), tax.interactingId(), gross.minorUnits(),
                    tax.basisPoints(), taxAmount.minorUnits(), location.getWorld().getName()));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            owner.getLogger().severe("QuickShop 成功交易入账事件处理失败: " + message(exception));
        }
    }

    private MoneyAmount money(double amount) {
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("QuickShop 返回了无效金额");
        }
        return MoneyAmount.rounded(BigDecimal.valueOf(amount), moneyScale, RoundingMode.HALF_UP);
    }

    static EventExecutor filteredExecutor(Class<? extends Event> expectedType,
                                            Consumer<Event> consumer) {
        Objects.requireNonNull(expectedType, "expectedType");
        Objects.requireNonNull(consumer, "consumer");
        return (listener, event) -> {
            if (!expectedType.isInstance(event)) {
                return;
            }
            try {
                consumer.accept(event);
            } catch (RuntimeException exception) {
                throw new EventException(exception);
            }
        };
    }

    private static void verifyApi(Class<? extends Event> taxEvent,
                                  Class<? extends Event> transactionEvent,
                                  Class<? extends Event> successEvent,
                                  Class<?> qUserType) throws NoSuchMethodException {
        Class<?> shopType = taxEvent.getMethod("getShop").getReturnType();
        taxEvent.getMethod("getUser");
        taxEvent.getMethod("setTax", double.class);
        shopType.getMethod("isSelling");
        shopType.getMethod("getOwner");
        shopType.getMethod("getRuntimeRandomUniqueId");
        shopType.getMethod("getShopId");
        shopType.getMethod("getLocation");
        qUserType.getMethod("getUniqueId");

        Class<?> transactionType = transactionEvent.getMethod("getTransaction").getReturnType();
        transactionType.getMethod("getTo");
        transactionType.getMethod("setTaxer", qUserType);

        successEvent.getMethod("getShop");
        successEvent.getMethod("getPurchaser");
        successEvent.getMethod("getTax");
        successEvent.getMethod("getBalance");
        successEvent.getMethod("getBalanceWithoutTax");
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Event> eventClass(ClassLoader loader, String name)
            throws ClassNotFoundException {
        return (Class<? extends Event>) Class.forName(name, true, loader).asSubclass(Event.class);
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        return call(target, name, new Class<?>[0], new Object[0]);
    }

    private static Object call(Object target, String name, Class<?> parameter, Object value)
            throws ReflectiveOperationException {
        return call(target, name, new Class<?>[]{parameter}, new Object[]{value});
    }

    private static Object call(Object target, String name, Class<?>[] parameters, Object[] values)
            throws ReflectiveOperationException {
        try {
            Method method = target.getClass().getMethod(name, parameters);
            return method.invoke(target, values);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw exception;
        }
    }

    private static String message(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }

    private record PendingTax(UUID townId, UUID receiverId, UUID interactingId,
                              UUID shopRuntimeId, long shopId, String shopType,
                              int basisPoints) {
    }

    public record TaxPolicy(UUID townId, int basisPoints) {
    }

    public record SuccessfulTax(UUID townId, String businessKey, long shopId, String shopType,
                                UUID receiverId, UUID interactingId, long grossMinor,
                                int basisPoints, long taxMinor, String worldName) {
    }

    public record Capability(boolean available, String detail) {
        public static Capability success(String detail) {
            return new Capability(true, detail);
        }

        public static Capability failure(String detail) {
            return new Capability(false, detail);
        }
    }
}
