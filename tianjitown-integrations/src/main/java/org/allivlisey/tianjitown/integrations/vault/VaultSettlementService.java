package org.allivlisey.tianjitown.integrations.vault;

import org.allivlisey.tianjitown.core.economy.MoneyAmount;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

public final class VaultSettlementService {
    private static final String ACCOUNT_NAME_REQUIRED =
            "validation.vault.account-name-required";
    private static final String ACCOUNT_RESOLVE_FAILURE =
            "diagnostic.vault.settlement.account-resolve-failure";
    private static final String SCALE_READ_FAILURE =
            "diagnostic.vault.settlement.scale-read-failure";
    private static final String SCALE_RANGE = "validation.vault.scale-range";
    private static final String ACCOUNT_READY = "diagnostic.vault.settlement.account-ready";
    private static final String ACCOUNT_CREATE_FAILURE =
            "diagnostic.vault.settlement.account-create-failure";
    private static final String ACCOUNT_INITIALIZATION_FAILURE =
            "diagnostic.vault.settlement.account-initialization-failure";
    private static final String BALANCE_READ_FAILURE =
            "diagnostic.vault.settlement.balance-read-failure";
    private static final String ACCOUNT_AVAILABLE =
            "diagnostic.vault.settlement.account-available";
    private static final String ACCOUNT_UNAVAILABLE =
            "diagnostic.vault.settlement.account-unavailable";
    private static final String DONATION_AMOUNT_POSITIVE =
            "validation.vault.donation-amount-positive";
    private static final String PLAYER_DEBIT_AMBIGUOUS =
            "log.vault.settlement.player-debit-ambiguous";
    private static final String PLAYER_DEBIT_FAILURE =
            "diagnostic.vault.settlement.player-debit-failure";
    private static final String SETTLEMENT_CREDIT_AMBIGUOUS =
            "log.vault.settlement.settlement-credit-ambiguous";
    private static final String SETTLEMENT_CREDIT_FAILURE =
            "diagnostic.vault.settlement.settlement-credit-failure";
    private static final String FUNDS_TRANSFERRED =
            "diagnostic.vault.settlement.funds-transferred";
    private static final String PLAYER_REFUND_AMBIGUOUS =
            "log.vault.settlement.player-refund-ambiguous";
    private static final String REFUND_AMOUNT_POSITIVE =
            "validation.vault.refund-amount-positive";
    private static final String PLAYER_DEBIT_REFUNDED =
            "diagnostic.vault.settlement.player-debit-refunded";
    private static final String PLAYER_REFUND_FAILURE =
            "diagnostic.vault.settlement.player-refund-failure";
    private static final String RETURN_AMOUNT_POSITIVE =
            "validation.vault.return-amount-positive";
    private static final String SETTLEMENT_DEBIT_AMBIGUOUS =
            "log.vault.settlement.settlement-debit-ambiguous";
    private static final String SETTLEMENT_DEBIT_FAILURE =
            "diagnostic.vault.settlement.settlement-debit-failure";
    private static final String PLAYER_CREDIT_AMBIGUOUS =
            "log.vault.settlement.player-credit-ambiguous";
    private static final String FUNDS_RETURNED =
            "diagnostic.vault.settlement.funds-returned";
    private static final String SETTLEMENT_COMPENSATION_AMBIGUOUS =
            "log.vault.settlement.settlement-compensation-ambiguous";
    private static final String PLAYER_CREDIT_FAILURE =
            "diagnostic.vault.settlement.player-credit-failure";
    private static final String ADJUSTMENT_NON_ZERO =
            "validation.vault.adjustment-non-zero";
    private static final String ACCOUNT_ADJUSTMENT_AMBIGUOUS =
            "log.vault.settlement.account-adjustment-ambiguous";
    private static final String ACCOUNT_ADJUSTED =
            "diagnostic.vault.settlement.account-adjusted";
    private static final String ACCOUNT_ADJUSTMENT_FAILURE =
            "diagnostic.vault.settlement.account-adjustment-failure";
    private static final String PROVIDER_UNAVAILABLE =
            "diagnostic.vault.provider-unavailable";
    private static final String PROVIDER_PROBE_FAILURE =
            "diagnostic.vault.provider-probe-failure";
    private static final String INVALID_AMOUNT = "diagnostic.vault.invalid-amount";
    private static final String ACCOUNT_NOT_AVAILABLE =
            "diagnostic.vault.settlement.account-not-available";
    private static final String ACCOUNT_CHECK_FAILURE =
            "diagnostic.vault.settlement.account-check-failure";
    private static final String MAIN_THREAD_REQUIRED = "diagnostic.vault.main-thread-required";
    private static final String EMPTY_RESPONSE = "log.vault.settlement.empty-response";
    private final Server server;
    private final String accountName;
    private final UUID accountId;
    private final OfflinePlayer account;
    private final boolean boundAccount;
    private final int scale;
    private final BiFunction<String, Map<String, ?>, String> messageResolver;

