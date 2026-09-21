package org.allivlisey.tianjitown.integrations.jobs;

import org.allivlisey.tianjitown.integrations.ThirdPartyEventExecutor;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

public final class JobsIncomeTaxAdapter implements AutoCloseable {
    private static final String PAYMENT_EVENT = "com.gamingmesh.jobs.api.JobsPaymentEvent";
    private final Plugin owner;
    private final Plugin jobs;
    private final BooleanSupplier taxEnabled;
    private final Function<Earning, TaxResult> processor;
    private final BiFunction<String, Map<String, ?>, String> messageResolver;
    private final AtomicBoolean eventFailureLogged = new AtomicBoolean();
    private final JobsPaymentObserver paymentObserver;

    public JobsIncomeTaxAdapter(Plugin owner, Plugin jobs, BooleanSupplier taxEnabled,
                                Function<Earning, TaxResult> processor,
                                BiFunction<String, Map<String, ?>, String> messageResolver) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.taxEnabled = Objects.requireNonNull(taxEnabled, "taxEnabled");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.messageResolver = Objects.requireNonNull(messageResolver, "messageResolver");
        this.paymentObserver = new JobsPaymentObserver((player, gross) -> {
            if (owner.isEnabled() && taxEnabled.getAsBoolean()) process(new Earning(player, gross));
        }, exception -> logEventFailure("log.jobs.payment-failure", exception));
    }

    public Capability register() {
        try {
            Class<? extends Event> eventType = eventClass(jobs.getClass().getClassLoader(),
                    PAYMENT_EVENT);
            installPaymentObserver();
            Listener listener = new Listener() {
            };
            owner.getServer().getPluginManager().registerEvent(eventType, listener,
                    EventPriority.MONITOR, safeExecutor(eventType, this::onPayment), owner,
                    true);
            owner.getServer().getPluginManager().registerEvent(PluginDisableEvent.class, listener,
                    EventPriority.MONITOR, (ignored, event) -> {
                        Plugin disabled = ((PluginDisableEvent) event).getPlugin();
                        if (disabled == owner || disabled == jobs) paymentObserver.close();
                    }, owner, true);
            return Capability.success(resolveMessage(
                    "diagnostic.jobs.capability-success",
                    Map.of("version", String.valueOf(jobs.getPluginMeta().getVersion()))));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            paymentObserver.close();
            return Capability.failure(resolveMessage(
                    "diagnostic.jobs.capability-failure",
                    Map.of("detail", message(exception))));
        }
    }

    private void onPayment(Event event) {
        try {
            // Jobs can replace its buffer on reload. The event only refreshes the observer;
            // no money or subsidy is created until depositPlayer actually returns success.
            installPaymentObserver();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logEventFailure("log.jobs.payment-failure", exception);
        }
    }

    private void installPaymentObserver() throws ReflectiveOperationException {
        Object buffered = jobs.getClass().getMethod("getEconomy").invoke(null);
        if (buffered == null) throw new IllegalStateException("Jobs economy provider is not ready");
        paymentObserver.install(buffered);
    }

    @Override
    public void close() {
        paymentObserver.close();
    }

    private TaxResult process(Earning earning) {
        if (owner.getServer().isPrimaryThread()) {
            return processor.apply(earning);
        }
        try {
            // Jobs may finish a wage on its economy worker; town Vault operations use the main thread.
            JobsTaxCall payment = new JobsTaxCall(() -> processor.apply(earning));
            owner.getServer().getScheduler().callSyncMethod(owner, payment);
            return payment.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(resolveMessage("log.jobs.await-interrupted", Map.of()),
                    exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException(resolveMessage("log.jobs.await-timeout", Map.of()),
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof LinkageError linkage) {
                throw linkage;
            }
            throw new IllegalStateException(
                    resolveMessage("log.jobs.main-thread-failure", Map.of()), cause);
        }
    }

    private EventExecutor safeExecutor(Class<? extends Event> expectedType,
                                       java.util.function.Consumer<Event> consumer) {
        return ThirdPartyEventExecutor.filtered(expectedType, consumer,
                exception -> logEventFailure("log.jobs.boundary-failure", exception));
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

    private static String message(Throwable throwable) {
        String detail = throwable.getMessage() == null ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
        return detail.replace('&', '＆').replace('§', '�');
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
