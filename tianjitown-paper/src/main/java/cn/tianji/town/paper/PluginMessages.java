package cn.tianji.town.paper;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Map;
import java.util.Objects;

final class PluginMessages {
    private final File file;
    private volatile YamlConfiguration configuration;

    PluginMessages(File dataFolder) {
        this.file = new File(Objects.requireNonNull(dataFolder, "dataFolder"), "messages.yml");
        reload();
    }

    void reload() {
        configuration = YamlConfiguration.loadConfiguration(file);
    }

    String text(String key) {
        return text(key, Map.of());
    }

    String text(String key, Map<String, ?> placeholders) {
        String fallback = "&c缺少消息配置: " + key;
        String message = Objects.requireNonNullElse(configuration.getString(key), fallback);
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}",
                    String.valueOf(entry.getValue()));
        }
        return message.replace('&', '§');
    }

    void send(CommandSender recipient, String key) {
        recipient.sendMessage(text(key));
    }

    void send(CommandSender recipient, String key, Map<String, ?> placeholders) {
        recipient.sendMessage(text(key, placeholders));
    }
}