    public VaultSettlementService(Server server, String accountName, int configuredScale) {
        this(server, accountName, configuredScale, VaultSettlementService::fallbackMessage);
    }

    public VaultSettlementService(Server server, String accountName, int configuredScale,
                                  BiFunction<String, Map<String, ?>, String> messageResolver) {
        this(server, accountName, configuredScale, messageResolver, null);
    }

    public VaultSettlementService(Server server, String accountName, int configuredScale,
                                  BiFunction<String, Map<String, ?>, String> messageResolver,
                                  UUID existingAccountId) {
        this.server = Objects.requireNonNull(server, "server");
        this.messageResolver = Objects.requireNonNull(messageResolver, "messageResolver");
        this.boundAccount = existingAccountId != null;
        if (accountName == null || accountName.isBlank()) {
            throw new IllegalArgumentException(resolveMessage(ACCOUNT_NAME_REQUIRED, Map.of()));
        }
        try {
            this.account = boundAccount ? server.getOfflinePlayer(existingAccountId) : server.getOfflinePlayer(accountName);
            this.accountId = account.getUniqueId();
            this.accountName = boundAccount || account.getName() == null ? accountName : account.getName();
        } catch (RuntimeException | LinkageError exception) {
            throw unavailable(ACCOUNT_RESOLVE_FAILURE, exception);
        }
        Economy economy = economy();
        int providerScale;
        try {
            providerScale = economy.fractionalDigits();
        } catch (RuntimeException | LinkageError exception) {
            throw unavailable(SCALE_READ_FAILURE, exception);
        }
        this.scale = providerScale >= 0 ? Math.min(providerScale, 8) : configuredScale;
        if (scale < 0 || scale > 8) {
            throw new IllegalArgumentException(resolveMessage(SCALE_RANGE, Map.of()));
        }
    }

    public Result ensureAccount() {
        requireMainThread();
        try {
            Economy economy = economy();
            if (economy.hasAccount(account)) {
                return Result.success(resolveMessage(ACCOUNT_READY, Map.of()));
            }
            if (!boundAccount && economy.createPlayerAccount(account) && economy.hasAccount(account)) {
                return Result.success(resolveMessage(ACCOUNT_READY, Map.of()));
            }
            return Result.failure(resolveMessage(ACCOUNT_CREATE_FAILURE,
                    Map.of("account", safeText(accountName))), false, false);
        } catch (AvailabilityException exception) {
            return Result.failure(exception.getMessage(), false, false);
        } catch (RuntimeException | LinkageError exception) {
            return Result.failure(resolveMessage(ACCOUNT_INITIALIZATION_FAILURE,
                    Map.of("detail", safeMessage(exception))), false, false);
        }
    }

    public long balanceMinor() {
        requireMainThread();
        Economy economy = readyEconomy();
        try {
            double balance = economy.getBalance(account);
            return amount(balance).minorUnits();
        } catch (RuntimeException | LinkageError exception) {
            throw unavailable(BALANCE_READ_FAILURE, exception);
        }
    }

    public Result checkAvailability() {
        requireMainThread();
        try {
            readyEconomy();
            return Result.success(resolveMessage(ACCOUNT_AVAILABLE, Map.of()));
        } catch (RuntimeException | LinkageError exception) {
            return Result.failure(resolveMessage(ACCOUNT_UNAVAILABLE,
                    Map.of("detail", safeMessage(exception))), false, false);
        }
    }

