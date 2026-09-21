package org.allivlisey.tianjitown.paper.runtime;

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import java.util.List;
import java.util.UUID;
import org.allivlisey.tianjitown.integrations.vault.VaultSettlementService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SettlementAccountPrivacyTest {
    private final UUID accountId = UUID.randomUUID();
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final VaultSettlementService settlement = mock(VaultSettlementService.class);
    private final SettlementAccountPrivacy privacy;

    SettlementAccountPrivacyTest() {
        when(settlement.accountId()).thenReturn(accountId);
        when(settlement.accountName()).thenReturn("tianjitown-tax");
        privacy = new SettlementAccountPrivacy(plugin, settlement);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/bal tianjitown-tax", "/XConomy:balance TIANJITOWN-TAX",
            "/money tianjitown-tax", "/pay tianjitown-tax 1", "/cmi balance tianjitown-tax",
            "/cmi:cmi money tianjitown-tax", "/cmi money balance tianjitown-tax",
            "/xconomy:ebalance tianjitown-tax", " /bal   tianjitown-tax "})
    void identifiesSupportedDirectAccessIncludingAliasesAndNamespaces(String command) {
        assertTrue(privacy.targetsAccount(command));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/balance player", "/bal tianjitown-taxpayer", "/bal town",
            "/say tianjitown-tax", "/tianjitown money view town", "/cmi", "/baltop", ""})
    void leavesUnrelatedCommandsAndOtherPlayersAlone(String command) {
        assertFalse(privacy.targetsAccount(command));
    }

    @Test void usesConfiguredLegacyNameAndUuidRatherThanHardcodedDefault() {
        when(settlement.accountName()).thenReturn("Tax");
        assertTrue(privacy.targetsAccount("/bal tax"));
        assertTrue(privacy.targetsAccount("/bal " + accountId));
        assertFalse(privacy.targetsAccount("/bal tianjitown-tax"));
    }

    @Test void rejectsPlayerQueryButPreservesAdministratorAccess() {
        Player player = mock(Player.class);
        PluginMessages messages = mock(PluginMessages.class);
        String denied = "不可查询";
        when(plugin.messages()).thenReturn(messages);
        when(messages.text("chat.finance.account-unavailable")).thenReturn(denied);
        var event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn("/bal tianjitown-tax");
        privacy.onCommand(event);
        verify(event).setCancelled(true);
        verify(player).sendMessage(denied);
        when(player.hasPermission("tianjitown.admin")).thenReturn(true);
        var adminEvent = mock(PlayerCommandPreprocessEvent.class);
        when(adminEvent.getPlayer()).thenReturn(player);
        privacy.onCommand(adminEvent);
        verify(adminEvent, never()).setCancelled(anyBoolean());
        verify(settlement, never()).balanceMinor();
        verify(settlement, never()).adjustSettlement(anyLong());
    }

    @Test void migratedAccountAlsoHidesItsOldNameFromPlayerQueries() {
        var migrated = new SettlementAccountPrivacy(plugin, settlement, "Tax");
        assertTrue(migrated.targetsAccount("/bal tax"));
        assertTrue(migrated.targetsAccount("/cmi balance Tax"));
        assertTrue(migrated.targetsAccount("/bal tianjitown-tax"));
        assertFalse(migrated.targetsAccount("/bal taxpayer"));
    }

    @Test void preventsLoginWithTheBankUuidWithoutBlockingOtherPlayers() {
        var messages = mock(PluginMessages.class);
        when(plugin.messages()).thenReturn(messages);
        when(messages.text("chat.finance.account-unavailable")).thenReturn("不可用");
        var bankLogin = mock(AsyncPlayerPreLoginEvent.class);
        when(bankLogin.getUniqueId()).thenReturn(accountId);
        privacy.onLogin(bankLogin);
        verify(bankLogin).disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "不可用");
        var playerLogin = mock(AsyncPlayerPreLoginEvent.class);
        when(playerLogin.getUniqueId()).thenReturn(UUID.randomUUID());
        privacy.onLogin(playerLogin);
        verify(playerLogin, never()).disallow(any(AsyncPlayerPreLoginEvent.Result.class), anyString());
    }

    @Test void filtersFinalSuggestionsWithoutAccessingPlayerPermissionsOnAsyncThread() {
        Player player = mock(Player.class);
        StringRange range = StringRange.between(5, 8);
        Suggestion ordinary = new Suggestion(range, "tianjitown-taxpayer");
        var original = new Suggestions(range, List.of(
                new Suggestion(range, "TianjiTown-Tax"), new Suggestion(range, accountId.toString()),
                ordinary));
        var event = mock(AsyncPlayerSendSuggestionsEvent.class);
        when(event.getSuggestions()).thenReturn(original);
        privacy.onSuggestions(event);
        var sent = ArgumentCaptor.forClass(Suggestions.class);
        verify(event).setSuggestions(sent.capture());
        assertEquals(List.of(ordinary), sent.getValue().getList());
        assertEquals(range, sent.getValue().getRange());
        verifyNoInteractions(player, plugin);
    }

}
