package org.allivlisey.tianjitown.integrations.vault;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;
import org.allivlisey.tianjitown.integrations.vault.SettlementAccountMigration.Account;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class XConomyLegacyTaxAccountsTest {
    final Server server = mock(Server.class);
    final XConomyAccountRenamer reader = mock(XConomyAccountRenamer.class);
    final Api api = new Api();
    final Account account = new Account(UUID.randomUUID(), "Tax", BigDecimal.TEN);

    @Test void deletesByUuidWithoutResolvingAmbiguousName() {
        var selected = new Player(account.id(), account.name(), api);
        var other = new Player(UUID.randomUUID(), "tax", api);
        api.players.put(selected.id, selected);
        api.players.put(other.id, other);
        var adapter = new XConomyLegacyTaxAccounts(server, reader, api, Deleter.class);
        adapter.delete(account);
        verify(reader).prepare();
        assertNull(api.players.get(selected.id));
        assertSame(other, api.players.get(other.id));
    }

    @Test void refusesChangedIdentityOrRealPlayer() {
        var adapter = new XConomyLegacyTaxAccounts(server, reader, api, Deleter.class);
        api.players.put(account.id(), new Player(UUID.randomUUID(), "Tax", api));
        assertThrows(IllegalStateException.class, () -> adapter.delete(account));
        api.players.put(account.id(), new Player(account.id(), "someone", api));
        assertThrows(IllegalStateException.class, () -> adapter.delete(account));
        api.players.put(account.id(), new Player(account.id(), "Tax", api));
        when(reader.hasPlayed(account.id())).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> adapter.delete(account));
        assertEquals(1, api.players.size());
    }

    @Test void requiresDeletionCapabilityBeforeAnyMutation() {
        assertThrows(IllegalStateException.class,
                () -> new XConomyLegacyTaxAccounts(server, reader, api, Object.class));
    }

    public static class Api {
        final Map<UUID, Player> players = new HashMap<>();
        public Player getPlayerData(UUID id) { return players.get(id); }
    }
    public static class Player {
        final UUID id;
        final String name;
        final Api api;
        Player(UUID id, String name, Api api) { this.id = id; this.name = name; this.api = api; }
        public UUID getUniqueId() { return id; }
        public String getName() { return name; }
    }
    public static class Deleter {
        public static void deletePlayerData(Player player) { player.api.players.remove(player.id); }
    }
}
