package org.allivlisey.tianjitown.paper.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import net.milkbowl.vault.economy.Economy;
import org.allivlisey.tianjitown.integrations.vault.SettlementAccountMigration;
import org.allivlisey.tianjitown.integrations.vault.XConomyAccountRenamer;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

public final class SettlementAccountMigrationStartup {
    private static final String KEY = "economy.settlement-account";
    private SettlementAccountMigrationStartup() { }

    public static SettlementAccountMigration.Binding run(TianjiTownPlugin plugin) {
        String configured = plugin.getConfig().getString(KEY, SettlementAccountMigration.TARGET).strip();
        var directory = plugin.getDataFolder().toPath();
        if (!"tax".equalsIgnoreCase(configured)
                && (!SettlementAccountMigration.TARGET.equals(configured)
                || !Files.exists(directory.resolve(SettlementAccountMigration.JOURNAL)))) return null;
        try {
            var server = plugin.getServer();
            var provider = server.getPluginManager().getPlugin("XConomy");
            var registration = server.getServicesManager().getRegistration(Economy.class);
            if (provider == null || !provider.isEnabled() || registration == null
                    || registration.getPlugin() != provider) {
                throw new IllegalStateException("Vault Economy provider must be XConomy");
            }
            var accounts = new XConomyAccountRenamer(server, provider);
            var binding = new SettlementAccountMigration(directory, accounts).run(configured,
                    server.getOfflinePlayer(configured).getUniqueId(),
                    server.getOfflinePlayer(SettlementAccountMigration.TARGET).getUniqueId(), name -> {
                        if (!name.equals(plugin.getConfig().getString(KEY))) saveConfiguration(plugin, name);
                    });
            if (binding != null && !configured.equals(SettlementAccountMigration.TARGET)) {
                plugin.getLogger().info(plugin.messages().plainText("log.vault.settlement.migration-complete",
                        Map.of("account", SettlementAccountMigration.TARGET, "uuid", binding.id())));
            }
            return binding;
        } catch (IOException | RuntimeException | LinkageError exception) {
            throw new IllegalStateException(plugin.messages().plainText(
                    "diagnostic.vault.settlement.migration-failure",
                    Map.of("detail", RuntimeText.safeMessage(exception))), exception);
        }
    }

    private static void saveConfiguration(TianjiTownPlugin plugin, String name) {
        var file = plugin.getDataFolder().toPath().resolve("config.yml");
        try {
            // Read the real file without jar defaults and preserve all unrelated configured values.
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.options().parseComments(true);
            configuration.load(file.toFile());
            var backup = file.resolveSibling("config.before-settlement-account-migration.yml");
            if (!Files.exists(backup)) SettlementAccountMigration.atomicWrite(backup, Files.readAllBytes(file));
            configuration.set(KEY, name);
            SettlementAccountMigration.atomicWrite(file,
                    configuration.saveToString().getBytes(StandardCharsets.UTF_8));
            plugin.reloadConfig();
            if (!name.equals(plugin.getConfig().getString(KEY))) {
                throw new IllegalStateException("Settlement account configuration could not be reloaded");
            }
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException("Could not persist settlement account configuration", exception);
        }
    }
}
