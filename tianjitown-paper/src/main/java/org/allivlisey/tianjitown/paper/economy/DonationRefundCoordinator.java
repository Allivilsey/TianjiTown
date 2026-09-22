package org.allivlisey.tianjitown.paper.economy;
import org.allivlisey.tianjitown.paper.action.TownActionFailures;

import org.allivlisey.tianjitown.integrations.vault.VaultPlayerEconomyService;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

public final class DonationRefundCoordinator {
    private static final int DEFAULT_MAXIMUM_ATTEMPTS = 8;
    private static final long DEFAULT_INITIAL_DELAY_TICKS = 20L;
    private static final long DEFAULT_MAXIMUM_DELAY_TICKS = 20L * 30;
    private static final String INVALID_OPERATION =
            "validation.donation.refund-operation";
    private static final String REFUND_CALL_FAILURE =
            "diagnostic.donation.refund-call-failure";
    private static final String REFUND_RESOLVED = "log.donation.refund-resolved";

    private final Scheduler scheduler;
    private final PlayerRefund playerRefund;
    private final RefundStore store;
    private final Listener listener;
    private final BiFunction<String, Map<String, ?>, String> messageResolver;
    private final int maximumAttempts;
    private final long initialDelayTicks;
    private final long maximumDelayTicks;
    private final ConcurrentMap<UUID, Recovery> pending = new ConcurrentHashMap<>();

    public DonationRefundCoordinator(Scheduler scheduler, PlayerRefund playerRefund,
                               RefundStore store, Listener listener) {
        this(scheduler, playerRefund, store, listener, DEFAULT_MAXIMUM_ATTEMPTS,
                DEFAULT_INITIAL_DELAY_TICKS, DEFAULT_MAXIMUM_DELAY_TICKS,
                DonationRefundCoordinator::fallbackMessage);
    }

    public DonationRefundCoordinator(Scheduler scheduler, PlayerRefund playerRefund,
                               RefundStore store, Listener listener,
                               BiFunction<String, Map<String, ?>, String> messageResolver) {
        this(scheduler, playerRefund, store, listener, DEFAULT_MAXIMUM_ATTEMPTS,
                DEFAULT_INITIAL_DELAY_TICKS, DEFAULT_MAXIMUM_DELAY_TICKS, messageResolver);
    }

    public DonationRefundCoordinator(Scheduler scheduler, PlayerRefund playerRefund,
                               RefundStore store, Listener listener,
                               int maximumAttempts, long initialDelayTicks,
                               long maximumDelayTicks) {
        this(scheduler, playerRefund, store, listener, maximumAttempts, initialDelayTicks,
                maximumDelayTicks, DonationRefundCoordinator::fallbackMessage);
    }

