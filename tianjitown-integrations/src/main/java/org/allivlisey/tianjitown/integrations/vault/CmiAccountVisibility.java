package org.allivlisey.tianjitown.integrations.vault;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** CMI maintains a separate leaderboard even when XConomy provides Vault economy. */
public final class CmiAccountVisibility {
    private CmiAccountVisibility() { }

    public static void hide(Object cmi, String accountName) {
        try {
            Object manager = cmi.getClass().getMethod("getEconomyManager").invoke(cmi);
            Object value = manager.getClass().getMethod("getBalTopExclude").invoke(manager);
            if (!(value instanceof List<?> current)) {
                throw new IllegalStateException("CMI balance exclusion list unavailable");
            }
            String name = accountName.toLowerCase(Locale.ROOT);
            if (current.contains(name)) return;
            List<String> exclusions = new ArrayList<>();
            for (Object entry : current) exclusions.add((String) entry);
            exclusions.add(name);
            Object config = cmi.getClass().getMethod("getConfigManager").invoke(cmi);
            // Persist through CMI so reloads preserve both existing exclusions and this account.
            config.getClass().getMethod("ChangeConfig", String.class, Object.class, boolean.class)
                    .invoke(config, "Economy.BalTop.Exclude", exclusions, false);
            manager.getClass().getMethod("setBalTopExclude", List.class).invoke(manager, exclusions);
            manager.getClass().getMethod("setForBalTopRecalculation").invoke(manager);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("CMI account visibility API unavailable", exception);
        }
    }
}
