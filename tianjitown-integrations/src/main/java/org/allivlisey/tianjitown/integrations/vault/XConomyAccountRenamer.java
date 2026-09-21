package org.allivlisey.tianjitown.integrations.vault;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;
import java.sql.Connection;
import java.sql.SQLException;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

/** Uses XConomy's existing player-name update path; no debit, deposit or database schema change. */
public final class XConomyAccountRenamer implements SettlementAccountMigration.Accounts {
    private final Server server;
    private final Object api;
    private final Method registerName;
    private final Object uuidMode;
    private final Object database;
    private final Method connection;
    private final Method release;
    private final String table;
    private final Method cachedAccount;
    private final Method refreshAccount;

    public XConomyAccountRenamer(Server server, Plugin provider) {
        this.server = server;
        try {
            ClassLoader loader = provider.getClass().getClassLoader();
            Class<?> apiType = Class.forName("me.yic.xconomy.api.XConomyAPI", true, loader);
            api = apiType.getConstructor().newInstance();
            registerName = apiType.getMethod("createPlayerData", UUID.class, String.class);
            Class<?> sql = Class.forName("me.yic.xconomy.data.sql.SQL", true, loader);
            database = sql.getField("database").get(null);
            connection = database.getClass().getMethod("getConnectionAndCheck");
            release = database.getClass().getMethod("closeHikariConnection", Connection.class);
            table = (String) sql.getField("tableName").get(null);
            if (!table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalStateException("Unsupported XConomy account table name");
            }
            cachedAccount = Class.forName("me.yic.xconomy.data.DataCon", true, loader)
                    .getMethod("getPlayerData", UUID.class);
            refreshAccount = Class.forName("me.yic.xconomy.data.DataLink", true, loader)
                    .getMethod("getPlayerData", Object.class);
            Object config = Class.forName("me.yic.xconomy.XConomyLoad", true, loader)
                    .getField("Config").get(null);
            uuidMode = config.getClass().getField("UUIDMODE").get(config);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("XConomy account rename API unavailable", exception);
        }
    }

    @Override public SettlementAccountMigration.Account byName(String name) { return read(name); }
    @Override public SettlementAccountMigration.Account byId(UUID id) { return read(id); }

    @Override public boolean hasPlayed(UUID id) {
        var player = server.getOfflinePlayer(id);
        return player.isOnline() || player.hasPlayedBefore();
    }

    @Override public void prepare() {
        if (!server.getOnlinePlayers().isEmpty()) {
            throw new IllegalStateException("Restart the server with no connected players to migrate the account");
        }
        // Other modes derive/alias UUIDs from names. Only DEFAULT preserves this binding unambiguously.
        if (!(uuidMode instanceof Enum<?> mode) || !"DEFAULT".equals(mode.name())) {
            throw new IllegalStateException("Automatic account rename requires XConomy UUID-mode: Default");
        }
    }

    @Override public void rename(UUID id, String name) {
        prepare();
        try {
            var persisted = byId(id);
            Object cached = cachedAccount.invoke(null, id);
            if (persisted == null || cached == null || persisted.balance().compareTo(
                    (BigDecimal) cached.getClass().getMethod("getBalance").invoke(cached)) != 0) {
                throw new IllegalStateException("XConomy has an unconfirmed settlement balance; restart before migration");
            }
            if (!Boolean.TRUE.equals(registerName.invoke(api, id, name))) {
                throw new IllegalStateException("XConomy rejected account rename");
            }
            var renamed = byId(id);
            if (renamed == null || !name.equals(renamed.name())
                    || renamed.balance().compareTo(persisted.balance()) != 0) {
                throw new IllegalStateException("XConomy account rename could not be verified");
            }
            // XConomy's rename path caches the old PlayerData name before changing the row.
            // With the startup balance verified above, load the new metadata for Vault/QuickShop.
            refreshAccount.invoke(null, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("XConomy account rename failed", exception);
        }
    }

    private SettlementAccountMigration.Account read(Object key) {
        Connection opened = null;
        try {
            // Read only, through the provider's connection. Its convenience lookup swallows
            // SQL errors as "missing account" and can overwrite a pending balance cache.
            opened = (Connection) connection.invoke(database);
            if (opened == null) throw new IllegalStateException("XConomy database unavailable");
            String predicate = key instanceof UUID ? "UID = ?" : "LOWER(player) = LOWER(?)";
            try (var query = opened.prepareStatement("SELECT UID, player, balance FROM " + table
                    + " WHERE " + predicate)) {
                query.setString(1, key.toString());
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) return null;
                    var result = new SettlementAccountMigration.Account(UUID.fromString(rows.getString(1)),
                            rows.getString(2), rows.getBigDecimal(3));
                    if (rows.next()) throw new IllegalStateException("Ambiguous XConomy account name: " + key);
                    return result;
                }
            }
        } catch (ReflectiveOperationException | SQLException exception) {
            throw new IllegalStateException("XConomy account read failed", exception);
        } finally {
            if (opened != null) {
                try { release.invoke(database, opened); }
                catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Could not release XConomy connection", exception);
                }
            }
        }
    }
}
