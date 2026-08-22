package cn.tianji.town.integrations.jobs;

import cn.tianji.town.integrations.ThirdPartyEventExecutor;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

public final class JobsIncomeTaxAdapter {
    private static final String PAYMENT_EVENT = "com.gamingmesh.jobs.api.JobsPaymentEvent";
    private final Plugin owner;
    private final Plugin jobs;
    private final BooleanSupplier taxEnabled;
    private final Function<Earning, TaxResult> processor;
    private final AtomicBoolean warnedAsync = new AtomicBoolean();
    private final AtomicBoolean eventFailureLogged = new AtomicBoolean();

    public JobsIncomeTaxAdapter(Plugin owner, Plugin jobs, BooleanSupplier taxEnabled,
                                Function<Earning, TaxResult> processor) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.taxEnabled = Objects.requireNonNull(taxEnabled, "taxEnabled");
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    public Capability register() {
        try {
            Class<? extends Event> eventType = eventClass(jobs.getClass().getClassLoader(),
                    PAYMENT_EVENT);
            eventType.getMethod("getPlayer");
            eventType.getMethod("getAmount");
            eventType.getMethod("setAmount", double.class);
            Listener listener = new Listener() {
            };
            owner.getServer().getPluginManager().registerEvent(eventType, listener,
                    EventPriority.HIGHEST, safeExecutor(eventType, this::onPayment), owner,
                    true);
            return Capability.success("Jobs " + jobs.getPluginMeta().getVersion()
                    + " 收入事件与可变付款金额已通过能力检查");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return Capability.failure("Jobs 收入税 API 能力检查失败: " + message(exception));
        }
    }

    private void onPayment(Event event) {
        try {
            if (!taxEnabled.getAsBoolean()) {
                return;
            }
            if (event.isAsynchronous()) {
                if (warnedAsync.compareAndSet(false, true)) {
                    owner.getLogger().severe(
                            "Jobs 在异步线程派发付款事件，已跳过收入税以保护 Vault 一致性");
                }
                return;
            }
            OfflinePlayer player = (OfflinePlayer) call(event, "getPlayer");
            double gross = ((Number) call(event, "getAmount")).doubleValue();
            if (player == null || !Double.isFinite(gross) || gross <= 0) {
                return;
            }
            TaxResult result = processor.apply(new Earning(player, gross));
            if (result.applied()) {
                call(event, "setAmount", double.class, result.netAmount());
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logEventFailure("收入税处理失败，已保留玩家原始收入", exception);
        }
    }

    private EventExecutor safeExecutor(Class<? extends Event> expectedType,
                                       java.util.function.Consumer<Event> consumer) {
        return ThirdPartyEventExecutor.filtered(expectedType, consumer,
                exception -> logEventFailure("事件边界捕获异常", exception));
    }

    private void logEventFailure(String context, Throwable throwable) {
        if (eventFailureLogged.compareAndSet(false, true)) {
            owner.getLogger().severe("Jobs " + context + ": " + message(throwable)
                    + "；同类后续错误将被抑制");
        }
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

    public record Earning(OfflinePlayer player, double grossAmount) {
    }

    public record TaxResult(boolean applied, double netAmount) {
        public static TaxResult unchanged(double grossAmount) {
            return new TaxResult(false, grossAmount);
        }

        public static TaxResult taxed(double netAmount) {
            return new TaxResult(true, netAmount);
        }
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
