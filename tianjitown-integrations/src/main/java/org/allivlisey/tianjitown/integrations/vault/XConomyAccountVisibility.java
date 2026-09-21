package org.allivlisey.tianjitown.integrations.vault;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.plugin.Plugin;

/** Uses XConomy's own persistent hidden flag, which excludes both ranking and total balance. */
public final class XConomyAccountVisibility {
    private final UUID accountId;
    private final String accountName;
    private final Method getPlayerData;
    private final Method getHidden;
    private final Method setHidden;
    private final Method refreshRanking;
    private final Method hideName;
    private final Method removeSuggestion;
    private boolean initialized;

    public XConomyAccountVisibility(Plugin xconomy, UUID accountId, String accountName) {
        this(accountId, accountName,
                type(xconomy, "data.DataCon"), type(xconomy, "data.DataLink"),
                type(xconomy, "info.HiddenINFO"), type(xconomy, "utils.TabListCon"));
    }

    XConomyAccountVisibility(UUID accountId, String accountName, Class<?> dataCon,
                            Class<?> dataLink, Class<?> hiddenInfo, Class<?> tabList) {
        this.accountId = Objects.requireNonNull(accountId);
        this.accountName = Objects.requireNonNull(accountName);
        try {
            getPlayerData = dataCon.getMethod("getPlayerData", UUID.class);
            getHidden = dataCon.getMethod("getPlayerHiddenState", UUID.class);
            setHidden = dataLink.getMethod("setTopBalHide", UUID.class, int.class);
            refreshRanking = dataCon.getMethod("baltop");
            hideName = hiddenInfo.getMethod("addHidden", String.class);
            removeSuggestion = tabList.getMethod("remove_Tab_PlayerList", String.class);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("XConomy account visibility API unavailable", exception);
        }
    }

    public void hide() {
        try {
            // Missing accounts also return hidden=1 in XConomy: verify existence separately.
            if (getPlayerData.invoke(null, accountId) == null) {
                throw new IllegalStateException("XConomy player account unavailable: " + accountName);
            }
            boolean changed = !Integer.valueOf(1).equals(getHidden.invoke(null, accountId));
            if (changed) {
                setHidden.invoke(null, accountId, 1);
                // XConomy only updates this flag after its SQL write succeeds. Do not evict
                // player caches: reloading a whole account could replace a pending balance.
                if (!Integer.valueOf(1).equals(getHidden.invoke(null, accountId))) {
                    throw new IllegalStateException("XConomy did not persist account visibility");
                }
            }
            if (!initialized || changed) {
                // Also replaces cached PAPI rankings and the cached server total immediately.
                refreshRanking.invoke(null);
            }
            hideName.invoke(null, accountName);
            removeSuggestion.invoke(null, accountName);
            initialized = true;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("XConomy account visibility update failed", exception);
        }
    }

    public void hideFormerName(String name) {
        try {
            hideName.invoke(null, name);
            removeSuggestion.invoke(null, name);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("XConomy former account name visibility update failed", exception);
        }
    }

    private static Class<?> type(Plugin plugin, String suffix) {
        try {
            return Class.forName("me.yic.xconomy." + suffix, true,
                    plugin.getClass().getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("XConomy account visibility API unavailable", exception);
        }
    }
}