    public DonationRefundCoordinator(Scheduler scheduler, PlayerRefund playerRefund,
                               RefundStore store, Listener listener,
                               int maximumAttempts, long initialDelayTicks,
                               long maximumDelayTicks,
                               BiFunction<String, Map<String, ?>, String> messageResolver) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.playerRefund = Objects.requireNonNull(playerRefund, "playerRefund");
        this.store = Objects.requireNonNull(store, "store");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.messageResolver = Objects.requireNonNull(messageResolver, "messageResolver");
        if (maximumAttempts <= 0) {
            throw new IllegalArgumentException("maximumAttempts 必须大于 0");
        }
        if (initialDelayTicks <= 0 || maximumDelayTicks < initialDelayTicks) {
            throw new IllegalArgumentException("自动退款重试延迟配置无效");
        }
        this.maximumAttempts = maximumAttempts;
        this.initialDelayTicks = initialDelayTicks;
        this.maximumDelayTicks = maximumDelayTicks;
    }

    public void submit(EconomyRepository.EconomyOperation operation) {
        Objects.requireNonNull(operation, "operation");
        if (!"DONATION".equals(operation.operationType()) || operation.actorId() == null) {
            throw new IllegalArgumentException(resolveMessage(INVALID_OPERATION, Map.of()));
        }
        Recovery recovery = new Recovery(operation);
        if (pending.putIfAbsent(operation.operationId(), recovery) == null) {
            scheduleExternalAttempt(recovery);
        }
    }

    public int pendingCount() {
        return pending.size();
    }

    private void scheduleExternalAttempt(Recovery recovery) {
        scheduler.runMainLater(() -> attemptExternalRefund(recovery),
                retryDelayTicks(recovery.externalAttempts + 1));
    }

    private void attemptExternalRefund(Recovery recovery) {
        if (pending.get(recovery.operation.operationId()) != recovery) {
            return;
        }
        recovery.externalAttempts++;
        VaultPlayerEconomyService.Result result;
        try {
            result = playerRefund.refund(recovery.operation.actorId(),
                    recovery.operation.amountMinor());
        } catch (RuntimeException | LinkageError exception) {
            result = VaultPlayerEconomyService.Result.failure(
                    resolveMessage(REFUND_CALL_FAILURE, Map.of("detail",
                            safeText(TownActionFailures.safeMessage(exception)))),
                    false, true);
        }
        if (result == null || result.compensationRequired()) {
            // Keep this operation registered to prevent resubmission from paying again.
            // Its persisted compensation record and account lock require manual verification.
            String detail = result == null
                    ? resolveMessage(REFUND_CALL_FAILURE, Map.of("detail", "null")) : result.message();
            listener.retryFailed(recovery.operation, recovery.externalAttempts, detail);
            listener.exhausted(recovery.operation, detail);
            return;
        }
        if (result.success()) {
            recovery.externalRestored = true;
            scheduler.runAsync(() -> finalizeRecovery(recovery));
            return;
        }
        listener.retryFailed(recovery.operation, recovery.externalAttempts, result.message());
        if (recovery.externalAttempts >= maximumAttempts) {
            pending.remove(recovery.operation.operationId(), recovery);
            listener.exhausted(recovery.operation, result.message());
            return;
        }
        scheduleExternalAttempt(recovery);
    }

    private void finalizeRecovery(Recovery recovery) {
        if (pending.get(recovery.operation.operationId()) != recovery
                || !recovery.externalRestored) {
            return;
        }
        recovery.storageAttempts++;
        try {
            store.resolve(recovery.operation.operationId(),
                    resolveMessage(REFUND_RESOLVED, Map.of()));
            pending.remove(recovery.operation.operationId(), recovery);
            listener.recovered(recovery.operation, recovery.externalAttempts);
        } catch (RuntimeException exception) {
            listener.finalizationFailed(recovery.operation, recovery.storageAttempts,
                    TownActionFailures.safeMessage(exception));
            if (recovery.storageAttempts >= maximumAttempts) {
                pending.remove(recovery.operation.operationId(), recovery);
                listener.exhausted(recovery.operation, TownActionFailures.safeMessage(exception));
                return;
            }
            scheduler.runMainLater(() -> scheduler.runAsync(() -> finalizeRecovery(recovery)),
                    retryDelayTicks(recovery.storageAttempts));
        }
    }

    private long retryDelayTicks(int attempt) {
        long delay = initialDelayTicks;
        for (int current = 1; current < attempt; current++) {
            if (delay >= maximumDelayTicks || delay > maximumDelayTicks / 2) {
                return maximumDelayTicks;
            }
            delay *= 2;
        }
        return Math.min(delay, maximumDelayTicks);
    }

    private String resolveMessage(String key, Map<String, ?> placeholders) {
        try {
            String resolved = messageResolver.apply(key, placeholders);
            return resolved == null || resolved.isBlank() ? key : resolved;
        } catch (RuntimeException | LinkageError exception) {
            return key;
        }
    }

    private static String fallbackMessage(String key, Map<String, ?> placeholders) {
        return key;
    }

    private static String safeText(String text) {
        return text == null ? "" : text.replace('&', '＆').replace('§', '�');
    }

    public interface Scheduler {
        void runMainLater(Runnable task, long delayTicks);

        void runAsync(Runnable task);
    }

    @FunctionalInterface
    public interface PlayerRefund {
        VaultPlayerEconomyService.Result refund(UUID playerId, long amountMinor);
    }

    @FunctionalInterface
    public interface RefundStore {
        void resolve(UUID operationId, String detail);
    }

    public interface Listener {
        void retryFailed(EconomyRepository.EconomyOperation operation, int attempt,
                         String detail);

        void finalizationFailed(EconomyRepository.EconomyOperation operation, int attempt,
                                String detail);

        void recovered(EconomyRepository.EconomyOperation operation, int attempts);

        void exhausted(EconomyRepository.EconomyOperation operation, String detail);
    }

    private static final class Recovery {
        private final EconomyRepository.EconomyOperation operation;
        private int externalAttempts;
        private int storageAttempts;
        private volatile boolean externalRestored;

        private Recovery(EconomyRepository.EconomyOperation operation) {
            this.operation = operation;
        }
    }
}
