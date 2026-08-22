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
        try {
            RegisteredServiceProvider<Economy> registration =
                    server.getServicesManager().getRegistration(Economy.class);
            if (registration == null || registration.getProvider() == null) {
                return new Result(false, null, "Vault 未注册 Economy 服务");
            }
            Economy economy = registration.getProvider();
            boolean enabled = economy.isEnabled();
            return new Result(enabled, economy.getName(),
                    enabled ? "正常" : "Economy provider 未启用");
        } catch (RuntimeException | LinkageError exception) {
            return new Result(false, null, "Vault Economy 探测异常: "
                    + safeMessage(exception));
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    public record Result(boolean healthy, String provider, String message) {
    }
}

