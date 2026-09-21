package org.allivlisey.tianjitown.integrations.globalmarketplus;

import org.allivlisey.tianjitown.integrations.ThirdPartyEventExecutor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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

public final class GlobalMarketPlusIncomeTaxAdapter {
    private static final String TRANSACTION_RESULT_EVENT =
            "studio.trc.bukkit.globalmarketplus.api.event.TransactionResultEvent";
    private static final String TRANSACTION_EVENT =
            "studio.trc.bukkit.globalmarketplus.api.event.TransactionEvent";
    private static final String AUCTION_RESULT_EVENT =
            "studio.trc.bukkit.globalmarketplus.api.event.AuctionResultEvent";
    private final Plugin owner;
    private final Plugin globalMarketPlus;
    private final BooleanSupplier taxEnabled;
    private final Consumer<Earning> processor;
    private final BiFunction<String, Map<String, ?>, String> messageResolver;
    private final Set<Event> seenEvents = Collections.newSetFromMap(
            Collections.synchronizedMap(new WeakHashMap<>()));
    private final UUID startupId = UUID.randomUUID();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean eventFailureLogged = new AtomicBoolean();
    private Method getNativeConfig;
    private final Map<Object, NativeTaxSnapshot> nativeTaxSnapshots = Collections.synchronizedMap(
            new WeakHashMap<>());