    public Result transferFromPlayer(OfflinePlayer player, long amountMinor) {
        requireMainThread();
        if (amountMinor <= 0) {
            throw new IllegalArgumentException(resolveMessage(DONATION_AMOUNT_POSITIVE, Map.of()));
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
            return ambiguousFailure(PLAYER_DEBIT_AMBIGUOUS, exception);
        }
        if (withdrawn == null) {
            return ambiguousFailure(EMPTY_RESPONSE);
        }
        if (!withdrawn.transactionSuccess()) {
            return Result.failure(resolveMessage(PLAYER_DEBIT_FAILURE,
                    Map.of("detail", safeResponseDetail(withdrawn))), false, false);
        }
        EconomyResponse deposited;
        try {
            deposited = economy.depositPlayer(account, amount);
        } catch (RuntimeException | LinkageError exception) {
            return ambiguousFailure(SETTLEMENT_CREDIT_AMBIGUOUS, exception);
        }
        if (deposited == null) {
            return ambiguousFailure(EMPTY_RESPONSE);
        }
        if (deposited.transactionSuccess()) {
            return Result.success(resolveMessage(FUNDS_TRANSFERRED, Map.of()));
        }
        EconomyResponse refund;
        try {
            refund = economy.depositPlayer(player, amount);
        } catch (RuntimeException | LinkageError exception) {
            return ambiguousFailure(PLAYER_REFUND_AMBIGUOUS, exception);
        }
        if (refund == null) {
            return ambiguousFailure(EMPTY_RESPONSE);
        }
        boolean refunded = refund.transactionSuccess();
        return refunded
                ? Result.failure(resolveMessage(SETTLEMENT_CREDIT_FAILURE,
                Map.of("detail", safeResponseDetail(deposited))), true, false)
                : Result.playerRefundRequired(resolveMessage(SETTLEMENT_CREDIT_FAILURE,
                Map.of("detail", safeResponseDetail(deposited))));
    }

    public Result refundDebitedPlayer(OfflinePlayer player, long amountMinor) {
        requireMainThread();
        if (amountMinor <= 0) {
            throw new IllegalArgumentException(resolveMessage(REFUND_AMOUNT_POSITIVE, Map.of()));
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
            return ambiguousFailure(PLAYER_REFUND_AMBIGUOUS, exception);
        }
        if (refunded == null) {
            return ambiguousFailure(EMPTY_RESPONSE);
        }
        return refunded.transactionSuccess()
                ? Result.success(resolveMessage(PLAYER_DEBIT_REFUNDED, Map.of()))
                : Result.failure(resolveMessage(PLAYER_REFUND_FAILURE,
                Map.of("detail", safeResponseDetail(refunded))), false, false);
    }

    public Result transferToPlayer(OfflinePlayer player, long amountMinor) {
        requireMainThread();
        if (amountMinor <= 0) {
            throw new IllegalArgumentException(resolveMessage(RETURN_AMOUNT_POSITIVE, Map.of()));
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
            return ambiguousFailure(SETTLEMENT_DEBIT_AMBIGUOUS, exception);
        }
        if (withdrawn == null) {
            return ambiguousFailure(EMPTY_RESPONSE);
        }
        if (!withdrawn.transactionSuccess()) {
            return Result.failure(resolveMessage(SETTLEMENT_DEBIT_FAILURE,
                    Map.of("detail", safeResponseDetail(withdrawn))), false, false);
        }
        EconomyResponse deposited;
        try {
            deposited = economy.depositPlayer(player, amount);
        } catch (RuntimeException | LinkageError exception) {
            return ambiguousFailure(PLAYER_CREDIT_AMBIGUOUS, exception);
        }
        if (deposited == null) {
            return ambiguousFailure(EMPTY_RESPONSE);
        }
        if (deposited.transactionSuccess()) {
            return Result.success(resolveMessage(FUNDS_RETURNED, Map.of()));
        }
        EconomyResponse compensation;
        try {
            compensation = economy.depositPlayer(account, amount);
        } catch (RuntimeException | LinkageError exception) {
            return ambiguousFailure(SETTLEMENT_COMPENSATION_AMBIGUOUS, exception);
        }
        if (compensation == null) {
            return ambiguousFailure(EMPTY_RESPONSE);
        }
        boolean compensated = compensation.transactionSuccess();
        return Result.failure(resolveMessage(PLAYER_CREDIT_FAILURE,
                Map.of("detail", safeResponseDetail(deposited))), compensated, !compensated);
    }

    public Result adjustSettlement(long amountMinor) {
        requireMainThread();
        if (amountMinor == 0) {
            throw new IllegalArgumentException(resolveMessage(ADJUSTMENT_NON_ZERO, Map.of()));
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
            return ambiguousFailure(ACCOUNT_ADJUSTMENT_AMBIGUOUS, exception);
        }
        if (response == null) {
            return ambiguousFailure(EMPTY_RESPONSE);
        }
        return response.transactionSuccess()
                ? Result.success(resolveMessage(ACCOUNT_ADJUSTED, Map.of()))
                : Result.failure(resolveMessage(ACCOUNT_ADJUSTMENT_FAILURE,
                Map.of("detail", safeResponseDetail(response))), false, false);
    }

    public Result compensateSettlement(long appliedAmountMinor) {
        requireMainThread();
        return adjustSettlement(Math.negateExact(appliedAmountMinor));
    }

