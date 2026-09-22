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
import java.util.function.BiFunction;

/** Player wallet boundary. Town money is held exclusively in the town database. */
public final class VaultPlayerEconomyService {
    private static final String SCALE_RANGE = "validation.vault.scale-range";
    private static final String PLAYER_DEBIT_AMBIGUOUS =
            "log.vault.settlement.player-debit-ambiguous";
    private static final String PLAYER_DEBIT_FAILURE =
            "diagnostic.vault.settlement.player-debit-failure";
    private static final String PLAYER_CREDIT_AMBIGUOUS =
            "log.vault.settlement.player-credit-ambiguous";
    private static final String PLAYER_CREDIT_FAILURE =
            "diagnostic.vault.settlement.player-credit-failure";
    private static final String PROVIDER_UNAVAILABLE =
            "diagnostic.vault.provider-unavailable";
    private static final String PROVIDER_PROBE_FAILURE =
            "diagnostic.vault.provider-probe-failure";
    private static final String INVALID_AMOUNT = "diagnostic.vault.invalid-amount";
    private static final String MAIN_THREAD_REQUIRED = "diagnostic.vault.main-thread-required";
    private static final String EMPTY_RESPONSE = "log.vault.settlement.empty-response";
    private final Server server;
    private final int scale;
    private final BiFunction<String, Map<String, ?>, String> messageResolver;

    public VaultPlayerEconomyService(Server server, int configuredScale) {
        this(server, configuredScale, VaultPlayerEconomyService::fallbackMessage);
    }

    public VaultPlayerEconomyService(Server server, int configuredScale,
            BiFunction<String, Map<String, ?>, String> messageResolver) {
        this.server = Objects.requireNonNull(server, "server");
        this.messageResolver = Objects.requireNonNull(messageResolver, "messageResolver");
        int providerScale = economy().fractionalDigits();
        this.scale = providerScale >= 0 ? Math.min(providerScale, 8) : configuredScale;
        if (scale < 0 || scale > 8) throw new IllegalArgumentException(resolveMessage(SCALE_RANGE, Map.of()));
    }

    public Result checkAvailability() {
        requireMainThread();
        try {
            economy();
            return Result.success("Vault player economy available");
        } catch (RuntimeException | LinkageError exception) {
            return Result.failure(safeMessage(exception), false, false);
        }
    }

    public Result withdrawPlayer(OfflinePlayer player, long amountMinor) {
        return changePlayer(player, amountMinor, false);
    }

    public Result depositPlayer(OfflinePlayer player, long amountMinor) {
        return changePlayer(player, amountMinor, true);
    }

    public Result refundDebitedPlayer(OfflinePlayer player, long amountMinor) {
        return depositPlayer(player, amountMinor);
    }

    private Result changePlayer(OfflinePlayer player, long amountMinor, boolean credit) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        if (amountMinor <= 0) throw new IllegalArgumentException("金额必须大于 0");
        Economy provider;
        try {
            provider = economy();
        } catch (AvailabilityException exception) {
            return Result.failure(exception.getMessage(), false, false);
        }
        EconomyResponse response;
        try {
            response = credit ? provider.depositPlayer(player, decimal(amountMinor))
                    : provider.withdrawPlayer(player, decimal(amountMinor));
        } catch (RuntimeException | LinkageError exception) {
            return ambiguousFailure(credit ? PLAYER_CREDIT_AMBIGUOUS : PLAYER_DEBIT_AMBIGUOUS, exception);
        }
        if (response == null) return ambiguousFailure(EMPTY_RESPONSE);
        return response.transactionSuccess() ? Result.success("Player wallet updated")
                : Result.failure(resolveMessage(credit ? PLAYER_CREDIT_FAILURE : PLAYER_DEBIT_FAILURE,
                        Map.of("detail", safeResponseDetail(response))), false, false);
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

    private MoneyAmount amount(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(resolveMessage(INVALID_AMOUNT, Map.of()));
        }
        return MoneyAmount.rounded(BigDecimal.valueOf(value), scale, RoundingMode.HALF_UP);
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
