package org.allivlisey.tianjitown.paper;

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

final class PluginMessages {
    private static final String BOOTSTRAP_RESOURCE_MISSING =
            "TT-MESSAGES-BOOTSTRAP-RESOURCE-MISSING";
    private static final String BOOTSTRAP_RESOURCE_READ_FAILURE =
            "TT-MESSAGES-BOOTSTRAP-RESOURCE-READ-FAILED";
    private static final String BOOTSTRAP_MISSING_MESSAGE =
            "TT-MESSAGES-MISSING-KEY: {key}";
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
        for (String key : java.util.List.of(
                "plugin.command.townadmin.description",
                "plugin.permission.admin.description",
                "plugin.permission.admin-money.description",
                "plugin.permission.admin-tax.description",
                "plugin.permission.admin-ledger.description",
                "plugin.permission.admin-expand.description",
                "plugin.permission.admin-buff.description",
                "plugin.permission.admin-operations.description",
                "diagnostic.lifecycle.startup-checking",
                "diagnostic.lifecycle.admin-command-missing",
                "diagnostic.lifecycle.config-schema-gate-failed",
                "diagnostic.lifecycle.configuration-validation-passed",
                "diagnostic.lifecycle.configuration-validation-failed",
                "diagnostic.lifecycle.business-config-gate-failed",
                "diagnostic.lifecycle.synchronous-gate-failed",
                "diagnostic.lifecycle.dependency-missing",
                "diagnostic.lifecycle.dependency-disabled",
                "diagnostic.lifecycle.dependency-probe-failure",
                "diagnostic.lifecycle.vault-economy-unavailable",
                "diagnostic.lifecycle.database-gate-failed",
                "diagnostic.lifecycle.database-gate-locked",
                "diagnostic.lifecycle.database-config-invalid",
                "diagnostic.lifecycle.database-config-gate-failed",
                "diagnostic.lifecycle.config-schema-too-new",
                "diagnostic.lifecycle.config-schema-upgrade-required",
                "diagnostic.lifecycle.runtime-activation-failure",
                "diagnostic.lifecycle.runtime-gate-failed",
                "diagnostic.lifecycle.runtime-initialization-failure",
                "diagnostic.lifecycle.world-border-ready",
                "diagnostic.lifecycle.dialog-ui-ready",
                "diagnostic.lifecycle.runtime-features-ready",
                "diagnostic.world-border.api-load-failure",
                "log.scheduler.lifecycle-stopped",
                "log.scheduler.quick-shop-tax-refresh-failure",
                "log.lifecycle.sqlite-recovered",
                "log.lifecycle.sqlite-interrupted",
                "log.lifecycle.interrupted-provision-reason",
                "log.lifecycle.interrupted-provisions-recovered",
                "log.lifecycle.interrupted-provision-recovery-failure",
                "log.donation.compensation-retry-failed",
                "log.donation.compensation-finalization-failed",
                "log.donation.compensation-recovered",
                "log.donation.compensation-exhausted",
                "log.donation.settlement-balance-read-failure",
                "log.donation.settlement-shortfall",
                "log.donation.settlement-reconciliation-failure",
                "log.residence.reconciliation-failure",
                "log.residence.reconciliation-difference",
                "log.residence.reconciliation-sqlite-read-failure",
                "log.residence.automatic-repair-cancelled",
                "log.residence.automatic-repair-delayed",
                "log.residence.automatic-repair-sqlite-read-failure",
                "log.residence.automatic-repair-api-failure",
                "log.residence.automatic-repair-consistent",
                "log.residence.automatic-repair-completed",
                "log.residence.automatic-repair-failed",
                "validation.buff.label-required",
                "dialog.buff.labels.speed",
                "dialog.buff.labels.health",
                "validation.runtime-configuration.database-file-required",
                "validation.runtime-configuration.database-file-path-invalid",
                "validation.runtime-configuration.database-directory-create-failure")) {
            requireMessage(key);
        }
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
                throw configurationFailure("diagnostic.messages.required-vote-missing",
                        Map.of("key", key));
            }
        }
        for (String key : java.util.List.of(
                "dialog.tax.rate-format",
                "dialog.buff.duration-format",
                "dialog.buff.intensity-format")) {
            validateRangeFormat(key);
        }
    }

    boolean hasMessage(String key) {
        String value = configuration.getString(key);
        return value != null && !value.isBlank();
    }

    String requiredPlainText(String key) {
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
        String missingMessage = resolve("system.missing-message", Map.of("key", key),
                BOOTSTRAP_MISSING_MESSAGE);
        return resolve(key, placeholders, missingMessage);
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