    public int scale() {
        return scale;
    }

    /**
     * Formats an amount stored in minor units for player-facing text.
     *
     * <p>Vault providers are Bukkit services and are not required to be thread-safe. Call this
     * method only from the Paper primary thread; database work must return to that thread before
     * it formats a value. A broken or unavailable provider never prevents a page from opening:
     * the precise numeric representation is used as a final fallback.</p>
     */
    public String formatMinor(long minorUnits) {
        requireMainThread();
        String numeric = numericAmount(minorUnits);
        Economy economy = null;
        try {
            economy = economy();
            String formatted = economy.format(decimal(minorUnits));
            if (formatted != null && !formatted.isBlank()) {
                return formatted;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Formatting is cosmetic. Preserve a deterministic value when an Economy provider
            // returns null or throws instead of making the caller's UI/command fail.
        }
        return numericWithCurrencyName(numeric, minorUnits, economy);
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
                throw unavailable(PROVIDER_UNAVAILABLE, Map.of());
            }
            return registration.getProvider();
        } catch (AvailabilityException exception) {
            throw exception;
        } catch (RuntimeException | LinkageError exception) {
            throw unavailable(PROVIDER_PROBE_FAILURE, exception);
        }
    }

    private Economy readyEconomy() {
        Economy economy = economy();
        requireAccount(economy);
        return economy;
    }

    private MoneyAmount amount(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(resolveMessage(INVALID_AMOUNT, Map.of()));
        }
        return MoneyAmount.rounded(BigDecimal.valueOf(value), scale, RoundingMode.HALF_UP);
    }

    private void requireAccount(Economy economy) {
        try {
            if (!economy.hasAccount(account)) {
                throw unavailable(ACCOUNT_NOT_AVAILABLE,
                        Map.of("account", safeText(accountName)));
            }
        } catch (AvailabilityException exception) {
            throw exception;
        } catch (RuntimeException | LinkageError exception) {
            throw unavailable(ACCOUNT_CHECK_FAILURE, exception);
        }
    }

    private double decimal(long minorUnits) {
        return BigDecimal.valueOf(minorUnits, scale).doubleValue();
    }

    private String numericAmount(long minorUnits) {
        return BigDecimal.valueOf(minorUnits, scale).toPlainString();
    }

    private String numericWithCurrencyName(String numeric, long minorUnits, Economy economy) {
        if (economy == null) {
            return numeric;
        }
        try {
            boolean singular = BigDecimal.valueOf(minorUnits, scale).abs()
                    .compareTo(BigDecimal.ONE) == 0;
            String currency = singular ? economy.currencyNameSingular()
                    : economy.currencyNamePlural();
            return currency == null || currency.isBlank() ? numeric : numeric + " " + currency;
        } catch (RuntimeException | LinkageError ignored) {
            return numeric;
        }
    }

    private void requireMainThread() {
        if (!server.isPrimaryThread()) {
            throw new IllegalStateException(resolveMessage(MAIN_THREAD_REQUIRED, Map.of()));
        }
    }

    private Result ambiguousFailure(String messageKey, Throwable throwable) {
        return Result.failure(resolveMessage(messageKey,
                Map.of("detail", safeMessage(throwable))), false, true);
    }

    private Result ambiguousFailure(String messageKey) {
        return Result.failure(resolveMessage(messageKey, Map.of()), false, true);
    }

    private AvailabilityException unavailable(String messageKey, Throwable throwable) {
        return new AvailabilityException(resolveMessage(messageKey,
                Map.of("detail", safeMessage(throwable))), throwable);
    }

    private AvailabilityException unavailable(String messageKey,
                                              Map<String, ?> placeholders) {
        return new AvailabilityException(resolveMessage(messageKey, placeholders));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        String detail = message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
        return safeText(detail);
    }

    private static String safeResponseDetail(EconomyResponse response) {
        String detail = response.errorMessage;
        return safeText(detail == null || detail.isBlank()
                ? "unknown-provider-error" : detail);
    }

    private static String safeText(String text) {
        return text == null ? "" : text.replace('&', '＆').replace('§', '�');
    }

    private String resolveMessage(String key, Map<String, ?> placeholders) {
        try {
            String resolved = messageResolver.apply(key, placeholders);
            return resolved == null || resolved.isBlank() ? key : resolved;
        } catch (RuntimeException | LinkageError exception) {
            return key + " " + placeholders;
        }
    }

    private static String fallbackMessage(String key, Map<String, ?> placeholders) {
        return key;
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
