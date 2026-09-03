package org.allivlisey.tianjitown.paper;

import org.bukkit.command.Command;
import org.bukkit.permissions.Permission;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Applies configurable descriptions to descriptor objects after messages are loaded. */
final class PluginDescriptorMessages {
    private static final String COMMAND_DESCRIPTION = "plugin.command.townadmin.description";
    private static final List<PermissionDescription> PERMISSION_DESCRIPTIONS = List.of(
            new PermissionDescription("tianjitown.admin", "plugin.permission.admin.description"),
            new PermissionDescription("tianjitown.admin.money",
                    "plugin.permission.admin-money.description"),
            new PermissionDescription("tianjitown.admin.tax",
                    "plugin.permission.admin-tax.description"),
            new PermissionDescription("tianjitown.admin.ledger",
                    "plugin.permission.admin-ledger.description"),
            new PermissionDescription("tianjitown.admin.expand",
                    "plugin.permission.admin-expand.description"),
            new PermissionDescription("tianjitown.admin.buff",
                    "plugin.permission.admin-buff.description"),
            new PermissionDescription("tianjitown.admin.operations",
                    "plugin.permission.admin-operations.description"));

    private PluginDescriptorMessages() {
    }

    static void apply(PluginMessages messages, Command command,
                      Function<String, Permission> permissionLookup) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(permissionLookup, "permissionLookup");

        command.setDescription(messages.requiredPlainText(COMMAND_DESCRIPTION));
        for (PermissionDescription descriptor : PERMISSION_DESCRIPTIONS) {
            Permission permission = permissionLookup.apply(descriptor.name());
            if (permission != null) {
                permission.setDescription(messages.requiredPlainText(descriptor.messageKey()));
            }
        }
    }

    private record PermissionDescription(String name, String messageKey) {
    }
}
