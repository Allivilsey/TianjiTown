package org.allivlisey.tianjitown.integrations.jobs;

import com.gamingmesh.jobs.tasks.BufferedPaymentTask;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class JobsPaymentObserverTest {
    public interface Economy {
        boolean depositPlayer(OfflinePlayer player, double amount);
        boolean withdrawPlayer(OfflinePlayer player, double amount);
    }
    static final class Buffer {
        private Economy economy;
        Buffer(Economy economy) { this.economy = economy; }
    }

    @Test
    void onlyConfirmedWageDepositsTriggerTax() throws Exception {
        var player = mock(OfflinePlayer.class);
        var delegate = mock(Economy.class);
        var buffer = new Buffer(delegate);
        List<Double> taxes = new ArrayList<>();
        try (var observer = new JobsPaymentObserver((recipient, amount) -> {
            assertSame(player, recipient);
            taxes.add(amount);
        }, fail -> fail(fail))) {
            observer.install(buffer);
            observer.install(buffer);
            when(delegate.depositPlayer(player, 100)).thenReturn(false, true);
            assertFalse(BufferedPaymentTask.pay(buffer.economy, player, 100));
            assertTrue(taxes.isEmpty());
            assertTrue(BufferedPaymentTask.pay(buffer.economy, player, 100));
            assertEquals(List.of(100D), taxes);
            // A server-tax deposit uses the same economy but is not a Jobs wage.
            assertTrue(buffer.economy.depositPlayer(player, 100));
            buffer.economy.withdrawPlayer(player, 100);
            assertEquals(List.of(100D), taxes);
        }
        assertSame(delegate, buffer.economy);
    }

    @Test
    void providerExceptionsAndObserverExceptionsDoNotFabricateOrRepeatWages() throws Exception {
        var player = mock(OfflinePlayer.class);
        var delegate = mock(Economy.class);
        var buffer = new Buffer(delegate);
        List<Throwable> failures = new ArrayList<>();
        try (var observer = new JobsPaymentObserver((recipient, amount) -> {
            throw new IllegalStateException("tax unavailable");
        }, failures::add)) {
            observer.install(buffer);
            when(delegate.depositPlayer(player, 100)).thenThrow(new IllegalArgumentException("offline"));
            assertThrows(IllegalArgumentException.class,
                    () -> BufferedPaymentTask.pay(buffer.economy, player, 100));
            assertTrue(failures.isEmpty());
            when(delegate.depositPlayer(player, 200)).thenReturn(true);
            assertTrue(BufferedPaymentTask.pay(buffer.economy, player, 200));
            assertEquals(1, failures.size());
            verify(delegate).depositPlayer(player, 200);
        }
    }

    @Test
    void shutdownDoesNotOverwriteLaterProviderAndCapturedTasksStopTaxing() throws Exception {
        var player = mock(OfflinePlayer.class);
        var delegate = mock(Economy.class);
        when(delegate.depositPlayer(player, 100)).thenReturn(true);
        var buffer = new Buffer(delegate);
        List<Double> taxes = new ArrayList<>();
        var observer = new JobsPaymentObserver((recipient, amount) -> taxes.add(amount), fail -> fail(fail));
        observer.install(buffer);
        Economy captured = buffer.economy;
        Economy replacement = mock(Economy.class);
        buffer.economy = replacement;
        observer.close();
        assertSame(replacement, buffer.economy);
        assertTrue(BufferedPaymentTask.pay(captured, player, 100));
        assertTrue(taxes.isEmpty());
    }
}
