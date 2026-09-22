package org.allivlisey.tianjitown.integrations.vault;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

/** Uses XConomy's existing player-name update path; no debit, deposit or database schema change. */
public final class XConomyAccountRenamer implements SettlementAccountMigration.Accounts {
    private final Server server;
    private final Object api;
    private final Method registerName;
    private final Object uuidMode;
    private final Method accountById;
    private final Method accountByName;

    public XConomyAccountRenamer(Server server, Plugin provider) {
        this(server, loadApi(provider), loadUuidMode(provider));
    }

    XConomyAccountRenamer(Server server, Object api, Object uuidMode) {
        this.server = server;
        this.api = api;
        this.uuidMode = uuidMode;
        try {
            Class<?> apiType = api.getClass();
            registerName = apiType.getMethod("createPlayerData", UUID.class, String.class);
            accountById = apiType.getMethod("getPlayerData", UUID.class);
            accountByName = apiType.getMethod("getPlayerData", String.class);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("XConomy account rename API unavailable", exception);
        }
    }

    private static Object loadApi(Plugin provider) {
        try {
            return Class.forName("me.yic.xconomy.api.XConomyAPI", true,
                    provider.getClass().getClassLoader()).getConstructor().newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("XConomy account rename API unavailable", exception);
        }
    }

    private static Object loadUuidMode(Plugin provider) {
        try {
            Object config = Class.forName("me.yic.xconomy.XConomyLoad", true,
                    provider.getClass().getClassLoader()).getField("Config").get(null);
            return config.getClass().getField("UUIDMODE").get(config);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("XConomy UUID mode unavailable", exception);
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
            var before = byId(id);
            if (before == null) throw new IllegalStateException("XConomy settlement account is missing");
            if (!Boolean.TRUE.equals(registerName.invoke(api, id, name))) {
                throw new IllegalStateException("XConomy rejected account rename");
            }
            // XConomy 2.26.3 may cache the old name while renaming. With no players
            // connected, its public lookup clears that cache after reading it.
            byId(id);
            var renamed = byId(id);
            if (renamed == null || !id.equals(renamed.id()) || !name.equals(renamed.name())
                    || renamed.balance().compareTo(before.balance()) != 0) {
                throw new IllegalStateException("XConomy account rename could not be verified");
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("XConomy account rename failed", exception);
        }
    }

    private SettlementAccountMigration.Account read(Object key) {
        try {
            Object account = (key instanceof UUID ? accountById : accountByName).invoke(api, key);
            if (account == null) return null;
            Class<?> type = account.getClass();
            return new SettlementAccountMigration.Account(
                    (UUID) type.getMethod("getUniqueId").invoke(account),
                    (String) type.getMethod("getName").invoke(account),
                    (BigDecimal) type.getMethod("getBalance").invoke(account));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("XConomy account read failed", exception);
        }
    }
}
