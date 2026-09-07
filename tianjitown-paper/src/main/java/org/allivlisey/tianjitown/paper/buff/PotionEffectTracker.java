package org.allivlisey.tianjitown.paper.buff;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Reconciles only effects whose provenance is recorded, independent of the Bukkit registry. */
final class PotionEffectTracker {
    interface Effects {
        PotionOwnership current(String key);
        boolean apply(String key, PotionOwnership effect);
        void remove(String key);
    }

    static void reconcile(Map<String, PotionOwnership> previous,
                          Map<String, PotionOwnership> desired, Effects effects,
                          Consumer<Map<String, PotionOwnership>> persist) {
        Map<String, PotionOwnership> owned = new HashMap<>(previous);
        for (String key : Set.copyOf(owned.keySet())) {
            if (!desired.containsKey(key)) {
                if (matches(owned.remove(key), effects.current(key))) effects.remove(key);
            }
        }
        persist.accept(owned);
        for (var entry : desired.entrySet()) {
            String key = entry.getKey();
            PotionOwnership wanted = entry.getValue(), current = effects.current(key);
            if (current != null && !matches(owned.get(key), current)
                    && current.amplifier() >= wanted.amplifier()
                    && (current.maximumTicks() == -1 || current.maximumTicks() >= wanted.maximumTicks())) {
                owned.remove(key);
            } else if (effects.apply(key, wanted) && matches(wanted, effects.current(key))) {
                owned.put(key, wanted);
            }
            // Persist each successful mutation so partial application can be safely rolled back.
            persist.accept(owned);
        }
    }

    static boolean matches(PotionOwnership owner, PotionOwnership current) {
        return owner != null && current != null && owner.matches(current.amplifier(),
                current.maximumTicks(), current.ambient(), current.particles(), current.icon());
    }
}
