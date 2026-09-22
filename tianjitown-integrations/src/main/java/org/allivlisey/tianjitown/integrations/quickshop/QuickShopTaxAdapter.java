package org.allivlisey.tianjitown.integrations.quickshop;

import org.allivlisey.tianjitown.core.economy.MoneyAmount;
import org.allivlisey.tianjitown.integrations.ThirdPartyEventExecutor;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

public final class QuickShopTaxAdapter {
    public static final String MINIMUM_SUPPORTED_VERSION = "6.3.0.0";
    private static final String TAX_EVENT =
            "com.ghostchu.quickshop.api.event.economy.ShopEnhancedTaxEvent";
    private static final String TRANSACTION_EVENT =
            "com.ghostchu.quickshop.api.event.economy.EconomyTransactionEvent";
    private static final String SUCCESS_EVENT =
            "com.ghostchu.quickshop.api.event.economy.ShopSuccessPurchaseEvent";
    private final Plugin owner;
    private final Plugin quickShop;
    private final BooleanSupplier taxEnabled;
    private final Function<UUID, TaxPolicy> policyLookup;
    private final Consumer<SuccessfulTax> successConsumer;
    private final BiFunction<String, Map<String, ?>, String> messageResolver;
    private final int moneyScale;
    private final ThreadLocal<PendingTax> pending = new ThreadLocal<>();
    private final Set<Event> seenSuccessEvents = Collections.newSetFromMap(
            Collections.synchronizedMap(new WeakHashMap<>()));
    private final AtomicLong successSequence = new AtomicLong();
    private final AtomicBoolean eventFailureLogged = new AtomicBoolean();
    private final UUID startupId = UUID.randomUUID();
    private Class<?> qUserType;

