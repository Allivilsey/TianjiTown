package org.allivlisey.tianjitown.paper.command;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.core.economy.MoneyAmount;
import org.allivlisey.tianjitown.core.consumption.BuffDefinition;
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

    @Command("tianjitown money view")
    @Usage("/tianjitown money view <小镇代码>")
    @AdminAccess(TownAdminPermissions.ROOT)
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

    @Command("tianjitown money adjust")
    @Usage("/tianjitown money adjust <小镇代码> <带符号金额> [原因]")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void adjustMoney(CommandSender sender, TownRuntime runtime, String input) {
        runtime.read(sender, () -> {
            List<TownSnapshot> towns = runtime.repository().listTowns(true);
            TownCommandParser.NamedAmountReason parsed = TownCommandParser.namedAmountReason(
                    input.split(" "), 0, TownAdminCommand.townCodes(towns), plugin.messages()::plainText);
            TownSnapshot town = towns.stream().filter(candidate -> TownAdminCommand.sameCode(
                            candidate.profile().residenceName(), parsed.townName())).findFirst()
                    .orElseThrow(() -> facade.messageArgument("chat.admin.town-not-found-generic"));
            long amount = MoneyAmount.from(new BigDecimal(parsed.amount()),
                    runtime.wallet().scale()).minorUnits();
            if (amount == 0) {
                throw facade.messageArgument("chat.admin.money-adjust-zero");
            }
            return new MoneyAdjustment(town.id(), amount, parsed.reason());
        }, request -> runtime.adjustFunds(sender, request.townId(), request.amountMinor(),
                request.reason()));
    }

    @Command("tianjitown buff list")
    @Usage("/tianjitown buff list <小镇代码>")
    @AdminAccess(TownAdminPermissions.ROOT)
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
                    "expires", value.expiresAt())));
        });
    }

    @Command("tianjitown buff set")
    @Usage("/tianjitown buff set <小镇代码> <buffKey> [周数] [等级]")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void setBuff(CommandSender sender, TownRuntime runtime,
            @revxrsal.commands.annotation.Single String townCode,
            @revxrsal.commands.annotation.Single String buffKey,
            @revxrsal.commands.annotation.Default("1") @revxrsal.commands.annotation.Single String time,
            @revxrsal.commands.annotation.Default("1") @revxrsal.commands.annotation.Single String intensity) {
        int weeks = Integer.parseInt(time);
        int level = Integer.parseInt(intensity);
        BuffDefinition definition = runtime.buffs().settings().requireBuff(buffKey.toLowerCase(Locale.ROOT));
        org.allivlisey.tianjitown.core.consumption.BuffPricing.weeklyPrice(definition, weeks, level,
                runtime.wallet().scale());
        runtime.read(sender, () -> facade.requireTown(runtime, townCode), town ->
                runtime.buffs().setBuffAction(sender, town.id(), definition, weeks, level,
                        purchase -> facade.send(sender, "chat.admin.buff-set-complete", Map.of(
                                "balance", runtime.money(purchase.balanceAfterMinor())))));
    }

    @Command("tianjitown tax set")
    @Usage("/tianjitown tax set <小镇代码> <百分比> [原因]")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void tax(CommandSender sender, TownRuntime runtime, String input) {
        runtime.read(sender, () -> {
            List<TownSnapshot> towns = runtime.repository().listTowns(true);
            TownCommandParser.NamedAmountReason parsed = TownCommandParser.namedAmountReason(
                    input.split(" "), 0, TownAdminCommand.townCodes(towns), plugin.messages()::plainText);
            TownSnapshot town = towns.stream().filter(candidate -> TownAdminCommand.sameCode(
                            candidate.profile().residenceName(), parsed.townName())).findFirst()
                    .orElseThrow(() -> facade.messageArgument("chat.admin.town-not-found-generic"));
            BigDecimal percent = new BigDecimal(parsed.amount().replace("%", ""));
            int bps = percent.movePointRight(2).intValueExact();
            return new TaxAdjustment(town.id(), bps, parsed.reason());
        }, request -> runtime.forceTaxRate(sender, request.townId(), request.basisPoints(),
                request.reason()));
    }

    @Command("tianjitown ledger view")
    @Usage("/tianjitown ledger view <小镇代码>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void ledger(CommandSender sender, TownRuntime runtime, String input) {
        String townName = input.strip();
        runtime.read(sender, () -> {
            TownSnapshot town = facade.requireTown(runtime, townName);
            return runtime.finance().displayLedger(town.id(), 0, 45);
        }, entries -> {
            facade.send(sender, "chat.admin.ledger-title", Map.of("count", entries.size()));
            for (org.allivlisey.tianjitown.storage.economy.EconomyRepository.DisplayLedgerEntry entry : entries) {
                if (entry.summary() != null) {
                    var summary = entry.summary();
                    facade.send(sender, "chat.admin.ledger-summary", Map.of(
                            "start", summary.periodStart(), "end", summary.periodEnd(),
                            "type", plugin.messages().plainText("dialog.ledger.type."
                                    + (entry.entryType().equals("JOBS_INCOME") ? "jobs-income" : "shop-income")),
                            "amount", runtime.money(entry.amountMinor()),
                            "tax", runtime.money(summary.taxMinor()),
                            "subsidy", runtime.money(summary.subsidyMinor()),
                            "count", summary.transactionCount(),
                            "status", plugin.messages().plainText(java.time.Instant.now().isBefore(summary.periodEnd())
                                    ? "dialog.ledger.summary-current" : "dialog.ledger.summary-completed")));
                    continue;
                }
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

}
