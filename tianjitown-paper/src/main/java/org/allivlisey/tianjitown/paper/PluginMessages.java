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
import java.util.List;
import java.util.Objects;

final class PluginMessages {
    private static final String BOOTSTRAP_RESOURCE_MISSING =
            "TT-MESSAGES-BOOTSTRAP-RESOURCE-MISSING";
    private static final String BOOTSTRAP_RESOURCE_READ_FAILURE =
            "TT-MESSAGES-BOOTSTRAP-RESOURCE-READ-FAILED";
    private static final String BOOTSTRAP_MISSING_MESSAGE =
            "TT-MESSAGES-MISSING-KEY: {key}";
    /** Canonical key -> pre-merge keys accepted from existing user messages.yml files. */
    private static final Map<String, List<String>> LEGACY_ALIASES = Map.ofEntries(
            Map.entry("dialog.common.previous", List.of("dialog.votes.previous",
                    "dialog.town-members.previous", "dialog.visitor.previous",
                    "dialog.join.previous", "dialog.town-join.previous",
                    "dialog.admin.previous", "dialog.ledger.previous")),
            Map.entry("dialog.common.next", List.of("dialog.votes.next",
                    "dialog.town-members.next", "dialog.visitor.next", "dialog.join.next",
                    "dialog.town-join.next", "dialog.admin.next", "dialog.ledger.next")),
            Map.entry("dialog.common.expires", List.of("dialog.votes.expires",
                    "dialog.votes.entry.deadline", "dialog.tooltip.votes.expires",
                    "dialog.tooltip.votes.entry.deadline", "dialog.tooltip.my-join.expires",
                    "dialog.tooltip.town-join.entry-expires")),
            Map.entry("dialog.votes.type-kick", List.of("dialog.tooltip.votes.type-kick")),
            Map.entry("dialog.votes.type-replace-mayor", List.of(
                    "dialog.tooltip.votes.type-replace-mayor")),
            Map.entry("dialog.votes.status-open", List.of("dialog.tooltip.votes.status-open")),
            Map.entry("dialog.votes.status-passed", List.of("dialog.tooltip.votes.status-passed")),
            Map.entry("dialog.votes.status-rejected", List.of("dialog.tooltip.votes.status-rejected")),
            Map.entry("dialog.votes.status-cancelled", List.of("dialog.tooltip.votes.status-cancelled")),
            Map.entry("dialog.votes.approve", List.of("dialog.tooltip.votes.approve")),
            Map.entry("dialog.votes.reject", List.of("dialog.tooltip.votes.reject")),
            Map.entry("dialog.votes.cancel", List.of("dialog.tooltip.votes.cancel")),
            Map.entry("dialog.votes.target", List.of("dialog.tooltip.votes.target")),
            Map.entry("dialog.votes.voters", List.of("dialog.tooltip.votes.voters")),
            Map.entry("dialog.votes.threshold", List.of("dialog.tooltip.votes.threshold")),
            Map.entry("dialog.votes.tally", List.of("dialog.tooltip.votes.tally")),
            Map.entry("dialog.votes.already-voted", List.of("dialog.tooltip.votes.already-voted")),
            Map.entry("dialog.votes.ineligible", List.of("dialog.tooltip.votes.ineligible")),
            Map.entry("dialog.votes.entry.approve-count", List.of(
                    "dialog.tooltip.votes.entry.approve-count")),
            Map.entry("dialog.votes.entry.oppose-count", List.of(
                    "dialog.tooltip.votes.entry.oppose-count")),
            Map.entry("dialog.votes.once", List.of("dialog.tooltip.votes.once")),
            Map.entry("dialog.votes.cancel-only", List.of("dialog.tooltip.votes.cancel-only")),
            Map.entry("dialog.votes.cancel-irreversible", List.of(
                    "dialog.tooltip.votes.cancel-irreversible")),
            Map.entry("validation.common.value-required", List.of("validation.buff.value-required",
                    "validation.configuration.value-required",
                    "validation.economy.settlement-account-required",
                    "validation.bonus.backup-directory-required")),
            Map.entry("validation.common.range", List.of("validation.runtime-configuration.range",
                    "validation.bonus.building-refund-weekly-limit-range",
                    "validation.bonus.building-refund-retention-range",
                    "validation.bonus.beacon-refresh-interval-range")),
            Map.entry("validation.common.integer-type", List.of(
                    "validation.configuration.integer-type")),
            Map.entry("validation.common.non-negative", List.of(
                    "validation.economy.weekly-subsidy-limit-negative",
                    "validation.economy.twelve-hour-subsidy-limit-negative")),
            Map.entry("chat.station.invalid-data", List.of("chat.station.invalid-copy",
                    "chat.station.invalid-interaction")),
            Map.entry("dialog.common.application-member-select", List.of(
                    "dialog.tooltip.application-members.select",
                    "dialog.application.member-select-hint")),
            Map.entry("dialog.common.application-member-save", List.of(
                    "dialog.tooltip.application-members.save", "dialog.application.save-hint")),
            Map.entry("dialog.common.town", List.of("dialog.transfer.town", "dialog.finance.town",
                    "dialog.rules.town", "dialog.review.town", "dialog.donation.town")),
            Map.entry("dialog.common.town-description", List.of("dialog.town.description",
                    "dialog.join.town-description", "dialog.admin.description",
                    "dialog.tooltip.join.town-description", "dialog.application.description")),
            Map.entry("dialog.common.town-code", List.of("dialog.join.town-code",
                    "dialog.tooltip.join.town-code", "dialog.tooltip.admin.entry-code")),
            Map.entry("dialog.common.admin-review-message", List.of("dialog.main.application-review",
                    "dialog.tooltip.main.application-review", "dialog.application.review-message")),
            Map.entry("dialog.common.name", List.of("dialog.admin.name", "dialog.application.name")),
            Map.entry("dialog.common.residence-name", List.of("dialog.admin.residence-name",
                    "dialog.application.residence-name")),
            Map.entry("dialog.common.applicant", List.of("dialog.admin.applicant",
                    "dialog.application.applicant")),
            Map.entry("dialog.common.application-status", List.of("dialog.admin.status-line",
                    "dialog.application.status-line")),
            Map.entry("dialog.common.rules", List.of("dialog.join.town-rules", "dialog.admin.rules")),
            Map.entry("dialog.common.error", List.of("dialog.review.error", "dialog.donation.error")),
            Map.entry("dialog.common.finance-title", List.of("dialog.main.finance",
                    "dialog.finance.title", "dialog.finance.summary-title")),
            Map.entry("dialog.common.town-name", List.of("dialog.main.town-summary",
                    "dialog.town.summary-title", "dialog.join.town-name")),
            Map.entry("dialog.common.rules-title", List.of("dialog.rules.title",
                    "dialog.application.rules-label")),
            Map.entry("dialog.common.draft-saved-title", List.of("dialog.notice.form-draft-saved-title",
                    "dialog.notice.draft-saved-title")),
            Map.entry("dialog.common.handbook", List.of("dialog.main.handbook",
                    "dialog.personal.handbook")),
            Map.entry("dialog.common.confirmation-required", List.of(
                    "dialog.tooltip.member-detail.kick-confirm",
                    "dialog.tooltip.transfer.accept-confirm")),
            Map.entry("dialog.common.preview-site", List.of("dialog.tooltip.application.preview-site",
                    "dialog.tooltip.admin.preview")),
            Map.entry("dialog.common.join-application-limit", List.of(
                    "dialog.tooltip.main.my-applications-limit", "dialog.tooltip.join.submit-limit")),
            Map.entry("dialog.common.irreversible", List.of(
                    "dialog.tooltip.personal.disband-irreversible",
                    "dialog.confirmation.irreversible")),
            Map.entry("dialog.common.unknown-player", List.of("dialog.ledger.unknown-player")),
            Map.entry("dialog.common.territory-units", List.of("dialog.main.territory-summary",
                    "dialog.finance.territory-units")),
            Map.entry("dialog.common.applications-count", List.of(
                    "dialog.governance.applications-count", "dialog.pending.applications-count")),
            Map.entry("dialog.common.reject", List.of("dialog.transfer.reject", "dialog.admin.reject")),
            Map.entry("dialog.common.town-entry-title", List.of("dialog.my-join.entry-title",
                    "dialog.admin.entry-title")),
            Map.entry("validation.donation.refund-operation", List.of(
                    "validation.donation.compensation-operation")),
            Map.entry("diagnostic.donation.refund-call-failure", List.of(
                    "diagnostic.donation.compensation-call-failure")),
            Map.entry("log.donation.refund-resolved", List.of(
                    "log.donation.compensation-resolved")),
            Map.entry("log.donation.refund-retry-failed", List.of(
                    "log.donation.compensation-retry-failed")),
            Map.entry("log.donation.refund-finalization-failed", List.of(
                    "log.donation.compensation-finalization-failed")),
            Map.entry("log.donation.refund-recovered", List.of(
                    "log.donation.compensation-recovered")),
            Map.entry("log.donation.refund-exhausted", List.of(
                    "log.donation.compensation-exhausted")),
            Map.entry("chat.lifecycle.refund-auto", List.of(
                    "chat.lifecycle.compensation-auto")),
            Map.entry("chat.lifecycle.manual-review", List.of(
                    "chat.lifecycle.compensation-manual")),
            Map.entry("log.external-operation.refund-auto", List.of(
                    "log.external-operation.compensation-auto")),
            Map.entry("log.external-operation.manual-review", List.of(
                    "log.external-operation.compensation-manual")),
            Map.entry("diagnostic.vault.settlement.player-debit-refunded", List.of(
                    "diagnostic.vault.settlement.player-debit-compensated")),
            Map.entry("diagnostic.vault.settlement.player-refund-failure", List.of(
                    "diagnostic.vault.settlement.player-compensation-failure")),
            Map.entry("log.vault.settlement.player-refund-ambiguous", List.of(
                    "log.vault.settlement.player-compensation-ambiguous")));
    private static final Map<String, String> LEGACY_TO_CANONICAL = legacyToCanonical();
    private final File file;
    private volatile YamlConfiguration configuration;

