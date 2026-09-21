package org.allivlisey.tianjitown.integrations.vault;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XConomyAccountVisibilityTest {
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();

    @BeforeEach void reset() {
        Provider.balances.clear();
        Provider.storedHidden.clear();
        Provider.cachedHidden.clear();
        Provider.names.clear();
        Provider.hiddenNames.clear();
        Provider.suggestions.clear();
        Provider.balances.put(ACCOUNT, new BigDecimal("123456.78"));
        Provider.balances.put(PLAYER, new BigDecimal("42.50"));
        Provider.names.put(ACCOUNT, "Tax");
        Provider.names.put(PLAYER, "Player");
        Provider.suggestions.addAll(List.of("Tax", "Player"));
        Provider.failWrite = false;
        Provider.writes = 0;
        Provider.baltop();
    }

    @Test void hidesExistingAccountFromRankingAndTotalWithoutChangingBalances() {
        var before = Map.copyOf(Provider.balances);
        privacy().hide();
        assertEquals(List.of(PLAYER), Provider.ranking);
        assertEquals(new BigDecimal("42.50"), Provider.total);
        assertEquals(List.of("Player"), Provider.suggestions);
        assertTrue(Provider.hiddenNames.contains("Tax"));
        assertEquals(before, Provider.balances);
        // Simulate a restart: the stored flag must survive losing every hidden-state cache.
        Provider.cachedHidden.clear();
        assertEquals(1, Provider.getPlayerHiddenState(ACCOUNT));
        privacy().hide();
        assertEquals(before, Provider.balances);
        assertEquals(List.of(PLAYER), Provider.ranking);
    }

    @Test void repairsAnAccountDisplayedAgainButDoesNotRepeatedlyWriteHealthyState() {
        var privacy = privacy();
        privacy.hide();
        privacy.hide();
        assertEquals(1, Provider.writes);
        Provider.storedHidden.put(ACCOUNT, 0);
        Provider.cachedHidden.clear();
        privacy.hide();
        assertEquals(2, Provider.writes);
        assertEquals(1, Provider.storedHidden.get(ACCOUNT));
    }

    @Test void detectsProviderSwallowingSqlFailureWithoutChangingBalances() {
        Provider.failWrite = true;
        assertThrows(IllegalStateException.class, () -> privacy().hide());
        assertEquals(0, Provider.getPlayerHiddenState(ACCOUNT));
        assertEquals(new BigDecimal("123456.78"), Provider.balances.get(ACCOUNT));
    }

    @Test void missingAccountIsNotMistakenForSuccessfullyHiddenAccount() {
        Provider.balances.remove(ACCOUNT);
        assertEquals(1, Provider.getPlayerHiddenState(ACCOUNT));
        assertThrows(IllegalStateException.class, () -> privacy().hide());
        assertEquals(0, Provider.writes);
    }

    @Test void incompatibleProviderIsRejectedBeforeAnyMutation() {
        assertThrows(IllegalStateException.class, () -> new XConomyAccountVisibility(
                ACCOUNT, "Tax", Object.class, Provider.class, Provider.class, Provider.class));
        assertEquals(0, Provider.writes);
    }

    private XConomyAccountVisibility privacy() {
        return new XConomyAccountVisibility(ACCOUNT, "Tax", Provider.class, Provider.class,
                Provider.class, Provider.class);
    }

    // Contract double: independent persistent state, provider cache, rankings and suggestions.
    public static class Provider {
        static final Map<UUID, BigDecimal> balances = new HashMap<>();
        static final Map<UUID, String> names = new HashMap<>();
        static final Map<UUID, Integer> storedHidden = new HashMap<>(), cachedHidden = new HashMap<>();
        static final List<String> hiddenNames = new ArrayList<>(), suggestions = new ArrayList<>();
        static List<UUID> ranking;
        static BigDecimal total;
        static boolean failWrite;
        static int writes;
        public static Object getPlayerData(UUID id) { return balances.get(id); }
        public static int getPlayerHiddenState(UUID id) {
            if (!balances.containsKey(id)) return 1;
            return cachedHidden.computeIfAbsent(id, key -> storedHidden.getOrDefault(key, 0));
        }
        public static void setTopBalHide(UUID id, int hidden) {
            writes++;
            if (failWrite) return;
            storedHidden.put(id, hidden);
            cachedHidden.put(id, hidden);
        }
        public static void baltop() {
            ranking = balances.keySet().stream()
                    .filter(id -> storedHidden.getOrDefault(id, 0) != 1).toList();
            total = ranking.stream().map(balances::get).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        public static void addHidden(String name) { hiddenNames.add(name); }
        public static void remove_Tab_PlayerList(String name) { suggestions.remove(name); }
    }
}
