package cn.tianji.town.paper;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class RetryingWorkQueue<T> {
    private final Scheduler scheduler;
    private final long initialRetryDelayTicks;
    private final long maximumRetryDelayTicks;
    private final Consumer<T> worker;
    private final FailureHandler<T> failureHandler;
    private final ConcurrentLinkedDeque<T> pending = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean retryScheduled = new AtomicBoolean();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    RetryingWorkQueue(Scheduler scheduler, long initialRetryDelayTicks,
                      long maximumRetryDelayTicks, Consumer<T> worker,
                      FailureHandler<T> failureHandler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (initialRetryDelayTicks <= 0) {
            throw new IllegalArgumentException("initialRetryDelayTicks 必须大于 0");
        }
        if (maximumRetryDelayTicks < initialRetryDelayTicks) {
            throw new IllegalArgumentException("maximumRetryDelayTicks 不能小于初始延迟");
        }
        this.initialRetryDelayTicks = initialRetryDelayTicks;
        this.maximumRetryDelayTicks = maximumRetryDelayTicks;
        this.worker = Objects.requireNonNull(worker, "worker");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    void submit(T item) {
        pending.addLast(Objects.requireNonNull(item, "item"));
        flush();
    }

    void flush() {
        if (pending.isEmpty() || !draining.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.executeAsync(this::drain);
        } catch (RuntimeException | LinkageError exception) {
            draining.set(false);
            throw asRuntimeException(exception);
        }
    }

    int pendingCount() {
        return pending.size();
    }

    private void drain() {
        boolean failed = false;
        RuntimeException notificationFailure = null;
        try {
            T item;
            while ((item = pending.pollFirst()) != null) {
                try {
                    worker.accept(item);
                } catch (RuntimeException | LinkageError exception) {
                    pending.addFirst(item);
                    failed = true;
                    RuntimeException failure = asRuntimeException(exception);
                    try {
                        failureHandler.onFailure(item, failure);
                    } catch (RuntimeException | LinkageError handlerException) {
                        failure.addSuppressed(handlerException);
                        notificationFailure = asRuntimeException(handlerException);
                    }
                    break;
                }
            }
        } finally {
            draining.set(false);
        }
        if (failed) {
            scheduleRetry();
            if (notificationFailure != null) {
                throw notificationFailure;
            }
            return;
        }
        consecutiveFailures.set(0);
        // 提交动作可能恰好发生在最后一次 poll 与 draining 复位之间。
        if (!pending.isEmpty()) {
            flush();
        }
    }

    private void scheduleRetry() {
        int failures = consecutiveFailures.updateAndGet(value ->
                value == Integer.MAX_VALUE ? value : value + 1);
        if (!retryScheduled.compareAndSet(false, true)) {
            return;
        }
        long delayTicks = retryDelayTicks(failures);
        try {
            scheduler.schedule(() -> {
                retryScheduled.set(false);
                flush();
            }, delayTicks);
        } catch (RuntimeException | LinkageError exception) {
            retryScheduled.set(false);
            throw asRuntimeException(exception);
        }
    }

    private static RuntimeException asRuntimeException(Throwable throwable) {
        return throwable instanceof RuntimeException exception
                ? exception
                : new IllegalStateException("可选依赖链接异常", throwable);
    }

    private long retryDelayTicks(int failures) {
        long delay = initialRetryDelayTicks;
        for (int attempt = 1; attempt < failures; attempt++) {
            if (delay >= maximumRetryDelayTicks
                    || delay > maximumRetryDelayTicks / 2) {
                return maximumRetryDelayTicks;
            }
            delay *= 2;
        }
        return Math.min(delay, maximumRetryDelayTicks);
    }

    interface Scheduler {
        void executeAsync(Runnable task);

        void schedule(Runnable task, long delayTicks);
    }

    @FunctionalInterface
    interface FailureHandler<T> {
        void onFailure(T item, RuntimeException exception);
    }
}
