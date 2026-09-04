package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.storage.town.TownSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Presentation model for the public town details menu. */
record TownDetailsMenuModel(List<String> summaryLore, RulesEntry rulesEntry) {
    private static final String RESIDENCE_NAME_KEY = "dialog.common.residence-name";
    private static final String DESCRIPTION_KEY = "dialog.common.town-description";

    TownDetailsMenuModel {
        summaryLore = List.copyOf(Objects.requireNonNull(summaryLore, "summaryLore"));
        rulesEntry = Objects.requireNonNull(rulesEntry, "rulesEntry");
    }

    static TownDetailsMenuModel create(PluginMessages messages, TownSnapshot town) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(town, "town");

        return new TownDetailsMenuModel(List.of(
                messages.rawText(RESIDENCE_NAME_KEY, Map.of(
                        "name", safeText(town.residenceName()))),
                messages.rawText(DESCRIPTION_KEY, Map.of(
                        "description", safeText(town.profile().description())))),
                new RulesEntry(10, "town.rules", "tooltip.town.rules", "TOWN_RULES",
                        town.id().toString()));
    }

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    record RulesEntry(int slot, String labelKey, String tooltipKey, String action, String target) {
        RulesEntry {
            labelKey = Objects.requireNonNull(labelKey, "labelKey");
            tooltipKey = Objects.requireNonNull(tooltipKey, "tooltipKey");
            action = Objects.requireNonNull(action, "action");
            target = Objects.requireNonNull(target, "target");
        }
    }
}
