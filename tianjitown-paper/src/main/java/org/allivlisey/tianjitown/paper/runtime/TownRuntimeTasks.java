package org.allivlisey.tianjitown.paper.runtime;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.storage.bonus.TownBonusRepository;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.allivlisey.tianjitown.storage.governance.GovernanceRepository;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.bukkit.command.CommandSender;

import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeMessage;
import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeText;

/** Worker/main-thread dispatch and shared database failure handling. */
final class TownRuntimeTasks {
    private static final String STORAGE_WRITE_LOCKED = "chat.lifecycle.storage-write-locked";
    private static final String PROVISION_APPLICATION_NOT_FOUND_DETAIL =
            "dialog.provision.application-not-found-detail";
    private final TianjiTownPlugin plugin;
    private final AtomicBoolean databaseAvailable;

    TownRuntimeTasks(TianjiTownPlugin plugin,
            AtomicBoolean databaseAvailable) {
        this.plugin = plugin;
        this.databaseAvailable = databaseAvailable;
    }

    <T> void read(CommandSender sender, Supplier<T> operation, Consumer<T> success) {
        execute(sender, false, operation, success);
    }

    <T> void write(CommandSender sender, Supplier<T> operation, Consumer<T> success) {
        execute(sender, true, operation, success);
    }

    <T> void writeAction(CommandSender sender, Supplier<T> operation, Consumer<T> success,
                         Consumer<RuntimeException> failure) {
        executeAction(sender, true, operation, success, failure);
    }

    <T> void readAction(CommandSender sender, Supplier<T> operation, Consumer<T> success,
                        Consumer<RuntimeException> failure) {
        executeAction(sender, false, operation, success, failure);
    }

    private <T> void execute(CommandSender sender, boolean write, Supplier<T> operation,
                             Consumer<T> success) {
        if (write && !databaseAvailable.get()) {
            plugin.messages().send(sender, "chat.runtime.storage-locked");
            return;
        }
        plugin.runAsync(() -> {
            try {
                T result = operation.get();
                databaseAvailable.set(true);
                plugin.runMain(() -> success.accept(result));
            } catch (RuntimeException exception) {
                handleFailure(sender, exception);
            }
        });
    }

    private <T> void executeAction(CommandSender sender, boolean write, Supplier<T> operation,
                                   Consumer<T> success, Consumer<RuntimeException> failure) {
        if (write && !databaseAvailable.get()) {
            failure.accept(new TownRepository.StorageUnavailableException(
                    plugin.messages().plainText(STORAGE_WRITE_LOCKED), null));
            return;
        }
        plugin.runAsync(() -> {
            try {
                T result = operation.get();
                databaseAvailable.set(true);
                plugin.runMain(() -> success.accept(result));
            } catch (RuntimeException exception) {
                markStorageFailure(exception);
                plugin.runMain(() -> failure.accept(exception));
            }
        });
    }

    void handleFailure(CommandSender sender, Throwable exception) {
        if (exception instanceof RuntimeException runtimeException) {
            markStorageFailure(runtimeException);
        }
        String detail = exception instanceof ApplicationNotFoundException
                ? plugin.messages().plainText(PROVISION_APPLICATION_NOT_FOUND_DETAIL)
                : safeText(safeMessage(exception));
        plugin.runMain(
                () -> plugin.messages().send(sender, "chat.runtime.operation-failed",
                        Map.of("detail", detail)));
    }

    void reportActionFailure(RuntimeException exception,
                                     Consumer<RuntimeException> failure) {
        markStorageFailure(exception);
        if (plugin.getServer().isPrimaryThread()) {
            failure.accept(exception);
        } else {
            plugin.runMain(() -> failure.accept(exception));
        }
    }

    void markStorageFailure(RuntimeException exception) {
        if (exception instanceof TownRepository.StorageUnavailableException
                || exception instanceof GovernanceRepository.StorageUnavailableException
                || exception instanceof EconomyRepository.StorageUnavailableException
                || exception instanceof CommerceRepository.StorageUnavailableException
                || exception instanceof TownBonusRepository.StorageUnavailableException) {
            databaseAvailable.set(false);
            plugin.getLogger().severe(exception.getMessage());
        }
    }
    static final class ApplicationNotFoundException extends RuntimeException {
    }
}
