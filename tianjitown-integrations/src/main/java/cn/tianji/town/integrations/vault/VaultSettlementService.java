package cn.tianji.town.integrations.vault;

import cn.tianji.town.core.economy.MoneyAmount;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

public final class VaultSettlementService {
    private final Server server;
    private final String accountName;
    private final UUID accountId;
    private final OfflinePlayer account;
    private final int scale;

    public VaultSettlementService(Server server, String accountName, int configuredScale) {
        this.server = Objects.requireNonNull(server, "server");
        if (accountName == null || accountName.isBlank()) {
            throw new IllegalArgumentException("清算账户名不能为空");
        }
        this.account = server.getOfflinePlayer(accountName);
        this.accountId = account.getUniqueId();
        this.accountName = account.getName() == null ? accountName : account.getName();
        Economy economy = economy();
        int providerScale = economy.fractionalDigits();
        this.scale = providerScale >= 0 ? Math.min(providerScale, 8) : configuredScale;
        if (scale < 0 || scale > 8) {
            throw new IllegalArgumentException("Vault 金额精度必须在 0~8 之间");
        }
    }

    public Result ensureAccount() {
        requireMainThread();
        Economy economy = economy();
        if (economy.hasAccount(account)) {
            return Result.success("清算账户已就绪");
        }
        if (economy.createPlayerAccount(account) && economy.hasAccount(account)) {
            return Result.success("清算账户已就绪");
        }
        return Result.failure("Vault provider 无法创建或重新读取离线清算账户 "
                + accountName, false, false);
    }

    public long balanceMinor() {
        requireMainThread();
        Economy economy = readyEconomy();
        double balance = economy.getBalance(account);
        return amount(balance).minorUnits();
    }

    public Result checkAvailability() {
        requireMainThread();
        try {
            readyEconomy();
            return Result.success("Vault 清算账户可用");
        } catch (AvailabilityException exception) {
            return Result.failure(exception.getMessage(), false, false);
        }
    }

    public Result transferFromPlayer(OfflinePlayer player, long amountMinor) {
        requireMainThread();
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("捐款金额必须大于 0");
        }
        Economy economy;
        try {
            economy = readyEconomy();
        } catch (AvailabilityException exception) {
            return Result.failure(exception.getMessage(), false, false);
        }
        double amount = decimal(amountMinor);
        EconomyResponse withdrawn = economy.withdrawPlayer(player, amount);
        if (!withdrawn.transactionSuccess()) {
            return Result.failure("玩家扣款失败: " + withdrawn.errorMessage, false, false);
        }
        EconomyResponse deposited = economy.depositPlayer(account, amount);
        if (deposited.transactionSuccess()) {
            return Result.success("资金已转入清算账户");
        }
        EconomyResponse compensation = economy.depositPlayer(player, amount);
        boolean compensated = compensation.transactionSuccess();
        return Result.failure("清算账户入账失败: " + deposited.errorMessage,
                compensated, !compensated);
    }

    public Result adjustSettlement(long amountMinor) {
        requireMainThread();
        if (amountMinor == 0) {
            throw new IllegalArgumentException("调整金额不能为 0");
        }
        Economy economy;
        try {
            economy = readyEconomy();
        } catch (AvailabilityException exception) {
            return Result.failure(exception.getMessage(), false, false);
        }
        double amount = decimal(Math.abs(amountMinor));
        EconomyResponse response = amountMinor > 0
                ? economy.depositPlayer(account, amount)
                : economy.withdrawPlayer(account, amount);
        return response.transactionSuccess() ? Result.success("清算账户调整完成")
                : Result.failure("清算账户调整失败: " + response.errorMessage, false, false);
    }

    public Result compensateSettlement(long appliedAmountMinor) {
        requireMainThread();
        return adjustSettlement(Math.negateExact(appliedAmountMinor));
    }

    public int scale() {
        return scale;
    }

    public String accountName() {
        return accountName;
    }

    public UUID accountId() {
        return accountId;
    }

    private Economy economy() {
        RegisteredServiceProvider<Economy> registration =
                server.getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null
                || !registration.getProvider().isEnabled()) {
            throw new AvailabilityException("Vault Economy provider 不可用");
        }
        return registration.getProvider();
    }

    private Economy readyEconomy() {
        Economy economy = economy();
        requireAccount(economy);
        return economy;
    }

    private MoneyAmount amount(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Vault 返回了无效金额");
        }
        return MoneyAmount.rounded(BigDecimal.valueOf(value), scale, RoundingMode.HALF_UP);
    }

    private void requireAccount(Economy economy) {
        if (!economy.hasAccount(account)) {
            throw new AvailabilityException("Vault 离线清算账户不可用: " + accountName);
        }
    }

    private double decimal(long minorUnits) {
        return BigDecimal.valueOf(minorUnits, scale).doubleValue();
    }

    private void requireMainThread() {
        if (!server.isPrimaryThread()) {
            throw new IllegalStateException("Vault Economy API 必须在 Paper 主线程调用");
        }
    }

    public record Result(boolean success, String message, boolean compensated,
                         boolean compensationRequired) {
        public static Result success(String message) {
            return new Result(true, message, false, false);
        }

        public static Result failure(String message, boolean compensated,
                                     boolean compensationRequired) {
            return new Result(false, message, compensated, compensationRequired);
        }
    }

    private static final class AvailabilityException extends IllegalStateException {
        private AvailabilityException(String message) {
            super(message);
        }
    }
}
