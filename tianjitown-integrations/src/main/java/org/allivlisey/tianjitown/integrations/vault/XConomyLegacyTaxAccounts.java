package org.allivlisey.tianjitown.integrations.vault;

import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.allivlisey.tianjitown.integrations.vault.SettlementAccountMigration.Account;

/** Uses XConomy's UUID deletion path, including cache invalidation and synchronization. */
public final class XConomyLegacyTaxAccounts implements LegacyTaxAccountCleanup.Accounts {
    private final Server server;
    private final XConomyAccountRenamer reader;
    private final Object api;
    private final Method lookup;
    private final Method delete;

    public XConomyLegacyTaxAccounts(Server server, Plugin provider) {
        this(server, new XConomyAccountRenamer(server, provider),
                XConomyAccountRenamer.loadApi(provider), deletionType(provider));
    }

    XConomyLegacyTaxAccounts(Server server, XConomyAccountRenamer reader, Object api, Class<?> dataCon) {
        this.server = server;
        this.reader = reader;
        this.api = api;
        try {
            lookup = api.getClass().getMethod("getPlayerData", UUID.class);
            // deldata resolves a name first; select PlayerData by UUID to avoid case collisions.
            delete = dataCon.getMethod("deletePlayerData", lookup.getReturnType());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("XConomy UUID deletion capability unavailable", exception);
        }
    }

    private static Class<?> deletionType(Plugin provider) {
        try {
            return Class.forName("me.yic.xconomy.data.DataCon", true, provider.getClass().getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("XConomy UUID deletion capability unavailable", exception);
        }
    }

    @Override public Account byId(UUID id) { return reader.byId(id); }
    @Override public UUID resolveId(String name) { return server.getOfflinePlayer(name).getUniqueId(); }
    @Override public boolean hasPlayed(UUID id) { return reader.hasPlayed(id); }
    @Override public void prepare() { reader.prepare(); }

    @Override public void delete(Account account) {
        prepare();
        try {
            Object current = lookup.invoke(api, account.id());
            if (current == null || hasPlayed(account.id())
                    || !account.id().equals(current.getClass().getMethod("getUniqueId").invoke(current))
                    || !account.name().equals(current.getClass().getMethod("getName").invoke(current))) {
                throw new IllegalStateException("Legacy tax account identity changed before deletion");
            }
            delete.invoke(null, current);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("XConomy UUID deletion failed", exception);
        }
    }
}