    public QuickShopTaxAdapter(Plugin owner, Plugin quickShop,
                               BooleanSupplier taxEnabled,
                               Function<UUID, TaxPolicy> policyLookup,
                               Consumer<SuccessfulTax> successConsumer,
                               int moneyScale,
                               BiFunction<String, Map<String, ?>, String> messageResolver) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.quickShop = Objects.requireNonNull(quickShop, "quickShop");
        this.taxEnabled = Objects.requireNonNull(taxEnabled, "taxEnabled");
        this.policyLookup = Objects.requireNonNull(policyLookup, "policyLookup");
        this.successConsumer = Objects.requireNonNull(successConsumer, "successConsumer");
        this.messageResolver = Objects.requireNonNull(messageResolver, "messageResolver");
        this.moneyScale = moneyScale;
    }

    public Capability register() {
        try {
            String version = quickShop.getPluginMeta().getVersion();
            if (!isAtLeastMinimum(version)) {
                return Capability.failure(resolveMessage(
                        "diagnostic.quick-shop.tax-version-unsupported",
                        Map.of("minimum", MINIMUM_SUPPORTED_VERSION,
                                "version", safeText(String.valueOf(version)))));
            }
            ClassLoader loader = quickShop.getClass().getClassLoader();
            Class<? extends Event> taxEvent = eventClass(loader, TAX_EVENT);
            Class<? extends Event> transactionEvent = eventClass(loader, TRANSACTION_EVENT);
            Class<? extends Event> successEvent = eventClass(loader, SUCCESS_EVENT);
            qUserType = Class.forName("com.ghostchu.quickshop.api.obj.QUser", true, loader);
            verifyApi(taxEvent, transactionEvent, successEvent, qUserType);
            Listener listener = new Listener() {
            };
            owner.getServer().getPluginManager().registerEvent(taxEvent, listener,
                    EventPriority.HIGHEST, safeExecutor(taxEvent, this::onTax), owner, true);
            owner.getServer().getPluginManager().registerEvent(transactionEvent, listener,
                    EventPriority.HIGHEST, safeExecutor(transactionEvent,
                            this::onTransaction), owner, true);
            owner.getServer().getPluginManager().registerEvent(successEvent, listener,
                    EventPriority.MONITOR, safeExecutor(successEvent, this::onSuccess), owner,
                    true);
            return Capability.success(resolveMessage(
                    "diagnostic.quick-shop.tax-capability-success",
                    Map.of("version", safeText(String.valueOf(version)))));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return Capability.failure(resolveMessage(
                    "diagnostic.quick-shop.tax-capability-failure",
                    Map.of("detail", message(exception))));
        }
    }

    private void onTax(Event event) {
        try {
            if (!taxEnabled.getAsBoolean()) {
                pending.remove();
                return;
            }
            Object shop = call(event, "getShop");
            Object interacting = call(event, "getUser");
            boolean selling = (boolean) call(shop, "isSelling");
            Object receiver = selling ? call(shop, "getOwner") : interacting;
            UUID receiverId = (UUID) call(receiver, "getUniqueId");
            String receiverName = Objects.toString(call(receiver, "getUsername"),
                    receiverId.toString());
            UUID interactingId = (UUID) call(interacting, "getUniqueId");
            TaxPolicy policy = policyLookup.apply(receiverId);
            int basisPoints = policy == null ? 0 : policy.basisPoints();
            call(event, selling ? "setShopTax" : "setInteractorTax", double.class,
                    basisPoints / 10_000D);
            if (policy == null || basisPoints == 0) {
                pending.remove();
                return;
            }
            pending.set(new PendingTax(policy.townId(), receiverId, receiverName, interactingId,
                    (UUID) call(shop, "getRuntimeRandomUniqueId"),
                    ((Number) call(shop, "getShopId")).longValue(),
                    selling ? "SELLING" : "BUYING", basisPoints));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            pending.remove();
            logEventFailure("log.quick-shop.tax-event-failure", exception);
        }
    }

    private void onTransaction(Event event) {
        try {
            if (!taxEnabled.getAsBoolean()) {
                pending.remove();
                return;
            }
            PendingTax tax = pending.get();
            if (tax == null) {
                return;
            }
            Object transaction = call(event, "getTransaction");
            Object recipient = call(transaction, "to");
            UUID recipientId = recipient == null ? null : (UUID) call(recipient, "getUniqueId");
            if (!tax.receiverId().equals(recipientId)) {
                return;
            }
            call(transaction, "taxer", qUserType, null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            pending.remove();
            logEventFailure("log.quick-shop.transaction-account-failure", exception);
        }
    }

    private void onSuccess(Event event) {
        try {
            if (!taxEnabled.getAsBoolean()) {
                pending.remove();
                return;
            }
            PendingTax tax = pending.get();
            pending.remove();
            if (tax == null || !seenSuccessEvents.add(event)) {
                return;
            }
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
                    tax.shopType(), tax.receiverId(), tax.receiverName(), tax.interactingId(),
                    gross.minorUnits(),
                    tax.basisPoints(), taxAmount.minorUnits(), location.getWorld().getName()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logEventFailure("log.quick-shop.success-settlement-failure", exception);
        }
    }

    private MoneyAmount money(double amount) {
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException(resolveMessage(
                    "diagnostic.quick-shop.invalid-amount", Map.of()));
        }
        return MoneyAmount.rounded(BigDecimal.valueOf(amount), moneyScale, RoundingMode.HALF_UP);
    }

    private EventExecutor safeExecutor(Class<? extends Event> expectedType,
                                       Consumer<Event> consumer) {
        return ThirdPartyEventExecutor.filtered(expectedType, consumer,
                exception -> logEventFailure("log.quick-shop.boundary-failure", exception));
    }

    private void logEventFailure(String messageKey, Throwable throwable) {
        if (eventFailureLogged.compareAndSet(false, true)) {
            owner.getLogger().severe(resolveMessage(messageKey,
                    Map.of("detail", message(throwable))));
        }
    }

    private String resolveMessage(String key, Map<String, ?> placeholders) {
        try {
            String resolved = messageResolver.apply(key, placeholders);
            return resolved == null || resolved.isBlank() ? key : resolved;
        } catch (RuntimeException | LinkageError exception) {
            return key + " " + placeholders;
        }
    }

    private static void verifyApi(Class<? extends Event> taxEvent,
                                  Class<? extends Event> transactionEvent,
                                  Class<? extends Event> successEvent,
                                  Class<?> qUserType) throws NoSuchMethodException {
        Class<?> shopType = taxEvent.getMethod("getShop").getReturnType();
        taxEvent.getMethod("getUser");
        taxEvent.getMethod("setShopTax", double.class);
        taxEvent.getMethod("setInteractorTax", double.class);
        shopType.getMethod("isSelling");
        shopType.getMethod("getOwner");
        shopType.getMethod("getRuntimeRandomUniqueId");
        shopType.getMethod("getShopId");
        shopType.getMethod("getLocation");
        qUserType.getMethod("getUniqueId");
        qUserType.getMethod("getUsername");

        Class<?> transactionType = transactionEvent.getMethod("getTransaction").getReturnType();
        transactionType.getMethod("to");
        transactionType.getMethod("taxer", qUserType);

        successEvent.getMethod("getShop");
        successEvent.getMethod("getPurchaser");
        successEvent.getMethod("getTax");
        successEvent.getMethod("getBalance");
        successEvent.getMethod("getBalanceWithoutTax");
    }

    static boolean isAtLeastMinimum(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        int[] candidate = numericParts(version);
        int[] minimum = numericParts(MINIMUM_SUPPORTED_VERSION);
        if (candidate.length < 4) {
            return false;
        }
        int length = Math.max(candidate.length, minimum.length);
        for (int index = 0; index < length; index++) {
            int left = index < candidate.length ? candidate[index] : 0;
            int right = index < minimum.length ? minimum[index] : 0;
            if (left != right) {
                return left > right;
            }
        }
        return true;
    }

    private static int[] numericParts(String version) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+")
                .matcher(version);
        java.util.ArrayList<Integer> parts = new java.util.ArrayList<>();
        while (matcher.find()) {
            try {
                parts.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException exception) {
                return new int[0];
            }
        }
        return parts.stream().mapToInt(Integer::intValue).toArray();
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
        String detail = throwable.getMessage() == null ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
        return safeText(detail);
    }

    private static String safeText(String value) {
        return value.replace('&', '＆').replace('§', '�');
    }

    private record PendingTax(UUID townId, UUID receiverId, String receiverName,
                              UUID interactingId,
                              UUID shopRuntimeId, long shopId, String shopType,
                              int basisPoints) {
    }

    public record TaxPolicy(UUID townId, int basisPoints) {
    }

    public record SuccessfulTax(UUID townId, String businessKey, long shopId, String shopType,
                                UUID receiverId, String receiverName, UUID interactingId,
                                long grossMinor,
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
