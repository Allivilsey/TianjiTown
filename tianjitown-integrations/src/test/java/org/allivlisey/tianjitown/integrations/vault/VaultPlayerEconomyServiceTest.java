package org.allivlisey.tianjitown.integrations.vault;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VaultPlayerEconomyServiceTest {
    private final Server server = mock(Server.class);
    private final Economy provider = mock(Economy.class);
    private final OfflinePlayer player = mock(OfflinePlayer.class);
    private VaultPlayerEconomyService wallet;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        var services = mock(ServicesManager.class);
        var registration = mock(RegisteredServiceProvider.class);
        when(server.getServicesManager()).thenReturn(services);
        when(server.isPrimaryThread()).thenReturn(true);
        when(services.getRegistration(Economy.class)).thenReturn(registration);
        when(registration.getProvider()).thenReturn(provider);
        when(provider.isEnabled()).thenReturn(true);
        when(provider.fractionalDigits()).thenReturn(2);
        wallet = new VaultPlayerEconomyService(server, 2);
        clearInvocations(provider, server);
    }

    @Test void donationAndRefundOnlyTouchThePlayer() {
        when(provider.withdrawPlayer(player, 12.34)).thenReturn(success());
        when(provider.depositPlayer(player, 12.34)).thenReturn(success());
        assertTrue(wallet.withdrawPlayer(player, 1234).success());
        assertTrue(wallet.depositPlayer(player, 1234).success());
        verify(provider).withdrawPlayer(player, 12.34);
        verify(provider).depositPlayer(player, 12.34);
        verify(provider, times(2)).isEnabled();
        verifyNoMoreInteractions(provider);
        verify(server, never()).getOfflinePlayer(org.mockito.ArgumentMatchers.anyString());
        verify(server, never()).getOfflinePlayer(org.mockito.ArgumentMatchers.any(java.util.UUID.class));
    }

    @Test void failedDebitDoesNotCreditOrRefundAnything() {
        when(provider.withdrawPlayer(player, 1)).thenReturn(new EconomyResponse(1, 0,
                EconomyResponse.ResponseType.FAILURE, "insufficient funds"));
        var result = wallet.withdrawPlayer(player, 100);
        assertFalse(result.success());
        assertFalse(result.compensationRequired());
        verify(provider, never()).depositPlayer(org.mockito.ArgumentMatchers.any(OfflinePlayer.class), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test void unknownDebitAndRefundAreNotRetriedOrAutomaticallyReversed() {
        when(provider.withdrawPlayer(player, 1)).thenThrow(new IllegalStateException("connection lost"));
        when(provider.depositPlayer(player, 1)).thenReturn(null);
        assertTrue(wallet.withdrawPlayer(player, 100).compensationRequired());
        assertTrue(wallet.refundDebitedPlayer(player, 100).compensationRequired());
        verify(provider).withdrawPlayer(player, 1);
        verify(provider).depositPlayer(player, 1);
    }

    @Test void unavailableProviderFailsBeforeAttemptAndFormattingHasFallback() {
        when(provider.isEnabled()).thenReturn(false);
        assertFalse(wallet.checkAvailability().success());
        assertFalse(wallet.withdrawPlayer(player, 100).compensationRequired());
        assertEquals("12.34", wallet.formatMinor(1234));
        verify(provider, never()).withdrawPlayer(org.mockito.ArgumentMatchers.any(OfflinePlayer.class), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test void rejectsOffThreadPaymentsAndInvalidAmounts() {
        assertThrows(IllegalArgumentException.class, () -> wallet.withdrawPlayer(player, 0));
        when(server.isPrimaryThread()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> wallet.depositPlayer(player, 100));
    }

    private static EconomyResponse success() {
        return new EconomyResponse(12.34, 100, EconomyResponse.ResponseType.SUCCESS, "");
    }
}
