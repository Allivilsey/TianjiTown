package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.storage.town.TownSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Presentation model for the public join-application town page. */
record JoinTownMenuModel(List<String> summaryLore, Entry rulesEntry, Entry applyEntry) {
    private static final String TOWN_CODE_KEY = "dialog.common.town-code";
    private static final String DESCRIPTION_KEY = "dialog.common.town-description";

    JoinTownMenuModel {
        summaryLore = List.copyOf(Objects.requireNonNull(summaryLore, "summaryLore"));
        rulesEntry = Objects.requireNonNull(rulesEntry, "rulesEntry");
        applyEntry = Objects.requireNonNull(applyEntry, "applyEntry");
    }

    static JoinTownMenuModel create(PluginMessages messages, TownSnapshot town) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(town, "town");
        String target = town.id().toString();
        return new JoinTownMenuModel(List.of(
                messages.rawText(TOWN_CODE_KEY, Map.of(
                        "code", safeText(town.profile().residenceName()))),
                messages.rawText(DESCRIPTION_KEY, Map.of(
                        "description", safeText(town.profile().description())))),
                new Entry(10, "town.rules", "tooltip.town.rules", "JOIN_TOWN_RULES", target),
                new Entry(13, "join.apply", List.of("tooltip.join.submit-expiry",
                        "common.join-application-limit"), "CONFIRM_APPLY_JOIN", target));
    }

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    record Entry(int slot, String labelKey, List<String> loreKeys, String action, String target) {
        Entry(int slot, String labelKey, String loreKey, String action, String target) {
            this(slot, labelKey, List.of(loreKey), action, target);
        }

        Entry {
            labelKey = Objects.requireNonNull(labelKey, "labelKey");
            loreKeys = List.copyOf(Objects.requireNonNull(loreKeys, "loreKeys"));
            action = Objects.requireNonNull(action, "action");
            target = Objects.requireNonNull(target, "target");
        }
    }
}
