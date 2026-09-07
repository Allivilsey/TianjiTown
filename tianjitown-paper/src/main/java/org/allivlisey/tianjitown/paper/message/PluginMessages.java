package org.allivlisey.tianjitown.paper.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

public final class PluginMessages {
    private static final String BOOTSTRAP_RESOURCE_MISSING =
            "TT-MESSAGES-BOOTSTRAP-RESOURCE-MISSING";
    private static final String BOOTSTRAP_RESOURCE_READ_FAILURE =
            "TT-MESSAGES-BOOTSTRAP-RESOURCE-READ-FAILED";
    private static final String BOOTSTRAP_MISSING_MESSAGE =
            "TT-MESSAGES-MISSING-KEY: {key}";
    private final File file;
    private volatile YamlConfiguration configuration;

    public PluginMessages(File dataFolder) {
        this.file = new File(Objects.requireNonNull(dataFolder, "dataFolder"), "messages.yml");
        reload();
    }

    public void reload() {
        // 先读取玩家配置，再挂载 JAR 内默认值；缺省项使用默认值，保留自定义文案。
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        InputStream resource = PluginMessages.class.getResourceAsStream("/messages.yml");
        if (resource == null) {
            throw new IllegalStateException(BOOTSTRAP_RESOURCE_MISSING);
        }
        try (InputStream input = resource;
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.load(reader);
            loaded.setDefaults(defaults);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException(BOOTSTRAP_RESOURCE_READ_FAILURE, exception);
        }
        configuration = loaded;
        validateRequiredMessages();
    }

    private void validateRequiredMessages() {
        for (MessageContract.Entry entry : MessageContract.requiredEntries()) {
            String template = configuration.getString(entry.key());
            if (template == null || template.isBlank()) {
                throw configurationFailure("diagnostic.messages.required-message-missing",
                        Map.of("key", entry.key()));
            }
            if (entry.format() == MessageContract.Format.RANGE) {
                validateRangeFormat(entry.key());
                continue;
            }
            if (entry.placeholderContract()
                    && !MessageContract.placeholders(template).equals(entry.placeholders())) {
                throw configurationFailure("diagnostic.messages.placeholder-contract-mismatch",
                        Map.of("key", entry.key()));
            }
        }
    }

    public boolean hasMessage(String key) {
        String value = configuration.getString(key);
        return value != null && !value.isBlank();
    }

    public String requiredPlainText(String key) {
        requireMessage(key);
        return plainText(key);
    }

    private void requireMessage(String key) {
        String value = configuration.getString(key);
        if (value == null || value.isBlank()) {
            throw configurationFailure("diagnostic.messages.required-message-missing",
                    Map.of("key", key));
        }
    }

    private void validateRangeFormat(String key) {
        String format = configuration.getString(key);
        if (format == null || format.isBlank()) {
            throw configurationFailure("diagnostic.messages.range-format-missing",
                    Map.of("key", key));
        }
        int placeholders = 0;
        for (int index = 0; index < format.length(); index++) {
            if (format.charAt(index) != '%') {
                continue;
            }
            if (index + 1 < format.length() && format.charAt(index + 1) == '%') {
                index++;
                continue;
            }
            if (index + 1 >= format.length() || format.charAt(index + 1) != 's') {
                throw configurationFailure("diagnostic.messages.range-format-unsupported",
                        Map.of("key", key));
            }
            placeholders++;
            index++;
        }
        if (placeholders != 2) {
            throw configurationFailure("diagnostic.messages.range-format-placeholder-count",
                    Map.of("key", key));
        }
    }

    public String text(String key) {
        return text(key, Map.of());
    }

    public String text(String key, Map<String, ?> placeholders) {
        // 玩家消息和 Dialog 都在这里统一把 & 颜色码转换为 Minecraft legacy 颜色码。
        return rawText(key, placeholders)
                .replace('&', '§');
    }

    public String rawText(String key) {
        return rawText(key, Map.of());
    }

    public String rawText(String key, Map<String, ?> placeholders) {
        // Dialog 菜单需要在应用颜色表前保留配置中的 & 代码，因此提供未转换的读取入口。
        String missingMessage = resolve("system.missing-message", Map.of("key", key),
                BOOTSTRAP_MISSING_MESSAGE);
        return resolve(key, placeholders, missingMessage);
    }

    public Component component(String key) {
        return component(key, Map.of());
    }

    public Component component(String key, Map<String, ?> placeholders) {
        // Dialog 与聊天消息使用同一套 & 颜色码；这里转换为 Adventure 组件供 Paper 渲染。
        return LegacyComponentSerializer.legacySection().deserialize(text(key, placeholders));
    }

    public String plainText(String key) {
        return plainText(key, Map.of());
    }

    public String plainText(String key, Map<String, ?> placeholders) {
        // 兼容仍需要纯文本的调用方；颜色码不会作为可见字符返回。
        return PlainTextComponentSerializer.plainText().serialize(component(key, placeholders));
    }

    private IllegalStateException configurationFailure(String key, Map<String, ?> placeholders) {
        return new IllegalStateException(plainText(key, placeholders));
    }

    private String resolve(String key, Map<String, ?> placeholders, String fallback) {
        // 聊天消息和 Dialog 共用占位符替换逻辑，保证重载后的文本行为一致。
        String message = configuration.getString(key);
        if (message == null || message.isBlank()) {
            message = fallback;
        }
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}",
                    org.allivlisey.tianjitown.core.time.TownTime.display(entry.getValue()));
        }
        return message;
    }

    public void send(CommandSender recipient, String key) {
        recipient.sendMessage(text(key));
    }

    public void send(CommandSender recipient, String key, Map<String, ?> placeholders) {
        recipient.sendMessage(text(key, placeholders));
    }
}
