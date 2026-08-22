package cn.tianji.town.integrations.vault;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static net.milkbowl.vault.economy.EconomyResponse.ResponseType.FAILURE;
import static net.milkbowl.vault.economy.EconomyResponse.ResponseType.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultSettlementServiceTest {
    @Test
    void exposesRecoverablePlayerDebitAndRestoresItAfterProviderRecovery() {
        AtomicReference<String> mode = new AtomicReference<>("compfail");
        AtomicReference<Double> playerBalance = new AtomicReference<>(10.0D);
        UUID settlementId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        OfflinePlayer settlementAccount = offlinePlayer(settlementId, "Tax");
        OfflinePlayer player = offlinePlayer(playerId, "Player");
        Economy economy = proxy(Economy.class, (ignored, method, arguments) -> switch (
                method.getName()) {
            case "isEnabled", "hasAccount" -> true;
            case "fractionalDigits" -> 2;
            case "withdrawPlayer" -> {
                assertSame(player, arguments[0]);
                playerBalance.updateAndGet(balance -> balance - (double) arguments[1]);
                yield response((double) arguments[1], SUCCESS, "");
            }
            case "depositPlayer" -> {
                OfflinePlayer target = (OfflinePlayer) arguments[0];
                double amount = (double) arguments[1];
                if (target.getUniqueId().equals(settlementId)
                        || mode.get().equals("compfail")) {
                    yield response(amount, FAILURE, "INJECTED_" + mode.get());
                }
                assertSame(player, target);
                playerBalance.updateAndGet(balance -> balance + amount);
                yield response(amount, SUCCESS, "");
            }
            default -> defaultValue(method.getReturnType());
        });
        VaultSettlementService settlement = settlement(economy, settlementAccount);

        VaultSettlementService.Result failed = settlement.transferFromPlayer(player, 100);

        assertFalse(failed.success());
        assertTrue(failed.compensationRequired());
        assertTrue(failed.playerRefundRequired());
        assertEquals(9.0D, playerBalance.get());

        mode.set("normal");
        assertTrue(settlement.refundDebitedPlayer(player, 100).success());
        assertEquals(10.0D, playerBalance.get());
    }

    @Test
    void usesOneStableOfflineIdentityForCreationAndAdjustments() {
        AtomicBoolean accountCreated = new AtomicBoolean();
        AtomicReference<OfflinePlayer> createdAccount = new AtomicReference<>();
        AtomicReference<OfflinePlayer> depositedAccount = new AtomicReference<>();
        Economy economy = proxy(Economy.class, (ignored, method, arguments) -> switch (
                method.getName()) {
            case "isEnabled" -> true;
            case "fractionalDigits" -> 2;
            case "hasAccount" -> {
                assertTrue(arguments[0] instanceof OfflinePlayer);
                yield accountCreated.get();
            }
            case "createPlayerAccount" -> {
                assertTrue(arguments[0] instanceof OfflinePlayer);
                createdAccount.set((OfflinePlayer) arguments[0]);
                accountCreated.set(true);
                yield true;
            }
            case "depositPlayer" -> {
                assertTrue(arguments[0] instanceof OfflinePlayer);
                depositedAccount.set((OfflinePlayer) arguments[0]);
                assertEquals(100.0D, (double) arguments[1]);
                yield new EconomyResponse(100.0D, 100.0D, SUCCESS, "");
            }
            default -> defaultValue(method.getReturnType());
        });
        Plugin provider = proxy(Plugin.class,
                (ignored, method, arguments) -> defaultValue(method.getReturnType()));
        RegisteredServiceProvider<Economy> registration = new RegisteredServiceProvider<>(
                Economy.class, economy, ServicePriority.Normal, provider);
        ServicesManager services = proxy(ServicesManager.class,
                (ignored, method, arguments) -> method.getName().equals("getRegistration")
                        ? registration : defaultValue(method.getReturnType()));
        UUID accountId = UUID.randomUUID();
        OfflinePlayer delegate = proxy(OfflinePlayer.class,
                (ignored, method, arguments) -> switch (method.getName()) {
            case "getName" -> "Tax";
            case "getUniqueId" -> accountId;
            default -> defaultValue(method.getReturnType());
        });
        Server server = proxy(Server.class, (ignored, method, arguments) -> switch (
                method.getName()) {
            case "getServicesManager" -> services;
            case "getOfflinePlayer" -> {
                assertEquals("tax", arguments[0]);
                yield delegate;
            }
            case "isPrimaryThread" -> true;
            default -> defaultValue(method.getReturnType());
        });

        VaultSettlementService settlement = new VaultSettlementService(server, "tax", 2);

        assertTrue(settlement.ensureAccount().success());
        assertTrue(settlement.adjustSettlement(10_000).success());
        assertEquals("Tax", settlement.accountName());
        assertEquals(accountId, settlement.accountId());
        assertSame(delegate, createdAccount.get());
        assertSame(createdAccount.get(), depositedAccount.get());
    }

    @Test
    void reportsProviderOutageWithoutThrowingOrCallingTheProvider() {
        AtomicBoolean depositCalled = new AtomicBoolean();
        Economy economy = proxy(Economy.class, (ignored, method, arguments) -> switch (
                method.getName()) {
            case "isEnabled", "hasAccount" -> true;
            case "fractionalDigits" -> 2;
            case "depositPlayer" -> {
                depositCalled.set(true);
                yield new EconomyResponse(1.0D, 1.0D, SUCCESS, "");
            }
            default -> defaultValue(method.getReturnType());
        });
        Plugin provider = proxy(Plugin.class,
                (ignored, method, arguments) -> defaultValue(method.getReturnType()));
        AtomicReference<RegisteredServiceProvider<Economy>> registration = new AtomicReference<>(
                new RegisteredServiceProvider<>(Economy.class, economy, ServicePriority.Normal,
                        provider));
        ServicesManager services = proxy(ServicesManager.class,
                (ignored, method, arguments) -> method.getName().equals("getRegistration")
                        ? registration.get() : defaultValue(method.getReturnType()));
        OfflinePlayer account = proxy(OfflinePlayer.class,
                (ignored, method, arguments) -> switch (method.getName()) {
            case "getName" -> "Tax";
            case "getUniqueId" -> UUID.randomUUID();
            default -> defaultValue(method.getReturnType());
        });
        Server server = proxy(Server.class, (ignored, method, arguments) -> switch (
                method.getName()) {
            case "getServicesManager" -> services;
            case "getOfflinePlayer" -> account;
            case "isPrimaryThread" -> true;
            default -> defaultValue(method.getReturnType());
        });
        VaultSettlementService settlement = new VaultSettlementService(server, "tax", 2);

        registration.set(null);

        VaultSettlementService.Result availability = settlement.checkAvailability();
        VaultSettlementService.Result adjustment = settlement.adjustSettlement(100);
        assertFalse(availability.success());
        assertFalse(adjustment.success());
        assertTrue(adjustment.message().contains("provider 不可用"));
        assertFalse(depositCalled.get());
    }

    @Test
    void isolatesProviderLinkageErrorsAndMarksMutatingCallsAmbiguous() {
        OfflinePlayer settlementAccount = offlinePlayer(UUID.randomUUID(), "Tax");
        OfflinePlayer player = offlinePlayer(UUID.randomUUID(), "Player");
        AtomicReference<String> failingMethod = new AtomicReference<>("hasAccount");
        Economy economy = proxy(Economy.class, (ignored, method, arguments) -> {
            if (method.getName().equals(failingMethod.get())) {
                throw new NoSuchMethodError("INJECTED_" + method.getName());
            }
            return switch (method.getName()) {
                case "isEnabled", "hasAccount" -> true;
                case "fractionalDigits" -> 2;
                default -> defaultValue(method.getReturnType());
            };
        });
        VaultSettlementService settlement = settlement(economy, settlementAccount);

        VaultSettlementService.Result availability = settlement.checkAvailability();

        assertFalse(availability.success());
        assertTrue(availability.message().contains("hasAccount"));

        failingMethod.set("withdrawPlayer");
        VaultSettlementService.Result mutation = settlement.transferFromPlayer(player, 100);

        assertFalse(mutation.success());
        assertTrue(mutation.compensationRequired());
        assertFalse(mutation.playerRefundRequired());
        assertTrue(mutation.message().contains("人工复核"));
    }

    private static VaultSettlementService settlement(Economy economy,
                                                       OfflinePlayer settlementAccount) {
        Plugin provider = proxy(Plugin.class,
                (ignored, method, arguments) -> defaultValue(method.getReturnType()));
        RegisteredServiceProvider<Economy> registration = new RegisteredServiceProvider<>(
                Economy.class, economy, ServicePriority.Normal, provider);
        ServicesManager services = proxy(ServicesManager.class,
                (ignored, method, arguments) -> method.getName().equals("getRegistration")
                        ? registration : defaultValue(method.getReturnType()));
        Server server = proxy(Server.class, (ignored, method, arguments) -> switch (
                method.getName()) {
            case "getServicesManager" -> services;
            case "getOfflinePlayer" -> settlementAccount;
            case "isPrimaryThread" -> true;
            default -> defaultValue(method.getReturnType());
        });
        return new VaultSettlementService(server, "tax", 2);
    }

    private static OfflinePlayer offlinePlayer(UUID playerId, String playerName) {
        return proxy(OfflinePlayer.class, (ignored, method, arguments) -> switch (
                method.getName()) {
            case "getUniqueId" -> playerId;
            case "getName" -> playerName;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static EconomyResponse response(double amount,
                                             EconomyResponse.ResponseType type,
                                             String error) {
        return new EconomyResponse(amount, 0.0D, type, error);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        return 0.0D;
    }
}
