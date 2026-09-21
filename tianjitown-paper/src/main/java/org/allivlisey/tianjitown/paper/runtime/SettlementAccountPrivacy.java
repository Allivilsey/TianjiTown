package org.allivlisey.tianjitown.paper.runtime;

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent;
import com.mojang.brigadier.suggestion.Suggestions;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.milkbowl.vault.economy.Economy;
import org.allivlisey.tianjitown.integrations.vault.CmiAccountVisibility;
import org.allivlisey.tianjitown.integrations.vault.VaultSettlementService;
import org.allivlisey.tianjitown.integrations.vault.XConomyAccountVisibility;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.Plugin;

public final class SettlementAccountPrivacy implements Listener {
    private static final Set<String> ECONOMY_COMMANDS = Set.of(
            "bal", "balance", "money", "ebal", "ebalance", "emoney", "pay", "epay",
            "baltop", "balancetop", "ebaltop", "ebalancetop", "eco", "economy", "eeconomy");
    private final TianjiTownPlugin plugin;
    private final VaultSettlementService settlement;
    private final String formerName;
    private XConomyAccountVisibility xconomy;

    public SettlementAccountPrivacy(TianjiTownPlugin plugin, VaultSettlementService settlement) {
        this(plugin, settlement, null);
    }

    public SettlementAccountPrivacy(TianjiTownPlugin plugin, VaultSettlementService settlement, String formerName) {
        this.plugin = plugin;
        this.settlement = settlement;
        this.formerName = formerName;
    }

    public void enforce() {
        try {
            Plugin provider = plugin.getServer().getPluginManager().getPlugin("XConomy");
            var registration = plugin.getServer().getServicesManager().getRegistration(Economy.class);
            if (provider == null || !provider.isEnabled() || registration == null
                    || registration.getPlugin() != provider) {
                throw new IllegalStateException("Vault Economy provider must be XConomy");
            }
            if (xconomy == null) {
                xconomy = new XConomyAccountVisibility(provider, settlement.accountId(),
                        settlement.accountName());
            }
            xconomy.hide();
            if (formerName != null) xconomy.hideFormerName(formerName);
            Plugin cmi = plugin.getServer().getPluginManager().getPlugin("CMI");
            if (cmi != null && cmi.isEnabled()) {
                CmiAccountVisibility.hide(cmi, settlement.accountName());
                if (formerName != null) CmiAccountVisibility.hide(cmi, formerName);
            }
        } catch (RuntimeException | LinkageError exception) {
            throw new IllegalStateException(plugin.messages().plainText(
                    "diagnostic.vault.settlement.privacy-failure",
                    Map.of("account", settlement.accountName(), "detail", RuntimeText.safeMessage(exception))),
                    exception);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (event.getPlayer().hasPermission("tianjitown.admin")) return;
        if (targetsAccount(event.getMessage())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.messages().text("chat.finance.account-unavailable"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(AsyncPlayerPreLoginEvent event) {
        // A renamed offline account still has the original UUID. Do not let a player
        // claim that identity and make XConomy rename the bank back during PlayerJoin.
        if (settlement.accountId().equals(event.getUniqueId())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.messages().text("chat.finance.account-unavailable"));
        }
    }

    // This is the final outgoing suggestion list, including Brigadier and async completers.
    // It only reads immutable account identity, without async Bukkit permission/provider access.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSuggestions(AsyncPlayerSendSuggestionsEvent event) {
        var original = event.getSuggestions();
        var visible = original.getList().stream()
                .filter(suggestion -> !isAccount(suggestion.getText())).toList();
        if (visible.size() != original.getList().size()) {
            event.setSuggestions(new Suggestions(original.getRange(), visible));
        }
    }

    boolean targetsAccount(String command) {
        String[] parts = command.strip().replaceFirst("^/", "").split("\\s+");
        String root = commandName(parts[0]);
        int start = 1;
        if (root.equals("cmi")) {
            if (parts.length < 2) return false;
            root = commandName(parts[1]);
            start = 2;
        }
        return ECONOMY_COMMANDS.contains(root)
                && Arrays.stream(parts, start, parts.length).anyMatch(this::isAccount);
    }

    private boolean isAccount(String value) {
        return settlement.accountName().equalsIgnoreCase(value)
                || formerName != null && formerName.equalsIgnoreCase(value)
                || settlement.accountId().toString().equalsIgnoreCase(value);
    }

    private static String commandName(String value) {
        return value.substring(value.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
    }
}
