package cn.tianji.town.paper;

import cn.tianji.town.integrations.vault.VaultSettlementService;
import cn.tianji.town.storage.economy.EconomyRepository;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class DonationCompensationCoordinator {
    private static final int DEFAULT_MAXIMUM_ATTEMPTS = 8;
    private static final long DEFAULT_INITIAL_DELAY_TICKS = 20L;
    private static final long DEFAULT_MAXIMUM_DELAY_TICKS = 20L * 30;

    private final Scheduler scheduler;
    private final PlayerRefund playerRefund;
    private final CompensationStore store;
    private final Listener listener;
    private final int maximumAttempts;
    private final long initialDelayTicks;
    private final long maximumDelayTicks;
    private final ConcurrentMap<UUID, Recovery> pending = new ConcurrentHashMap<>();

    DonationCompensationCoordinator(Scheduler scheduler, PlayerRefund playerRefund,
                                    CompensationStore store, Listener listener) {
        this(scheduler, playerRefund, store, listener, DEFAULT_MAXIMUM_ATTEMPTS,
                DEFAULT_INITIAL_DELAY_TICKS, DEFAULT_MAXIMUM_DELAY_TICKS);
    }

    DonationCompensationCoordinator(Scheduler scheduler, PlayerRefund playerRefund,
                                    CompensationStore store, Listener listener,
                                    int maximumAttempts, long initialDelayTicks,
                                    long maximumDelayTicks) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.playerRefund = Objects.requireNonNull(playerRefund, "playerRefund");
        this.store = Objects.requireNonNull(store, "store");
        this.listener = Objects.requireNonNull(listener, "listener");
        if (maximumAttempts <= 0) {
            throw new IllegalArgumentException("maximumAttempts 必须大于 0");
        }
        if (initialDelayTicks <= 0 || maximumDelayTicks < initialDelayTicks) {
            throw new IllegalArgumentException("自动补偿重试延迟配置无效");
        }
        this.maximumAttempts = maximumAttempts;
        this.initialDelayTicks = initialDelayTicks;
        this.maximumDelayTicks = maximumDelayTicks;
    }

    void submit(EconomyRepository.EconomyOperation operation) {
        Objects.requireNonNull(operation, "operation");
        if (!operation.operationType().equals("DONATION") || operation.actorId() == null) {
            throw new IllegalArgumentException("只有带玩家身份的捐款操作可以自动补偿");
        }
        Recovery recovery = new Recovery(operation);
        if (pending.putIfAbsent(operation.operationId(), recovery) == null) {
            scheduleExternalAttempt(recovery);
        }
    }

    int pendingCount() {
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
        VaultSettlementService.Result result;
        try {
            result = playerRefund.refund(recovery.operation.actorId(),
                    recovery.operation.amountMinor());
        } catch (RuntimeException | LinkageError exception) {
            result = VaultSettlementService.Result.failure(
                    "Vault 自动补偿调用异常: " + TownActionFailures.safeMessage(exception),
                    false, false);
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
                    "玩家扣款已由自动补偿恢复");
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

    interface Scheduler {
        void runMainLater(Runnable task, long delayTicks);

        void runAsync(Runnable task);
    }

    @FunctionalInterface
    interface PlayerRefund {
        VaultSettlementService.Result refund(UUID playerId, long amountMinor);
    }

    @FunctionalInterface
    interface CompensationStore {
        void resolve(UUID operationId, String detail);
    }

    interface Listener {
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
