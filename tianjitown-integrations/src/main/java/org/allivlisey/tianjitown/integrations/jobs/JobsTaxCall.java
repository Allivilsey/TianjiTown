package org.allivlisey.tianjitown.integrations.jobs;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** A payment event may abandon queued work, but must await work that began settlement. */
final class JobsTaxCall implements Callable<JobsIncomeTaxAdapter.TaxResult> {
    private final AtomicBoolean claimed = new AtomicBoolean();
    private final CompletableFuture<JobsIncomeTaxAdapter.TaxResult> result = new CompletableFuture<>();
    private final Supplier<JobsIncomeTaxAdapter.TaxResult> processor;

    JobsTaxCall(Supplier<JobsIncomeTaxAdapter.TaxResult> processor) {
        this.processor = processor;
    }

    @Override
    public JobsIncomeTaxAdapter.TaxResult call() {
        if (!claimed.compareAndSet(false, true)) {
            return null;
        }
        try {
            var value = processor.get();
            result.complete(value);
            return value;
        } catch (RuntimeException | LinkageError failure) {
            result.completeExceptionally(failure);
            throw failure;
        }
    }

    JobsIncomeTaxAdapter.TaxResult await(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        boolean interrupted = false;
        try {
            try {
                return result.get(timeout, unit);
            } catch (TimeoutException exception) {
                if (claimed.compareAndSet(false, true)) {
                    throw exception;
                }
            } catch (InterruptedException exception) {
                if (claimed.compareAndSet(false, true)) {
                    throw exception;
                }
                interrupted = true;
            }
            // Settlement has started. Returning the original amount now would mint untaxed income.
            while (true) {
                try {
                    return result.get();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
