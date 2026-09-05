package org.allivlisey.tianjitown.paper.ui.membership;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import org.allivlisey.tianjitown.storage.governance.MemberGovernanceSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Presentation model for the visitor-management entry in the governance menu. */
public record VisitorManagementMenuModel(boolean visible, String label, List<String> lore,
                                  String action, String target) {
    private static final String LABEL_KEY = "dialog.governance.visitors";
    private static final String TOOLTIP_KEY = "dialog.tooltip.governance.visitors";
    private static final String PERMISSION_KEY = "dialog.tooltip.governance.visitor-permission";

    public VisitorManagementMenuModel {
        label = Objects.requireNonNull(label, "label");
        lore = List.copyOf(Objects.requireNonNull(lore, "lore"));
        if (visible) {
            action = Objects.requireNonNull(action, "action");
            target = Objects.requireNonNull(target, "target");
        }
    }

    public static VisitorManagementMenuModel create(PluginMessages messages,
                                             MemberGovernanceSnapshot governance,
                                             UUID townId) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(townId, "townId");
        if (!canManage(governance, townId)) {
            return new VisitorManagementMenuModel(false, "", List.of(), null, null);
        }
        return new VisitorManagementMenuModel(true,
                messages.rawText(LABEL_KEY),
                List.of(messages.rawText(TOOLTIP_KEY), messages.rawText(PERMISSION_KEY)),
                "VISITOR_CENTER", townId.toString());
    }

    public static boolean canManage(MemberGovernanceSnapshot governance, UUID townId) {
        return governance != null
                && townId != null
                && townId.equals(governance.townId())
                && governance.role() != null
                && governance.role().isLeader();
    }
}
