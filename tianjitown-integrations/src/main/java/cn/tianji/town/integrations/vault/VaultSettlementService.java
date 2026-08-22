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
        try {
            this.account = server.getOfflinePlayer(accountName);
            this.accountId = account.getUniqueId();
            this.accountName = account.getName() == null ? accountName : account.getName();
        } catch (RuntimeException | LinkageError exception) {
            throw unavailable("无法解析 Vault 离线清算账户", exception);
        }
        Economy economy = economy();
        int providerScale;
        try {
            providerScale = economy.fractionalDigits();
        } catch (RuntimeException | LinkageError exception) {
            throw unavailable("无法读取 Vault 金额精度", exception);
        }
        this.scale = providerScale >= 0 ? Math.min(providerScale, 8) : configuredScale;
        if (scale < 0 || scale > 8) {
            throw new IllegalArgumentException("Vault 金额精度必须在 0~8 之间");
        }
    }

    public Result ensureAccount() {
        requireMainThread();
        try {
            Economy economy = economy();
            if (economy.hasAccount(account)) {
                return Result.success("清算账户已就绪");
            }
            if (economy.createPlayerAccount(account) && economy.hasAccount(account)) {
                return Result.success("清算账户已就绪");
            }
            return Result.failure("Vault provider 无法创建或重新读取离线清算账户 "
                    + accountName, false, false);
        } catch (AvailabilityException exception) {
            return Result.failure(exception.getMessage(), false, false);
        } catch (RuntimeException | LinkageError exception) {
            return Result.failure("Vault 清算账户初始化异常: " + safeMessage(exception),
                    false, false);
        }
    }

    public long balanceMinor() {
        requireMainThread();
        Economy economy = readyEconomy();
        try {
            double balance = economy.getBalance(account);
            return amount(balance).minorUnits();
        } catch (RuntimeException | LinkageError exception) {
            throw unavailable("Vault 清算余额读取异常", exception);
        }
    }

    public Result checkAvailability() {
        requireMainThread();
        try {
            readyEconomy();
            return Result.success("Vault 清算账户可用");
        } catch (RuntimeException | LinkageError exception) {
            return Result.failure("Vault 清算账户不可用: " + safeMessage(exception),
                    false, false);
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
        EconomyResponse withdrawn;
        try {
            withdrawn = economy.withdrawPlayer(player, amount);
        } catch (RuntimeException | LinkageError exception) {
            return ambiguousFailure("玩家扣款", exception);
        }
        if (withdrawn == null) {
            return ambiguousFailure("玩家扣款", new IllegalStateException("返回结果为空"));
        }
        if (!withdrawn.transactionSuccess()) {
            return Result.failure("玩家扣款失败: " + withdrawn.errorMessage, false, false);
        }
        EconomyResponse deposited;
        try {
            deposited = economy.depositPlayer(account, amount);
        } catch (RuntimeException | LinkageError exception) {
            return ambiguousFailure("清算账户入账", exception);
        }
        if (deposited == null) {
            return ambiguousFailure("清算账户入账", new IllegalStateException("返回结果为空"));
        }
        if (deposited.transactionSuccess()) {
            return Result.success("资金已转入清算账户");
        }
        EconomyResponse compensation;
        try {
            compensation = economy.depositPlayer(player, amount);
        } catch (RuntimeException | LinkageError exception) {
            return ambiguousFailure("玩家自动补偿", exception);
        }
        if (compensation == null) {
            return ambiguousFailure("玩家自动补偿", new IllegalStateException("返回结果为空"));
        }
        boolean compensated = compensation.transactionSuccess();
        return compensated
                ? Result.failure("清算账户入账失败: " + deposited.errorMessage, true, false)
                : Result.playerRefundRequired("清算账户入账失败: "
                + deposited.errorMessage);
    }

    public Result refundDebitedPlayer(OfflinePlayer player, long amountMinor) {
        requireMainThread();
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("补偿金额必须大于 0");
        }
        Economy economy;
        try {
            economy = readyEconomy();
        } catch (AvailabilityException exception) {
            return Result.failure(exception.getMessage(), false, false);
        }
        EconomyResponse refunded;
        try {
            refunded = economy.depositPlayer(player, decimal(amountMinor));
        } catch (RuntimeException | LinkageError exception) {
            return ambiguousFailure("玩家退款", exception);
        }
        if (refunded == null) {
            return ambiguousFailure("玩家退款", new IllegalStateException("返回结果为空"));
        }
        return refunded.transactionSuccess()
                ? Result.success("玩家扣款已自动补偿")
                : Result.failure("玩家自动补偿失败: " + refunded.errorMessage,
                false, false);
    }

    public Result transferToPlayer(OfflinePlayer player, long amountMinor) {
        requireMainThread();
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("返还金额必须大于 0");
        }
        Economy economy;
        try {
            economy = readyEconomy();
        } catch (AvailabilityException exception) {
            return Result.failure(exception.getMessage(), false, false);
        }
        double amount = decimal(amountMinor);
        EconomyResponse withdrawn;
        try {
            withdrawn = economy.withdrawPlayer(account, amount);
        } catch (RuntimeException | LinkageError exception) {
            return ambiguousFailure("清算账户扣款", exception);
        }
        if (withdrawn == null) {
            return ambiguousFailure("清算账户扣款", new IllegalStateException("返回结果为空"));
        }
        if (!withdrawn.transactionSuccess()) {
            return Result.failure("清算账户扣款失败: " + withdrawn.errorMessage, false, false);
        }
        EconomyResponse deposited;
        try {
            deposited = economy.depositPlayer(player, amount);
        } catch (RuntimeException | LinkageError exception) {
            return ambiguousFailure("玩家返还入账", exception);
        }
        if (deposited == null) {
            return ambiguousFailure("玩家返还入账", new IllegalStateException("返回结果为空"));
        }
        if (deposited.transactionSuccess()) {
            return Result.success("资金已返还玩家");
        }
        EconomyResponse compensation;
        try {
            compensation = economy.depositPlayer(account, amount);
        } catch (RuntimeException | LinkageError exception) {
            return ambiguousFailure("清算账户自动补偿", exception);
        }
        if (compensation == null) {
            return ambiguousFailure("清算账户自动补偿", new IllegalStateException("返回结果为空"));
        }
        boolean compensated = compensation.transactionSuccess();
        return Result.failure("玩家返还入账失败: " + deposited.errorMessage,
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
        EconomyResponse response;
        try {
            response = amountMinor > 0
                    ? economy.depositPlayer(account, amount)
                    : economy.withdrawPlayer(account, amount);
        } catch (RuntimeException | LinkageError exception) {
            return ambiguousFailure("清算账户调整", exception);
        }
        if (response == null) {
            return ambiguousFailure("清算账户调整", new IllegalStateException("返回结果为空"));
        }
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
        try {
            RegisteredServiceProvider<Economy> registration =
                    server.getServicesManager().getRegistration(Economy.class);
            if (registration == null || registration.getProvider() == null
                    || !registration.getProvider().isEnabled()) {
                throw new AvailabilityException("Vault Economy provider 不可用");
            }
            return registration.getProvider();
        } catch (AvailabilityException exception) {
            throw exception;
        } catch (RuntimeException | LinkageError exception) {
            throw unavailable("Vault Economy provider 探测异常", exception);
        }
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
        try {
            if (!economy.hasAccount(account)) {
                throw new AvailabilityException("Vault 离线清算账户不可用: " + accountName);
            }
        } catch (AvailabilityException exception) {
            throw exception;
        } catch (RuntimeException | LinkageError exception) {
            throw unavailable("Vault 离线清算账户检查异常", exception);
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

    private static Result ambiguousFailure(String operation, Throwable throwable) {
        return Result.failure("Vault " + operation + "调用异常，资金结果需要人工复核: "
                + safeMessage(throwable), false, true);
    }

    private static AvailabilityException unavailable(String operation, Throwable throwable) {
        return new AvailabilityException(operation + ": " + safeMessage(throwable), throwable);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    public record Result(boolean success, String message, boolean compensated,
                         boolean compensationRequired, boolean playerRefundRequired) {
        public static Result success(String message) {
            return new Result(true, message, false, false, false);
        }

        public static Result failure(String message, boolean compensated,
                                     boolean compensationRequired) {
            return new Result(false, message, compensated, compensationRequired, false);
        }

        private static Result playerRefundRequired(String message) {
            return new Result(false, message, false, true, true);
        }
    }

    private static final class AvailabilityException extends IllegalStateException {
        private AvailabilityException(String message) {
            super(message);
        }

        private AvailabilityException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
