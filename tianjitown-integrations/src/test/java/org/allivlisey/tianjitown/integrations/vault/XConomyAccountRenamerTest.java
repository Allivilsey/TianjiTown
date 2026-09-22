package org.allivlisey.tianjitown.integrations.vault;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class XConomyAccountRenamerTest {
    enum Mode { DEFAULT }

    @Test void renamesThroughPublicApiDespiteOneStaleRead() {
        var api = new Api();
        var server = mock(Server.class);
        when(server.getOnlinePlayers()).thenReturn(List.of());
        var adapter = new XConomyAccountRenamer(server, api, Mode.DEFAULT);
        var before = adapter.byName("tax");
        adapter.rename(before.id(), "tianjitown-tax");
        var after = adapter.byId(before.id());
        assertEquals(before.id(), after.id());
        assertEquals(before.balance(), after.balance());
        assertEquals("tianjitown-tax", after.name());
        assertEquals(1, api.renames);
    }

    @Test void rejectsFailedRenameAndChangedBalance() {
        var server = mock(Server.class);
        when(server.getOnlinePlayers()).thenReturn(List.of());
        var api = new Api();
        var adapter = new XConomyAccountRenamer(server, api, Mode.DEFAULT);
        api.reject = true;
        assertThrows(IllegalStateException.class, () -> adapter.rename(api.current.id, "tianjitown-tax"));
        api.reject = false;
        api.changeBalance = true;
        assertThrows(IllegalStateException.class, () -> adapter.rename(api.current.id, "tianjitown-tax"));
    }

    // Public API fixture intentionally exposes no SQL, DataCon or DataLink internals.
    public static class Api {
        Player current = new Player(UUID.randomUUID(), "tax", new BigDecimal("15000.00"));
        Player stale;
        boolean reject, changeBalance;
        int renames;
        public Player getPlayerData(UUID id) {
            if (stale != null) { var value = stale; stale = null; return value; }
            return current.id.equals(id) ? current : null;
        }
        public Player getPlayerData(String name) { return current.name.equals(name) ? current : null; }
        public boolean createPlayerData(UUID id, String name) {
            if (reject) return false;
            renames++;
            stale = current;
            current = new Player(id, name, changeBalance ? BigDecimal.ZERO : current.balance);
            return true;
        }
    }

    public static class Player {
        final UUID id;
        final String name;
        final BigDecimal balance;
        Player(UUID id, String name, BigDecimal balance) { this.id = id; this.name = name; this.balance = balance; }
        public UUID getUniqueId() { return id; }
        public String getName() { return name; }
        public BigDecimal getBalance() { return balance; }
    }
}
