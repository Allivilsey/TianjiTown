package org.allivlisey.tianjitown.integrations.vault;

import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.allivlisey.tianjitown.integrations.vault.SettlementAccountMigration.Account;

/** Deletes through XConomy's own command, including its cache and synchronization handling. */
public final class XConomyLegacyTaxAccounts implements LegacyTaxAccountCleanup.Accounts {
    private final Server server;
    private final XConomyAccountRenamer reader;
    private final PluginCommand command;

    public XConomyLegacyTaxAccounts(Server server, Plugin provider) {
        this.server = server;
        reader = new XConomyAccountRenamer(server, provider);
        command = server.getPluginCommand("xconomy:xconomy");
        if (command == null || command.getPlugin() != provider) {
            throw new IllegalStateException("XConomy deletion command unavailable");
        }
    }

    @Override public Account byName(String name) { return reader.byName(name); }
    @Override public Account byId(UUID id) { return reader.byId(id); }
    @Override public UUID resolveId(String name) { return server.getOfflinePlayer(name).getUniqueId(); }
    @Override public boolean hasPlayed(UUID id) { return reader.hasPlayed(id); }
    @Override public void prepare() { reader.prepare(); }

    @Override public void delete(Account account) {
        prepare();
        Account current = byName(account.name());
        if (current == null || !current.id().equals(account.id()) || hasPlayed(account.id())) {
            throw new IllegalStateException("Legacy tax account identity changed before deletion");
        }
        if (!command.execute(server.getConsoleSender(), "xconomy", new String[] {"deldata", account.name()})) {
            throw new IllegalStateException("XConomy rejected account deletion");
        }
    }
}
