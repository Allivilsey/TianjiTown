package org.allivlisey.tianjitown.paper.message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The message API contract.  Text in messages.yml deliberately does not belong here: a
 * server owner may change wording, colours, punctuation, or line breaks without changing
 * the plugin API.  Keys, placeholder names, and specialised format shapes do belong here.
 */
public final class MessageContract {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    enum Format {
        TEXT,
        RANGE
    }

    public record Entry(String key, Set<String> placeholders, Format format,
                 boolean placeholderContract) {
        public Entry {
            placeholders = Set.copyOf(placeholders);
        }
    }

    private static final Map<String, Entry> ENTRIES = entries();

    private MessageContract() {
    }

    public static List<Entry> requiredEntries() {
        return List.copyOf(ENTRIES.values());
    }

    public static Entry entry(String key) {
        return ENTRIES.get(key);
    }

    public static Set<String> placeholders(String template) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return Set.copyOf(names);
    }

    private static Map<String, Entry> entries() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        // Startup and runtime-gate messages are intentionally required: without them a
        // locked server cannot explain why player-facing features are unavailable.
        required(entries, List.of(
                "diagnostic.lifecycle.startup-checking",
                "diagnostic.lifecycle.admin-command-missing",
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
                "diagnostic.lifecycle.startup-diagnostic-passed",
                "diagnostic.lifecycle.startup-diagnostic-failed",
                "diagnostic.lifecycle.startup-diagnostic-gate-failed",
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
                "validation.runtime-configuration.database-directory-create-failure",
                "chat.notification.vote-created", "chat.buttons.view-votes",
                "dialog.votes.type-kick", "dialog.votes.type-replace-mayor",
                "dialog.votes.governance-title", "dialog.votes.main-pending",
                "dialog.votes.governance-pending-votes", "dialog.votes.visitor-member-scope",
                "dialog.votes.pending-summary", "dialog.votes.pending-empty-hint",
                "dialog.votes.pending-title", "dialog.votes.action-kick-member",
                "dialog.votes.action-replace-mayor", "dialog.votes.status-open",
                "dialog.votes.status-passed", "dialog.votes.status-rejected",
                "dialog.votes.status-cancelled", "dialog.votes.approve", "dialog.votes.reject",
                "dialog.votes.cancel", "dialog.votes.target", "dialog.votes.voters",
                "dialog.votes.threshold", "dialog.votes.tally", "dialog.common.expires",
                "dialog.votes.already-voted", "dialog.votes.ineligible", "dialog.votes.pending",
                "dialog.common.previous", "dialog.common.next", "dialog.votes.back-governance",
                "dialog.votes.back-list", "dialog.votes.detail-type", "dialog.votes.once",
                "dialog.votes.cancel-only", "dialog.votes.cancel-irreversible",
                "dialog.votes.status-line", "dialog.votes.empty", "dialog.votes.empty-hint",
                "dialog.votes.list-title", "dialog.votes.detail-title",
                "dialog.votes.entry.approve-count", "dialog.votes.entry.oppose-count",
                "dialog.tooltip.governance.visitor-permission"));

        for (String prefix : List.of("submit-application", "accept-mayor", "change-role")) {
            text(entries, "dialog.confirmation." + prefix + "-confirm");
            text(entries, "dialog.confirmation." + prefix + "-confirm-tooltip");
        }
        // Existing customized role messages may omit the new optional player placeholder.
        text(entries, "dialog.confirmation.change-role-consequence", "player", "role");
        text(entries, "dialog.application.reselect-site");
        // These entries are used directly by rendering boundaries and therefore have a
        // strict placeholder contract as well as a required-key contract.
        text(entries, "system.missing-message", "key");
        text(entries, "system.operation-failed", "detail");
        text(entries, "validation.application.name-length", "minimum", "maximum");
        text(entries, "validation.application.rule-length", "index", "maximum");
        text(entries, "validation.application.rule-format", "index");
        text(entries, "dialog.provision.success-detail");
        text(entries, "dialog.provision.busy-recovery-action");
        text(entries, "dialog.provision.timeout-detail");
        text(entries, "dialog.provision.timeout-recovery-action");
        text(entries, "dialog.confirmation.submit-application-consequence", "amount");
        text(entries, "dialog.tooltip.town.territory-center", "x", "z");
        text(entries, "log.notification.vote-result-delivery-failed", "detail");
        text(entries, "chat.notification.vote-result", "town", "id", "type", "status", "yes", "no", "required");
        text(entries, "chat.notification.identity-changed", "town", "oldRole", "newRole");
        text(entries, "chat.notification.member-removed", "town");
        text(entries, "chat.notification.visitor-added", "town");
        text(entries, "chat.notification.visitor-removed", "town");
        required(entries, List.of("dialog.tooltip.town.territory-preview"));
        range(entries, "dialog.tax.rate-format");
        range(entries, "dialog.buff.duration-format");
        range(entries, "dialog.buff.intensity-format");
        return Map.copyOf(entries);
    }

    private static void required(Map<String, Entry> entries, List<String> keys) {
        keys.forEach(key -> entries.putIfAbsent(key,
                new Entry(key, Set.of(), Format.TEXT, false)));
    }

    private static void text(Map<String, Entry> entries, String key, String... placeholders) {
        entries.put(key, new Entry(key, Set.of(placeholders), Format.TEXT, true));
    }

    private static void range(Map<String, Entry> entries, String key) {
        entries.put(key, new Entry(key, Set.of(), Format.RANGE, false));
    }
}
