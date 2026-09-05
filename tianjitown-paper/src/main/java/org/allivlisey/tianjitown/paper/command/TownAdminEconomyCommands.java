package org.allivlisey.tianjitown.paper.command;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.core.economy.MoneyAmount;
import org.allivlisey.tianjitown.core.consumption.BuffDefinition;
import org.allivlisey.tianjitown.core.consumption.BuffDurationOption;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.bukkit.command.CommandSender;

import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Usage;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Handles administrator balance, tax, ledger and buff commands. */
public final class TownAdminEconomyCommands {
    private final TownAdminCommand facade;
    private final TianjiTownPlugin plugin;

    public TownAdminEconomyCommands(TownAdminCommand facade, TianjiTownPlugin plugin) {
        this.facade = facade;
        this.plugin = plugin;
    }

    @Command("townadmin money reconcile")
    @Usage("/townadmin money reconcile")
    @AdminAccess(TownAdminPermissions.MONEY)
    public void reconcileMoney(CommandSender sender, TownRuntime runtime) {
        runtime.reconcileSettlement();
        facade.send(sender, "chat.admin.settlement-reconcile-submitted");
    }

    @Command("townadmin money view")
    @Usage("/townadmin money view <小镇全名>")
    @AdminAccess(TownAdminPermissions.MONEY)
    public void viewMoney(CommandSender sender, TownRuntime runtime, String input) {
        String townName = input.strip();
        runtime.read(sender, () -> {
            TownSnapshot town = facade.requireTown(runtime, townName);
            return runtime.finance().findFinanceByTown(town.id()).orElseThrow();
        }, account -> facade.send(sender, "chat.admin.finance-balance", Map.of(
                "town", account.townName(), "balance", runtime.money(account.balanceMinor()),
                "locked", account.locked() ? "[LOCKED]" : "[READY]",
                "reason", account.locked() ? account.lockReason() : "")));
    }

    @Command("townadmin money adjust")
    @Usage("/townadmin money adjust <小镇全名> <带符号金额> <原因>")
    @AdminAccess(TownAdminPermissions.MONEY)
    public void adjustMoney(CommandSender sender, TownRuntime runtime, String input) {
        runtime.read(sender, () -> {
            List<TownSnapshot> towns = runtime.repository().listTowns(true);
            TownCommandParser.NamedAmountReason parsed = TownCommandParser.namedAmountReason(
                    input.split(" "), 0, TownAdminCommand.townNames(towns), plugin.messages()::plainText);
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
    }

    @Command("townadmin buff list")
    @Usage("/townadmin buff list <小镇全名>")
    @AdminAccess(TownAdminPermissions.BUFF)
    public void listBuffs(CommandSender sender, TownRuntime runtime, String input) {
        String townName = input.strip();
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
    }

    @Command("townadmin buff grant")
    @Usage("/townadmin buff grant <小镇全名> <buffKey> <原因>")
    @AdminAccess(TownAdminPermissions.BUFF)
    public void grantBuff(CommandSender sender, TownRuntime runtime, String input) {
        if (!runtime.buffs().buffShopEnabled() || !runtime.consumptionEnabled()) {
            throw facade.messageArgument("chat.admin.buff-purchase-paused");
        }
        runtime.read(sender, () -> {
            List<TownSnapshot> towns = runtime.repository().listTowns(true);
            TownCommandParser.NamedActionReason parsed = TownCommandParser.namedActionReason(
                    input.split(" "), 0, TownAdminCommand.townNames(towns),
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
                                        BuffDurationOption.ONE_WEEK,
                        "admin-buff-purchase:" + UUID.randomUUID(),
                        java.time.Instant.now(), request.reason()),
                        purchase -> {
                            facade.send(sender, "chat.admin.buff-purchase-complete", Map.of(
                                    "balance", runtime.money(purchase.balanceAfterMinor())));
                            runtime.buffs().refreshAllPlayers();
                        })));
    }

    @Command("townadmin tax set")
    @Usage("/townadmin tax set <小镇全名> <百分比> <原因>")
    @AdminAccess(TownAdminPermissions.TAX)
    public void tax(CommandSender sender, TownRuntime runtime, String input) {
        runtime.read(sender, () -> {
            List<TownSnapshot> towns = runtime.repository().listTowns(true);
            TownCommandParser.NamedAmountReason parsed = TownCommandParser.namedAmountReason(
                    input.split(" "), 0, TownAdminCommand.townNames(towns), plugin.messages()::plainText);
            TownSnapshot town = towns.stream().filter(candidate -> TownAdminCommand.sameName(
                            candidate.profile().name(), parsed.townName())).findFirst()
                    .orElseThrow(() -> facade.messageArgument("chat.admin.town-not-found-generic"));
            BigDecimal percent = new BigDecimal(parsed.amount().replace("%", ""));
            int bps = percent.movePointRight(2).intValueExact();
            return new TaxAdjustment(town.id(), bps, parsed.reason());
        }, request -> runtime.forceTaxRate(sender, request.townId(), request.basisPoints(),
                request.reason()));
    }

    @Command("townadmin ledger view")
    @Usage("/townadmin ledger view <小镇全名>")
    @AdminAccess(TownAdminPermissions.LEDGER)
    public void ledger(CommandSender sender, TownRuntime runtime, String input) {
        String townName = input.strip();
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
    }

    private record MoneyAdjustment(UUID townId, long amountMinor, String reason) {
    }

    private record TaxAdjustment(UUID townId, int basisPoints, String reason) {
    }

    private record BuffGrantRequest(UUID townId, String townName,
                                    BuffDefinition definition, String reason) {
    }
}
