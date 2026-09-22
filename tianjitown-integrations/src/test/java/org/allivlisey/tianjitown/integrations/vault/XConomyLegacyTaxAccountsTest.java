package org.allivlisey.tianjitown.integrations.vault;

import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.allivlisey.tianjitown.integrations.vault.SettlementAccountMigration.Account;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;

class XConomyLegacyTaxAccountsTest {
    final Server server = mock(Server.class);
    final Plugin provider = mock(Plugin.class);
    final PluginCommand command = mock(PluginCommand.class);
    final ConsoleCommandSender console = mock(ConsoleCommandSender.class);
    final Account account = new Account(UUID.randomUUID(), "tax", BigDecimal.TEN);

    XConomyLegacyTaxAccountsTest() {
        when(server.getPluginCommand("xconomy:xconomy")).thenReturn(command);
        when(command.getPlugin()).thenReturn(provider);
        when(server.getConsoleSender()).thenReturn(console);
    }

    @Test void usesProviderCommandAndRejectsChangedIdentityBeforeDeletion() {
        try (var construction = mockConstruction(XConomyAccountRenamer.class)) {
            var adapter = new XConomyLegacyTaxAccounts(server, provider);
            var reader = construction.constructed().getFirst();
            when(reader.byName("tax")).thenReturn(account);
            when(command.execute(eq(console), eq("xconomy"), aryEq(new String[]{"deldata", "tax"})))
                    .thenReturn(true);
            adapter.delete(account);
            verify(reader).prepare();
            verify(command).execute(eq(console), eq("xconomy"), aryEq(new String[]{"deldata", "tax"}));
            clearInvocations(command);
            when(reader.byName("tax")).thenReturn(new Account(UUID.randomUUID(), "tax", BigDecimal.TEN));
            assertThrows(IllegalStateException.class, () -> adapter.delete(account));
            verify(command, never()).execute(any(), anyString(), any());
        }
    }

    @Test void refusesCommandOwnedByAnotherPlugin() {
        when(command.getPlugin()).thenReturn(mock(Plugin.class));
        try (var construction = mockConstruction(XConomyAccountRenamer.class)) {
            assertThrows(IllegalStateException.class, () -> new XConomyLegacyTaxAccounts(server, provider));
            verify(command, never()).execute(any(), anyString(), any());
        }
    }

}
