package org.allivlisey.tianjitown.paper.runtime;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.command.TownCommandParser;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TownRuntimeTasksTest {
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final CommandSender sender = mock(CommandSender.class);
    private final Queue<Runnable> worker = new ArrayDeque<>();
    private final Queue<Runnable> main = new ArrayDeque<>();
    private final AtomicBoolean available = new AtomicBoolean(true);
    private final TownRuntimeTasks tasks = new TownRuntimeTasks(plugin, available);

    TownRuntimeTasksTest() {
        when(plugin.messages()).thenReturn(mock(PluginMessages.class));
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.runAsync(any())).thenAnswer(call -> worker.add(call.getArgument(0)));
        when(plugin.runMain(any())).thenAnswer(call -> main.add(call.getArgument(0)));
    }

    @Test
    void asynchronousCommandParseFailureResolvesMessageAndKeepsStorageAvailable() {
        when(plugin.messages().plainText("chat.parser.missing-reason", Map.of()))
                .thenReturn("必须填写原因");
        tasks.read(sender, () -> TownCommandParser.namedAmountReason(
                        new String[]{"astrara", "5"}, 0, List.of("astrara")),
                ignored -> fail("invalid command must not succeed"));
        worker.remove().run();
        verify(plugin.messages(), never()).send(eq(sender), eq("chat.runtime.operation-failed"), anyMap());
        assertTrue(available.get());
        main.remove().run();
        verify(plugin.messages()).send(sender, "chat.runtime.operation-failed",
                Map.of("detail", "必须填写原因"));
    }

    @Test
    void lockedWritesDoNotRunAndSuccessfulReadRestoresSharedAvailability() {
        available.set(false);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        AtomicReference<String> result = new AtomicReference<>();
        tasks.writeAction(sender, () -> fail("locked write must not run"),
                ignored -> fail("locked write must not succeed"), failure::set);
        assertInstanceOf(TownRepository.StorageUnavailableException.class, failure.get());
        assertTrue(worker.isEmpty());

        tasks.readAction(sender, () -> "recovered", result::set, failure::set);
        assertFalse(available.get());
        assertNull(result.get());
        worker.remove().run();
        assertTrue(available.get());
        assertNull(result.get(), "success callback must wait for the main thread");
        main.remove().run();
        assertEquals("recovered", result.get());
    }

    @Test
    void diagnosticStorageFailureLocksWritesAndKeepsTheOriginalFailure() {
        var original = new org.allivlisey.tianjitown.storage.diagnostics.TownDiagnosticRepository
                .StorageUnavailableException("offline", null);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        tasks.readAction(sender, () -> { throw original; },
                ignored -> fail("failed diagnostic must not succeed"), failure::set);
        worker.remove().run();
        assertFalse(available.get());
        main.remove().run();
        assertSame(original, failure.get());
    }

    @Test
    void storageFailureLocksWritesAndReportsOriginalFailureOnMainThread() {
        RuntimeException original = new EconomyRepository.StorageUnavailableException("offline", null);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        tasks.readAction(sender, () -> { throw original; },
                ignored -> fail("failed read must not succeed"), failure::set);
        worker.remove().run();
        assertFalse(available.get());
        assertNull(failure.get());
        main.remove().run();
        assertSame(original, failure.get());
        tasks.write(sender, () -> fail("storage is unavailable"), ignored -> {});
        assertTrue(worker.isEmpty());
    }

    @Test
    void businessConflictDoesNotLockStorage() {
        RuntimeException original = new TownRepository.ConflictException("already changed");
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        tasks.writeAction(sender, () -> { throw original; },
                ignored -> fail("conflicting write must not succeed"), failure::set);
        worker.remove().run();
        assertTrue(available.get());
        main.remove().run();
        assertSame(original, failure.get());
    }
}