    public GlobalMarketPlusIncomeTaxAdapter(Plugin owner, Plugin globalMarketPlus,
                                            BooleanSupplier taxEnabled,
                                            Consumer<Earning> processor,
                                            BiFunction<String, Map<String, ?>, String> messageResolver) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.globalMarketPlus = Objects.requireNonNull(globalMarketPlus, "globalMarketPlus");
        this.taxEnabled = Objects.requireNonNull(taxEnabled, "taxEnabled");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.messageResolver = Objects.requireNonNull(messageResolver, "messageResolver");
    }

    public Capability register() {
        try {
            ClassLoader loader = globalMarketPlus.getClass().getClassLoader();
            Class<? extends Event> transactionEvent = eventClass(loader,
                    TRANSACTION_RESULT_EVENT);
            Class<? extends Event> beforeTransactionEvent = eventClass(loader, TRANSACTION_EVENT);
            Class<? extends Event> auctionEvent = eventClass(loader, AUCTION_RESULT_EVENT);
            verifyTransactionApi(transactionEvent);
            verifyAuctionApi(auctionEvent);
            getNativeConfig = Class.forName("studio.trc.bukkit.globalmarketplus.api.APIUtils", true,
                    loader).getMethod("getConfig", String.class);
            nativeTaxPaidOnUpload();
            beforeTransactionEvent.getMethod("getTransaction");
            beforeTransactionEvent.getMethod("getMerchandise");
            Listener listener = new Listener() {
            };
            owner.getServer().getPluginManager().registerEvent(beforeTransactionEvent, listener,
                    EventPriority.MONITOR, safeExecutor(beforeTransactionEvent,
                            this::beforeTransaction), owner, true);
            owner.getServer().getPluginManager().registerEvent(transactionEvent, listener,
                    EventPriority.MONITOR, safeExecutor(transactionEvent,
                            this::onTransaction), owner, true);
            owner.getServer().getPluginManager().registerEvent(auctionEvent, listener,
                    EventPriority.MONITOR, safeExecutor(auctionEvent, this::onAuction), owner,
                    true);
            return Capability.success(resolveMessage(
                    "diagnostic.global-market-plus.capability-success",
                    Map.of("version", String.valueOf(
                            globalMarketPlus.getPluginMeta().getVersion()))));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return Capability.failure(resolveMessage(
                    "diagnostic.global-market-plus.capability-failure",
                    Map.of("detail", message(exception))));
        }
    }

    private void beforeTransaction(Event event) {
        try {
            if (!taxEnabled.getAsBoolean()) return;
            Object merchandise = call(event, "getMerchandise");
            if (!usesVault(merchandise)
                    || !"SELLING".equals(String.valueOf(call(merchandise, "getMerchandiseType")))) return;
            Object transaction = call(event, "getTransaction");
            boolean retail = Boolean.TRUE.equals(call(transaction, "isRetail"));
            int amount = retail ? ((Number) call(transaction, "getAmount")).intValue()
                    : ((org.bukkit.inventory.ItemStack) call(merchandise, "getItem")).getAmount();
            double gross = retail ? ((Number) call(merchandise, "getRetailPrice")).doubleValue() * amount
                    : ((Number) call(merchandise, "getPrice")).doubleValue();
            boolean prepaid = nativeTaxPaidOnUpload();
            double paid = ((Number) call(merchandise, "getTaxed")).doubleValue();
            double rate = prepaid ? 0 : nativeSellingRate(call(merchandise, "getMerchant"), merchandise);
            double nativeTax;
            if (prepaid) {
                if (Boolean.TRUE.equals(call(call(merchandise, "getMerchandiseOption"), "isUnlimited"))) {
                    throw new IllegalStateException("INCOMPLETE: cannot allocate prepaid tax for unlimited merchandise");
                }
                nativeTax = allocatedPrepaidTax(paid,
                        ((Number) call(merchandise, "getInitialAmount")).intValue(), amount);
            } else {
                // GMP 1.4.1.4 Vault uses this exact multiplication without minimum tax or rounding.
                nativeTax = gross * rate;
            }
            nativeTaxSnapshots.put(transaction,
                    new NativeTaxSnapshot(prepaid, retail, amount, gross, paid, rate, nativeTax));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logEventFailure("log.global-market-plus.transaction-failure", exception);
        }
    }

    private void onTransaction(Event event) {
        if (!taxEnabled.getAsBoolean() || !seenEvents.add(event)) {
            return;
        }
        try {
            Object result = call(event, "getResult");
            NativeTaxSnapshot snapshot = nativeTaxSnapshots.remove(call(result, "getTransaction"));
            if (!"SUCCESSFUL".equals(String.valueOf(call(result, "getResultType")))) {
                return;
            }
            Object merchandise = call(event, "getMerchandise");
            if (!usesVault(merchandise)) {
                return;
            }
            String type = String.valueOf(call(result, "getMerchandiseType"));
            Object receiver = switch (type) {
                case "SELLING" -> call(result, "getMerchant");
                case "PURCHASING" -> call(result, "getTrader");
                default -> null;
            };
            if (receiver == null) {
                return;
            }
            double gross = ((Number) call(result, "getPrice")).doubleValue();
            double received = "SELLING".equals(type)
                    ? amountAfterSellingTax(receiver, merchandise, result, gross, snapshot) : gross;
            long merchandiseId = ((Number) call(merchandise, "getMerchandiseUID")).longValue();
            dispatch(receiver, received, "transaction:" + merchandiseId);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logEventFailure("log.global-market-plus.transaction-failure", exception);
        }
    }

    private void onAuction(Event event) {
        if (!taxEnabled.getAsBoolean() || !seenEvents.add(event)) {
            return;
        }
        try {
            Object result = call(event, "getAuctionResult");
            if (!"SUCCESSFUL".equals(String.valueOf(call(result, "getResultType")))) {
                return;
            }
            Object auction = call(result, "getAuction");
            if (!usesVault(auction)) {
                return;
            }
            Object receiver = call(result, "getMerchant");
            double price = ((Number) call(result, "getPrice")).doubleValue();
            double nativeTax = ((Number) call(result, "getExtraTaxed")).doubleValue();
            double prepaidTax = ((Number) call(auction, "getTaxed")).doubleValue();
            long merchandiseId = ((Number) call(auction, "getMerchandiseUID")).longValue();
            dispatch(receiver, amountAfterNativeTax(price, prepaidTax + nativeTax),
                    "auction:" + merchandiseId);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logEventFailure("log.global-market-plus.auction-failure", exception);
        }
    }

    private boolean usesVault(Object merchandise) throws ReflectiveOperationException {
        Object currency = call(merchandise, "getCurrency");
        return currency != null && "Vault".equalsIgnoreCase(String.valueOf(
                call(currency, "getName")));
    }

    private double amountAfterSellingTax(Object receiver, Object merchandise, Object result,
                                         double gross, NativeTaxSnapshot snapshot)
            throws ReflectiveOperationException {
        if (snapshot == null || snapshot.prepaid() != nativeTaxPaidOnUpload()
                || snapshot.amount() != ((Number) call(result, "getAmount")).intValue()
                || !sameAmount(snapshot.gross(), gross)) {
            throw new IllegalStateException("INCOMPLETE: GMP transaction changed or tax snapshot is missing");
        }
        if (snapshot.prepaid()) return amountAfterNativeTax(gross, snapshot.nativeTax());
        if (snapshot.retail()) {
            double actualTax = ((Number) call(merchandise, "getTaxed")).doubleValue() - snapshot.paidBefore();
            if (!sameAmount(actualTax, snapshot.nativeTax())) {
                throw new IllegalStateException("INCOMPLETE: GMP retail tax changed during transaction");
            }
            return amountAfterNativeTax(gross, actualTax);
        }
        if (Double.compare(snapshot.rate(), nativeSellingRate(receiver, merchandise)) != 0) {
            throw new IllegalStateException("INCOMPLETE: GMP wholesale tax rate changed during transaction");
        }
        return amountAfterNativeTax(gross, snapshot.nativeTax());
    }

    private double nativeSellingRate(Object receiver, Object merchandise) throws ReflectiveOperationException {
        Object currency = call(merchandise, "getCurrency");
        Object group = call(receiver, "getGroup");
        if (group == null || currency == null) {
            throw new IllegalStateException("INCOMPLETE: GMP tax policy is unavailable");
        }
        double rate = ((Number) call(group, "getTaxRate_Selling", currency)).doubleValue();
        if (!Double.isFinite(rate) || rate < 0) {
            throw new IllegalStateException("INCOMPLETE: GMP native tax rate is invalid");
        }
        return rate;
    }

    private static boolean sameAmount(double expected, double actual) {
        return Double.isFinite(expected) && Double.isFinite(actual)
                && Math.abs(expected - actual) <= Math.max(1e-9, Math.abs(expected) * 1e-12);
    }

    record NativeTaxSnapshot(boolean prepaid, boolean retail, int amount, double gross,
                             double paidBefore, double rate, double nativeTax) { }

    private boolean nativeTaxPaidOnUpload() throws ReflectiveOperationException {
        Object config = getNativeConfig.invoke(null, "GlobalMarket.yml");
        if (!(config instanceof YamlConfiguration yaml)
                || !yaml.isBoolean("Transaction-After-Taxes")) {
            throw new IllegalStateException("INCOMPLETE: GMP tax timing is unavailable");
        }
        return yaml.getBoolean("Transaction-After-Taxes");
    }

    static double allocatedPrepaidTax(double paid, int initialAmount, int soldAmount) {
        if (!Double.isFinite(paid) || paid < 0 || initialAmount <= 0
                || soldAmount <= 0 || soldAmount > initialAmount) {
            throw new IllegalArgumentException("INCOMPLETE: invalid GMP prepaid tax allocation");
        }
        // Preserve fractional native tax here. Only the final town tax is rounded to money scale.
        return paid * ((double) soldAmount / initialAmount);
    }

    static double amountAfterNativeTax(double gross, double nativeTax) {
        if (!Double.isFinite(gross) || !Double.isFinite(nativeTax)) {
            return 0.0D;
        }
        return Math.max(0.0D, gross - Math.max(0.0D, nativeTax));
    }

    private void dispatch(Object receiver, double gross, String sourceKey)
            throws ReflectiveOperationException {
        if (!Double.isFinite(gross) || gross <= 0) {
            return;
        }
        UUID receiverId = (UUID) call(receiver, "getPlayerUUID");
        String receiverName = String.valueOf(call(receiver, "getPlayerName"));
        OfflinePlayer player = owner.getServer().getOfflinePlayer(receiverId);
        String businessKey = "globalmarketplus:" + startupId + ":"
                + sequence.incrementAndGet() + ":" + sourceKey;
        Runnable task = () -> {
            if (!owner.isEnabled()) {
                return;
            }
            try {
                processor.accept(new Earning(player, receiverName, gross, businessKey));
            } catch (RuntimeException | LinkageError exception) {
                logEventFailure("log.global-market-plus.main-thread-failure", exception);
            }
        };
        if (!owner.isEnabled()) {
            return;
        }
        // 等 GlobalMarketPlus 完成自身余额保存后再扣税，避免其旧余额覆盖 Vault 扣款。
        owner.getServer().getScheduler().runTask(owner, task);
    }

    private static void verifyTransactionApi(Class<? extends Event> eventType)
            throws ReflectiveOperationException {
        Class<?> result = eventType.getMethod("getResult").getReturnType();
        Class<?> merchandise = eventType.getMethod("getMerchandise").getReturnType();
        result.getMethod("getResultType");
        result.getMethod("getMerchandiseType");
        result.getMethod("getMerchant");
        result.getMethod("getTrader");
        result.getMethod("getPrice");
        result.getMethod("getAmount");
        Class<?> transaction = result.getMethod("getTransaction").getReturnType();
        transaction.getMethod("isRetail");
        transaction.getMethod("getAmount");
        merchandise.getMethod("getItem");
        merchandise.getMethod("getPrice");
        merchandise.getMethod("getRetailPrice");
        merchandise.getMethod("getMerchandiseType");
        merchandise.getMethod("getMerchant");
        verifyMerchandise(merchandise);
        Class<?> receiver = result.getMethod("getMerchant").getReturnType();
        Class<?> group = receiver.getMethod("getGroup").getReturnType();
        Class<?> currency = merchandise.getMethod("getCurrency").getReturnType();
        group.getMethod("getTaxRate_Selling", currency);
        verifyReceiver(receiver);
        verifyReceiver(result.getMethod("getTrader").getReturnType());
    }

    private static void verifyAuctionApi(Class<? extends Event> eventType)
            throws ReflectiveOperationException {
        Class<?> result = eventType.getMethod("getAuctionResult").getReturnType();
        Class<?> auction = result.getMethod("getAuction").getReturnType();
        result.getMethod("getResultType");
        result.getMethod("getMerchant");
        result.getMethod("getPrice");
        result.getMethod("getExtraTaxed");
        verifyMerchandise(auction);
        verifyReceiver(result.getMethod("getMerchant").getReturnType());
    }

    private static void verifyMerchandise(Class<?> merchandise)
            throws ReflectiveOperationException {
        merchandise.getMethod("getMerchandiseUID");
        merchandise.getMethod("getTaxed");
        merchandise.getMethod("getInitialAmount");
        merchandise.getMethod("getMerchandiseOption").getReturnType().getMethod("isUnlimited");
        Class<?> currency = merchandise.getMethod("getCurrency").getReturnType();
        currency.getMethod("getName");
    }

    private static void verifyReceiver(Class<?> receiver) throws ReflectiveOperationException {
        receiver.getMethod("getPlayerUUID");
        receiver.getMethod("getPlayerName");
    }

    private EventExecutor safeExecutor(Class<? extends Event> expectedType,
                                       Consumer<Event> consumer) {
        return ThirdPartyEventExecutor.filtered(expectedType, consumer,
                exception -> logEventFailure("log.global-market-plus.boundary-failure", exception));
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

    @SuppressWarnings("unchecked")
    private static Class<? extends Event> eventClass(ClassLoader loader, String name)
            throws ClassNotFoundException {
        return (Class<? extends Event>) Class.forName(name, true, loader).asSubclass(Event.class);
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        return call(target, name, null);
    }

    private static Object call(Object target, String name, Object argument)
            throws ReflectiveOperationException {
        try {
            Method method;
            if (argument == null) {
                method = target.getClass().getMethod(name);
                return method.invoke(target);
            }
            method = java.util.Arrays.stream(target.getClass().getMethods())
                    .filter(candidate -> candidate.getName().equals(name)
                            && candidate.getParameterCount() == 1
                            && candidate.getParameterTypes()[0].isInstance(argument))
                    .findFirst().orElseThrow(() -> new NoSuchMethodException(name));
            return method.invoke(target, argument);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw exception;
        }
    }

    private static String message(Throwable throwable) {
        String detail = throwable.getMessage() == null ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
        return detail.replace('&', '＆').replace('§', '�');
    }

    public record Earning(OfflinePlayer player, String receiverName, double grossAmount,
                          String businessKey) {
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
