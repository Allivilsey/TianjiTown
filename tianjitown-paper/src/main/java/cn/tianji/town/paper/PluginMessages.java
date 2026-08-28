package cn.tianji.town.paper;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
        // 先读取玩家配置，再挂载 JAR 内默认值；这样升级时无需覆盖玩家已有的自定义文案。
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        InputStream resource = PluginMessages.class.getResourceAsStream("/messages.yml");
        if (resource != null) {
            try (InputStream input = resource;
                 InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(reader));
            } catch (IOException exception) {
                throw new IllegalStateException("读取内置 messages.yml 失败", exception);
            }
        }
        configuration = loaded;
    }

    String text(String key) {
        return text(key, Map.of());
    }

    String text(String key, Map<String, ?> placeholders) {
        return resolve(key, placeholders, "&c缺少消息配置: " + key)
                .replace('&', '§');
    }

    String plainText(String key) {
        return plainText(key, Map.of());
    }

    String plainText(String key, Map<String, ?> placeholders) {
        // Dialog 的颜色由调用方控制，避免把配置中的颜色码当作可见字符显示。
        return resolve(key, placeholders, "缺少消息配置: " + key);
    }

    private String resolve(String key, Map<String, ?> placeholders, String fallback) {
        // 聊天消息和 Dialog 共用占位符替换逻辑，保证重载后的文本行为一致。
        String message = Objects.requireNonNullElse(configuration.getString(key), fallback);
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}",
                    String.valueOf(entry.getValue()));
        }
        return message;
    }

    void send(CommandSender recipient, String key) {
        recipient.sendMessage(text(key));
    }

    void send(CommandSender recipient, String key, Map<String, ?> placeholders) {
        recipient.sendMessage(text(key, placeholders));
    }
}
