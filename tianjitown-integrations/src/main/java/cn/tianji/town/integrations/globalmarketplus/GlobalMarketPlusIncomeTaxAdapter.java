package cn.tianji.town.integrations.globalmarketplus;

import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
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
                    EventPriority.MONITOR, filteredExecutor(transactionEvent,
                            this::onTransaction), owner, true);
            owner.getServer().getPluginManager().registerEvent(auctionEvent, listener,
                    EventPriority.MONITOR, filteredExecutor(auctionEvent, this::onAuction), owner,
                    true);
            return Capability.success("GlobalMarketPlus "
                    + globalMarketPlus.getPluginMeta().getVersion()
                    + " 成交与拍卖结果事件已通过能力检查");
        } catch (ReflectiveOperationException | LinkageError exception) {
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
            long merchandiseId = ((Number) call(merchandise, "getMerchandiseUID")).longValue();
            dispatch(receiver, ((Number) call(result, "getPrice")).doubleValue(),
                    "transaction:" + merchandiseId);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            owner.getLogger().severe("GlobalMarketPlus 成交收入税处理失败: "
                    + message(exception));
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
            long merchandiseId = ((Number) call(auction, "getMerchandiseUID")).longValue();
            dispatch(receiver, ((Number) call(result, "getPrice")).doubleValue(),
                    "auction:" + merchandiseId);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            owner.getLogger().severe("GlobalMarketPlus 拍卖收入税处理失败: "
                    + message(exception));
        }
    }

    private boolean usesVault(Object merchandise) throws ReflectiveOperationException {
        Object currency = call(merchandise, "getCurrency");
        return currency != null && "Vault".equalsIgnoreCase(String.valueOf(
                call(currency, "getName")));
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
        Runnable task = () -> processor.accept(new Earning(player, receiverName, gross,
                businessKey));
        if (owner.getServer().isPrimaryThread()) {
            task.run();
        } else {
            owner.getServer().getScheduler().runTask(owner, task);
        }
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
    }

    private static void verifyAuctionApi(Class<? extends Event> eventType)
            throws ReflectiveOperationException {
        Class<?> result = eventType.getMethod("getAuctionResult").getReturnType();
        Class<?> auction = result.getMethod("getAuction").getReturnType();
        result.getMethod("getResultType");
        result.getMethod("getMerchant");
        result.getMethod("getPrice");
        verifyMerchandise(auction);
    }

    private static void verifyMerchandise(Class<?> merchandise)
            throws ReflectiveOperationException {
        merchandise.getMethod("getMerchandiseUID");
        Class<?> currency = merchandise.getMethod("getCurrency").getReturnType();
        currency.getMethod("getName");
    }

    private static EventExecutor filteredExecutor(Class<? extends Event> expectedType,
                                                   Consumer<Event> consumer) {
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

    @SuppressWarnings("unchecked")
    private static Class<? extends Event> eventClass(ClassLoader loader, String name)
            throws ClassNotFoundException {
        return (Class<? extends Event>) Class.forName(name, true, loader).asSubclass(Event.class);
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
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
