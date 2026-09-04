package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.economy.MoneyAmount;
import org.allivlisey.tianjitown.core.consumption.BuffDefinition;
import org.allivlisey.tianjitown.core.consumption.BuffDurationOption;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.bukkit.command.CommandSender;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Handles administrator balance, tax, ledger and buff commands. */
final class TownAdminEconomyCommands {
    private final TownAdminCommand facade;
    private final TianjiTownPlugin plugin;

    TownAdminEconomyCommands(TownAdminCommand facade, TianjiTownPlugin plugin) {
        this.facade = facade;
        this.plugin = plugin;
    }

    boolean money(CommandSender sender, TownRuntime runtime, String[] args) {
        facade.requirePermission(sender, TownAdminPermissions.MONEY);
        facade.requireMessageLength(args, 2, "chat.admin.usage-money-root");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("reconcile")) {
            runtime.reconcileSettlement();
            facade.send(sender, "chat.admin.settlement-reconcile-submitted");
            return true;
        }
        facade.requireMessageLength(args, 3, "chat.admin.usage-money");
        if (action.equals("view")) {
            String townName = TownCommandParser.townName(args, 2);
            runtime.read(sender, () -> {
                TownSnapshot town = facade.requireTown(runtime, townName);
                return runtime.finance().findFinanceByTown(town.id()).orElseThrow();
            }, account -> facade.send(sender, "chat.admin.finance-balance", Map.of(
                    "town", account.townName(), "balance", runtime.money(account.balanceMinor()),
                    "locked", account.locked() ? "[LOCKED]" : "[READY]",
                    "reason", account.locked() ? account.lockReason() : "")));
            return true;
        }
        if (action.equals("adjust")) {
            facade.requireMessageLength(args, 5, "chat.admin.usage-money-adjust");
            runtime.read(sender, () -> {
                List<TownSnapshot> towns = runtime.repository().listTowns(true);
                TownCommandParser.NamedAmountReason parsed = TownCommandParser.namedAmountReason(
                        args, 2, TownAdminCommand.townNames(towns), plugin.messages()::plainText);
                TownSnapshot town = towns.stream().filter(candidate -> TownAdminCommand.sameName(
                                candidate.profile().name(), parsed.townName())).findFirst()
                        .orElseThrow(() -> facade.messageArgument("chat.admin.town-not-found-generic"));
                long amount = MoneyAmount.from(new BigDecimal(parsed.amount()),
                        runtime.settlement().scale()).minorUnits();
                if (amount == 0) {
                    throw facade.messageArgument("chat.admin.money-adjust-zero");
                }
                return new MoneyAdjustment(town.id(), amount, parsed.reason());
            }, request -> runtime.adjustFunds(sender, request.townId(), request.amountMinor(),
                    request.reason()));
            return true;
        }
        throw facade.messageArgument("chat.admin.money-action-unsupported");
    }

    boolean tax(CommandSender sender, TownRuntime runtime, String[] args) {
        facade.requirePermission(sender, TownAdminPermissions.TAX);
        facade.requireMessageLength(args, 5, "chat.admin.usage-tax");
        if (!args[1].equalsIgnoreCase("set")) {
            throw facade.messageArgument("chat.admin.tax-action-unsupported");
        }
        runtime.read(sender, () -> {
            List<TownSnapshot> towns = runtime.repository().listTowns(true);
            TownCommandParser.NamedAmountReason parsed = TownCommandParser.namedAmountReason(
                    args, 2, TownAdminCommand.townNames(towns), plugin.messages()::plainText);
            TownSnapshot town = towns.stream().filter(candidate -> TownAdminCommand.sameName(
                            candidate.profile().name(), parsed.townName())).findFirst()
                    .orElseThrow(() -> facade.messageArgument("chat.admin.town-not-found-generic"));
            BigDecimal percent = new BigDecimal(parsed.amount().replace("%", ""));
            int bps = percent.movePointRight(2).intValueExact();
            return new TaxAdjustment(town.id(), bps, parsed.reason());
        }, request -> runtime.forceTaxRate(sender, request.townId(), request.basisPoints(),
                request.reason()));
        return true;
    }

    boolean ledger(CommandSender sender, TownRuntime runtime, String[] args) {
        facade.requirePermission(sender, TownAdminPermissions.LEDGER);
        facade.requireMessageLength(args, 3, "chat.admin.usage-ledger");
        if (!args[1].equalsIgnoreCase("view")) {
            throw facade.messageArgument("chat.admin.ledger-action-unsupported");
        }
        String townName = TownCommandParser.townName(args, 2);
        runtime.read(sender, () -> {
            TownSnapshot town = facade.requireTown(runtime, townName);
            return runtime.finance().ledger(town.id(), 0, 45);
        }, entries -> {
            facade.send(sender, "chat.admin.ledger-title", Map.of("count", entries.size()));
            for (org.allivlisey.tianjitown.storage.economy.EconomyRepository.LedgerEntry entry : entries) {
                facade.send(sender, "chat.admin.ledger-record", Map.of("created", entry.createdAt(),
                        "type", entry.entryType(), "amount", runtime.money(entry.amountMinor()),
                        "balance", runtime.money(entry.balanceAfterMinor()),
                        "note", entry.note()));
            }
        });
        return true;
    }

    boolean buff(CommandSender sender, TownRuntime runtime, String[] args) {
        facade.requirePermission(sender, TownAdminPermissions.BUFF);
        facade.requireMessageLength(args, 2, "chat.admin.usage-buff-root");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            facade.requireMessageLength(args, 3, "chat.admin.usage-buff-list");
            String townName = TownCommandParser.townName(args, 2);
            runtime.read(sender, () -> {
                TownSnapshot town = facade.requireTown(runtime, townName);
                return runtime.buffs().repository().activeBuffsForTown(town.id(),
                        java.time.Instant.now());
            }, buffs -> {
                facade.send(sender, "chat.admin.buff-title", Map.of("count", buffs.size()));
                buffs.forEach(value -> facade.send(sender, "chat.admin.buff-record", Map.of(
                        "id", value.buffId(), "key", value.buffKey(), "level", value.level(),
                        "stacks", value.stackCount(), "expires", value.expiresAt())));
            });
            return true;
        }
        if (action.equals("grant")) {
            if (!runtime.buffs().buffShopEnabled() || !runtime.consumptionEnabled()) {
                throw facade.messageArgument("chat.admin.buff-purchase-paused");
            }
            facade.requireMessageLength(args, 5, "chat.admin.usage-buff-grant");
            runtime.read(sender, () -> {
                List<TownSnapshot> towns = runtime.repository().listTowns(true);
                TownCommandParser.NamedActionReason parsed = TownCommandParser.namedActionReason(
                        args, 2, TownAdminCommand.townNames(towns),
                        runtime.buffs().settings().buffs().keySet(),
                        plugin.messages()::plainText);
                TownSnapshot town = towns.stream().filter(candidate -> TownAdminCommand.sameName(
                                candidate.profile().name(), parsed.townName())).findFirst()
                        .orElseThrow(() -> facade.messageArgument("chat.admin.town-not-found-generic"));
                BuffDefinition definition = runtime.buffs().settings().requireBuff(
                        parsed.action().toLowerCase(Locale.ROOT));
                return new BuffGrantRequest(town.id(), town.profile().name(), definition,
                        parsed.reason());
            }, request -> facade.requestConfirmation(sender,
                    plugin.messages().text("chat.admin.buff-purchase-confirmation", Map.of(
                            "town", TownAdminCommand.safeText(request.townName()),
                            "buff", TownAdminCommand.safeText(runtime.buffs().settings().label(
                                    request.definition().key())))),
                    () -> runtime.write(sender, () -> runtime.buffs().repository()
                                    .purchaseBuffForTown(request.townId(), TownAdminCommand.actorId(sender),
                                            sender.getName(), request.definition(),
                                            runtime.buffs().settings().label(
                                                    request.definition().key()),
                                            runtime.settlement().scale(),
                                            BuffDurationOption.ONE_HOUR,
                            "admin-buff-purchase:" + UUID.randomUUID(),
                            java.time.Instant.now(), request.reason()),
                            purchase -> {
                                facade.send(sender, "chat.admin.buff-purchase-complete", Map.of(
                                        "balance", runtime.money(purchase.balanceAfterMinor())));
                                runtime.buffs().refreshAllPlayers();
                            })));
            return true;
        }
        throw facade.messageArgument("chat.admin.buff-action-unsupported");
    }

    private record MoneyAdjustment(UUID townId, long amountMinor, String reason) {
    }

    private record TaxAdjustment(UUID townId, int basisPoints, String reason) {
    }

    private record BuffGrantRequest(UUID townId, String townName,
                                    BuffDefinition definition, String reason) {
    }
}
