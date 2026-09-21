package org.allivlisey.tianjitown.integrations.vault;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CmiAccountVisibilityTest {
    @Test void preservesExistingExclusionsPersistsExactNameAndRebuildsOnlyWhenChanged() {
        Cmi cmi = new Cmi();
        CmiAccountVisibility.hide(cmi, "TianjiTown-Tax");
        assertEquals(List.of("existing-player", "tianjitown-tax"), cmi.manager.exclusions);
        assertEquals(cmi.manager.exclusions, cmi.config.saved);
        assertEquals(1, cmi.manager.refreshes);
        CmiAccountVisibility.hide(cmi, "TianjiTown-Tax");
        assertEquals(1, cmi.manager.refreshes);
        assertEquals(1, cmi.config.saves);
    }

    @Test void brokenOptionalIntegrationReportsFailure() {
        assertThrows(IllegalStateException.class, () -> CmiAccountVisibility.hide(new Object(), "tax"));
    }

    public static class Cmi {
        final Manager manager = new Manager();
        final Config config = new Config();
        public Manager getEconomyManager() { return manager; }
        public Config getConfigManager() { return config; }
    }
    public static class Manager {
        List<String> exclusions = List.of("existing-player");
        int refreshes;
        public List<String> getBalTopExclude() { return exclusions; }
        public void setBalTopExclude(List<String> value) { exclusions = value; }
        public void setForBalTopRecalculation() { refreshes++; }
    }
    public static class Config {
        Object saved;
        int saves;
        public void ChangeConfig(String path, Object value, boolean reload) {
            assertEquals("Economy.BalTop.Exclude", path);
            assertFalse(reload, "Do not reload unrelated CMI configuration");
            saved = value;
            saves++;
        }
    }
}
