package cn.tianji.town.paper;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

enum TownUiMode {
    DIALOG,
    LEGACY;

    static TownUiMode load(ConfigurationSection config) {
        String configured = config.getString("ui.mode", DIALOG.name());
        if (configured == null || configured.isBlank()) {
            return DIALOG;
        }
        try {
            return valueOf(configured.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "ui.mode 仅支持 DIALOG 或 LEGACY，当前值为 " + configured, exception);
        }
    }
}