    PluginMessages(File dataFolder) {
        this.file = new File(Objects.requireNonNull(dataFolder, "dataFolder"), "messages.yml");
        reload();
    }

    void reload() {
        // 先读取玩家配置，再挂载 JAR 内默认值；这样升级时无需覆盖玩家已有的自定义文案。
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        migrateLegacyMessages(loaded);
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

    private static void migrateLegacyMessages(YamlConfiguration loaded) {
        for (Map.Entry<String, List<String>> entry : LEGACY_ALIASES.entrySet()) {
            if (loaded.isSet(entry.getKey())) {
                continue;
            }
            for (String legacyKey : entry.getValue()) {
                String value = loaded.getString(legacyKey);
                if (value != null && !value.isBlank()) {
                    loaded.set(entry.getKey(), value);
                    break;
                }
            }
        }
    }

    private static Map<String, String> legacyToCanonical() {
        Map<String, String> aliases = new java.util.HashMap<>();
        LEGACY_ALIASES.forEach((canonical, legacyKeys) -> legacyKeys.forEach(
                legacy -> aliases.put(legacy, canonical)));
        return Map.copyOf(aliases);
    }

    private void validateRequiredMessages() {
        for (String key : java.util.List.of(
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
                "log.donation.refund-retry-failed",
                "log.donation.refund-finalization-failed",
                "log.donation.refund-recovered",
                "log.donation.refund-exhausted",
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
                "dialog.votes.target", "dialog.votes.voters", "dialog.votes.threshold",
                "dialog.votes.tally", "dialog.common.expires", "dialog.votes.already-voted",
                "dialog.votes.ineligible",
                "dialog.votes.pending", "dialog.common.previous", "dialog.common.next",
                "dialog.votes.back-governance", "dialog.votes.back-list",
                "dialog.votes.detail-type",
                "dialog.votes.once", "dialog.votes.cancel-only",
                "dialog.votes.cancel-irreversible", "dialog.votes.status-line",
                "dialog.votes.empty", "dialog.votes.empty-hint",
                "dialog.votes.list-title", "dialog.votes.detail-title",
                "dialog.votes.entry.approve-count", "dialog.votes.entry.oppose-count")) {
            String value = configuration.getString(canonicalKey(key));
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
        String value = configuration.getString(canonicalKey(key));
        return value != null && !value.isBlank();
    }

    String requiredPlainText(String key) {
        requireMessage(key);
        return plainText(key);
    }

    private void requireMessage(String key) {
        String value = configuration.getString(canonicalKey(key));
        if (value == null || value.isBlank()) {
            throw configurationFailure("diagnostic.messages.required-message-missing",
                    Map.of("key", key));
        }
    }

    private void validateRangeFormat(String key) {
        String format = configuration.getString(canonicalKey(key));
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
        String message = configuration.getString(canonicalKey(key));
        if (message == null || message.isBlank()) {
            message = fallback;
        }
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}",
                    String.valueOf(entry.getValue()));
        }
        return message;
    }

    private static String canonicalKey(String key) {
        return LEGACY_TO_CANONICAL.getOrDefault(key, key);
    }

    void send(CommandSender recipient, String key) {
        recipient.sendMessage(text(key));
    }

    void send(CommandSender recipient, String key, Map<String, ?> placeholders) {
        recipient.sendMessage(text(key, placeholders));
    }
}
