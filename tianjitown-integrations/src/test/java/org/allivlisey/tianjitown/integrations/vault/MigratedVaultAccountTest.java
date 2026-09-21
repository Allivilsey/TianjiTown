package org.allivlisey.tianjitown.integrations.vault;

import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigratedVaultAccountTest {
    @Test void vaultKeepsOriginalUuidButPublishesNewNameAndNeverRecreatesTheOldAccount() {
        Server server = mock(Server.class);
        Economy economy = mock(Economy.class);
        ServicesManager services = mock(ServicesManager.class);
        @SuppressWarnings("unchecked")
        RegisteredServiceProvider<Economy> registration = mock(RegisteredServiceProvider.class);
        UUID id = UUID.randomUUID();
        OfflinePlayer oldIdentity = mock(OfflinePlayer.class);
        when(oldIdentity.getUniqueId()).thenReturn(id);
        when(oldIdentity.getName()).thenReturn("Tax");
        when(server.getOfflinePlayer(id)).thenReturn(oldIdentity);
        when(server.getServicesManager()).thenReturn(services);
        when(server.isPrimaryThread()).thenReturn(true);
        when(services.getRegistration(Economy.class)).thenReturn(registration);
        when(registration.getProvider()).thenReturn(economy);
        when(economy.fractionalDigits()).thenReturn(2);
        when(economy.isEnabled()).thenReturn(true);
        when(economy.hasAccount(oldIdentity)).thenReturn(true);
        when(economy.depositPlayer(oldIdentity, 12.34)).thenReturn(new EconomyResponse(
                12.34, 100.0, EconomyResponse.ResponseType.SUCCESS, ""));
        var settlement = new VaultSettlementService(server, "tianjitown-tax", 2, (key, args) -> key, id);
        assertEquals("tianjitown-tax", settlement.accountName());
        assertEquals(id, settlement.accountId());
        assertTrue(settlement.ensureAccount().success());
        assertTrue(settlement.adjustSettlement(1234).success());
        verify(economy).depositPlayer(oldIdentity, 12.34);
        verify(server, never()).getOfflinePlayer(anyString());
        when(economy.hasAccount(oldIdentity)).thenReturn(false);
        assertFalse(settlement.ensureAccount().success());
        verify(economy, never()).createPlayerAccount(any(OfflinePlayer.class));
    }
}
