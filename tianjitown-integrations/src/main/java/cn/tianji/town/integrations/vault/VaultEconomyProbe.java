package cn.tianji.town.integrations.vault;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class VaultEconomyProbe {
    private final Server server;

    public VaultEconomyProbe(Server server) {
        this.server = server;
    }

    public Result verify() {
        RegisteredServiceProvider<Economy> registration =
                server.getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            return new Result(false, null, "Vault 未注册 Economy 服务");
        }
        Economy economy = registration.getProvider();
        return new Result(economy.isEnabled(), economy.getName(),
                economy.isEnabled() ? "正常" : "Economy provider 未启用");
    }

    public record Result(boolean healthy, String provider, String message) {
    }
}

