package cn.tianji.town.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
        validateRequiredMessages();
    }

    private void validateRequiredMessages() {
        for (String key : java.util.List.of(
                "chat.notification.vote-created", "chat.buttons.view-votes",
                "dialog.votes.type-kick", "dialog.votes.type-replace-mayor",
                "dialog.votes.governance-title", "dialog.votes.main-pending",
                "dialog.votes.governance-pending-votes", "dialog.votes.visitor-member-scope",
                "dialog.votes.pending-summary", "dialog.votes.pending-empty-hint",
                "dialog.votes.pending-title", "dialog.votes.action-kick-member",
                "dialog.votes.action-replace-mayor",
                "dialog.votes.status-open", "dialog.votes.status-passed",
                "dialog.votes.status-rejected", "dialog.votes.status-cancelled",
                "dialog.votes.approve", "dialog.votes.reject", "dialog.votes.cancel",
                "dialog.votes.target", "dialog.votes.threshold", "dialog.votes.tally",
                "dialog.votes.expires", "dialog.votes.already-voted",
                "dialog.votes.ineligible",
                "dialog.votes.pending", "dialog.votes.previous", "dialog.votes.next",
                "dialog.votes.back-governance", "dialog.votes.back-list",
                "dialog.votes.detail-type",
                "dialog.votes.once", "dialog.votes.cancel-only",
                "dialog.votes.cancel-irreversible", "dialog.votes.status-line",
                "dialog.votes.empty", "dialog.votes.empty-hint",
                "dialog.votes.list-title", "dialog.votes.detail-title",
                "dialog.tooltip.votes.entry.approve-count",
                "dialog.tooltip.votes.entry.oppose-count",
                "dialog.tooltip.votes.entry.deadline")) {
            String value = configuration.getString(key);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("缺少必需的投票消息配置: " + key);
            }
        }
        for (String key : java.util.List.of(
                "dialog.tax.rate-format",
                "dialog.buff.duration-format",
                "dialog.buff.intensity-format")) {
            validateRangeFormat(key);
        }
    }

    private void validateRangeFormat(String key) {
        String format = configuration.getString(key);
        if (format == null || format.isBlank()) {
            throw new IllegalStateException("缺少范围输入格式配置: " + key);
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
                throw new IllegalStateException(key + " 只支持 %s 占位符，不支持浮点格式");
            }
            placeholders++;
            index++;
        }
        if (placeholders != 2) {
            throw new IllegalStateException(key + " 必须恰好包含两个未转义的 %s 占位符");
        }
    }

    String text(String key) {
        return text(key, Map.of());
    }

    String text(String key, Map<String, ?> placeholders) {
        // 玩家消息和 Dialog 都在这里统一把 & 颜色码转换为 Minecraft legacy 颜色码。
        return rawText(key, placeholders)
                .replace('&', '§');
    }

    String rawText(String key) {
        return rawText(key, Map.of());
    }

    String rawText(String key, Map<String, ?> placeholders) {
        // Dialog 菜单需要在应用颜色表前保留配置中的 & 代码，因此提供未转换的读取入口。
        return resolve(key, placeholders, "&c缺少消息配置: " + key);
    }

    Component component(String key) {
        return component(key, Map.of());
    }

    Component component(String key, Map<String, ?> placeholders) {
        // Dialog 与聊天消息使用同一套 & 颜色码；这里转换为 Adventure 组件供 Paper 渲染。
        return LegacyComponentSerializer.legacySection().deserialize(text(key, placeholders));
    }

    String plainText(String key) {
        return plainText(key, Map.of());
    }

    String plainText(String key, Map<String, ?> placeholders) {
        // 兼容仍需要纯文本的调用方；颜色码不会作为可见字符返回。
        return PlainTextComponentSerializer.plainText().serialize(component(key, placeholders));
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
