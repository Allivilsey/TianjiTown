package cn.tianji.town.integrations.globalmarketplus;

import cn.tianji.town.integrations.ThirdPartyEventExecutor;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class GlobalMarketPlusIncomeTaxAdapter {
    private static final String TRANSACTION_RESULT_EVENT =
            "studio.trc.bukkit.globalmarketplus.api.event.TransactionResultEvent";
    private static final String AUCTION_RESULT_EVENT =
            "studio.trc.bukkit.globalmarketplus.api.event.AuctionResultEvent";
    private final Plugin owner;
    private final Plugin globalMarketPlus;
    private final BooleanSupplier taxEnabled;
    private final Consumer<Earning> processor;
    private final Set<Event> seenEvents = Collections.newSetFromMap(
            Collections.synchronizedMap(new WeakHashMap<>()));
    private final UUID startupId = UUID.randomUUID();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean eventFailureLogged = new AtomicBoolean();

    public GlobalMarketPlusIncomeTaxAdapter(Plugin owner, Plugin globalMarketPlus,
                                            BooleanSupplier taxEnabled,
                                            Consumer<Earning> processor) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.globalMarketPlus = Objects.requireNonNull(globalMarketPlus, "globalMarketPlus");
        this.taxEnabled = Objects.requireNonNull(taxEnabled, "taxEnabled");
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    public Capability register() {
        try {
            ClassLoader loader = globalMarketPlus.getClass().getClassLoader();
            Class<? extends Event> transactionEvent = eventClass(loader,
                    TRANSACTION_RESULT_EVENT);
            Class<? extends Event> auctionEvent = eventClass(loader, AUCTION_RESULT_EVENT);
            verifyTransactionApi(transactionEvent);
            verifyAuctionApi(auctionEvent);
            Listener listener = new Listener() {
            };
            owner.getServer().getPluginManager().registerEvent(transactionEvent, listener,
                    EventPriority.MONITOR, safeExecutor(transactionEvent,
                            this::onTransaction), owner, true);
            owner.getServer().getPluginManager().registerEvent(auctionEvent, listener,
                    EventPriority.MONITOR, safeExecutor(auctionEvent, this::onAuction), owner,
                    true);
            return Capability.success("GlobalMarketPlus "
                    + globalMarketPlus.getPluginMeta().getVersion()
                    + " 成交与拍卖结果事件已通过能力检查");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return Capability.failure("GlobalMarketPlus 收入税 API 能力检查失败: "
                    + message(exception));
        }
    }

    private void onTransaction(Event event) {
        if (!taxEnabled.getAsBoolean() || !seenEvents.add(event)) {
            return;
        }
        try {
            Object result = call(event, "getResult");
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
                    ? amountAfterSellingTax(receiver, merchandise, gross) : gross;
            long merchandiseId = ((Number) call(merchandise, "getMerchandiseUID")).longValue();
            dispatch(receiver, received, "transaction:" + merchandiseId);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logEventFailure("成交收入税处理失败", exception);
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
            long merchandiseId = ((Number) call(auction, "getMerchandiseUID")).longValue();
            dispatch(receiver, amountAfterNativeTax(price, nativeTax),
                    "auction:" + merchandiseId);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logEventFailure("拍卖收入税处理失败", exception);
        }
    }

    private boolean usesVault(Object merchandise) throws ReflectiveOperationException {
        Object currency = call(merchandise, "getCurrency");
        return currency != null && "Vault".equalsIgnoreCase(String.valueOf(
                call(currency, "getName")));
    }

    private double amountAfterSellingTax(Object receiver, Object merchandise, double gross)
            throws ReflectiveOperationException {
        Object currency = call(merchandise, "getCurrency");
        Object group = call(receiver, "getGroup");
        if (group == null || currency == null) {
            return gross;
        }
        double rate = ((Number) call(group, "getTaxRate_Selling", currency)).doubleValue();
        double nativeTax = gross * rate;
        if (nativeTax == 0.0D && rate != 0.0D) {
            nativeTax = 1.0D;
        }
        return amountAfterNativeTax(gross, nativeTax);
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
                logEventFailure("主线程收入税处理失败", exception);
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
                exception -> logEventFailure("事件边界捕获异常", exception));
    }

    private void logEventFailure(String context, Throwable throwable) {
        if (eventFailureLogged.compareAndSet(false, true)) {
            owner.getLogger().severe("GlobalMarketPlus " + context + ": " + message(throwable)
                    + "；同类后续错误将被抑制");
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
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
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
