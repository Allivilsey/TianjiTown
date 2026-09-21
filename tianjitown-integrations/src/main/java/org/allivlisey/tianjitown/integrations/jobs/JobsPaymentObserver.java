package org.allivlisey.tianjitown.integrations.jobs;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bukkit.OfflinePlayer;

/** Observes the confirmed wage deposit, without replacing Jobs' payment buffer or global Vault. */
final class JobsPaymentObserver implements AutoCloseable {
    private static final String PAYMENT_TASK = "com.gamingmesh.jobs.tasks.BufferedPaymentTask";
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Map<Object, Installation> installations = new IdentityHashMap<>();
    private final BiConsumer<OfflinePlayer, Double> paid;
    private final Consumer<Throwable> failed;

    JobsPaymentObserver(BiConsumer<OfflinePlayer, Double> paid, Consumer<Throwable> failed) {
        this.paid = paid;
        this.failed = failed;
    }

    synchronized void install(Object bufferedEconomy) throws ReflectiveOperationException {
        if (!active.get()) return;
        Field field = bufferedEconomy.getClass().getDeclaredField("economy");
        if (!field.trySetAccessible()) throw new IllegalAccessException("Jobs economy is inaccessible");
        Object delegate = field.get(bufferedEconomy);
        Installation existing = installations.get(bufferedEconomy);
        if (existing != null && delegate == existing.proxy()) return;
        Class<?> type = field.getType();
        if (!type.isInterface() || delegate == null) {
            throw new IllegalStateException("Jobs economy provider is not ready");
        }
        Method deposit = type.getMethod("depositPlayer", OfflinePlayer.class, double.class);
        if (deposit.getReturnType() != boolean.class) {
            throw new NoSuchMethodException("Jobs confirmed payment result is unavailable");
        }
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, args) -> invoke(delegate, method, args));
        field.set(bufferedEconomy, proxy);
        installations.put(bufferedEconomy, new Installation(field, delegate, proxy));
    }

    private Object invoke(Object delegate, Method method, Object[] args) throws Throwable {
        Object result;
        try {
            result = method.invoke(delegate, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
        // Jobs also deposits its own server taxes through this provider; those are not wages.
        if (active.get() && Boolean.TRUE.equals(result) && method.getName().equals("depositPlayer")
                && args != null && args.length == 2 && args[0] instanceof OfflinePlayer player
                && args[1] instanceof Double amount && Double.isFinite(amount) && amount > 0
                && StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                        PAYMENT_TASK.equals(frame.getClassName())))) {
            try {
                paid.accept(player, amount);
            } catch (RuntimeException | LinkageError exception) {
                // An observer cannot turn a confirmed wage into a failed payment and cause a retry.
                reportFailure(exception);
            }
        }
        return result;
    }

    @Override
    public synchronized void close() {
        active.set(false);
        for (var entry : installations.entrySet()) {
            Installation installation = entry.getValue();
            try {
                // Preserve an economy provider installed later by Jobs or another integration.
                if (installation.field().get(entry.getKey()) == installation.proxy()) {
                    installation.field().set(entry.getKey(), installation.delegate());
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                reportFailure(exception);
            }
        }
        installations.clear();
    }

    private void reportFailure(Throwable exception) {
        try {
            failed.accept(exception);
        } catch (RuntimeException | LinkageError ignored) {
            // Even unavailable logging must not change the result of the wage payment.
        }
    }

    private record Installation(Field field, Object delegate, Object proxy) { }
}
