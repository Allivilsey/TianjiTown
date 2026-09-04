package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.economy.MoneyAmount;
import org.allivlisey.tianjitown.core.land.ExpansionPricing;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.allivlisey.tianjitown.paper.TownUiPresentation.MenuItem;

/** Loads town finances and ledgers, and handles tax changes and donations. */
final class TownFinanceDialogs {
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;

    TownFinanceDialogs(TownUiLegacyFacade facade) {
        this.facade = facade;
        this.plugin = facade.plugin();
        this.runtime = facade.runtime();
        this.actions = facade.actions();
    }

    void openFinance(Player player, int page) {
        runtime.read(player, () -> {
            EconomyRepository.TownFinance account = runtime.finance()
                    .findFinanceByPlayer(player.getUniqueId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            plugin.messages().plainText("chat.runtime.town-required")));
            return new FinanceView(account, runtime.quickShopSubsidyQuota(account.townId()));
        }, view -> renderFinance(player, view));
    }

    private void renderFinance(Player player, FinanceView view) {
        EconomyRepository.TownFinance account = view.account();
        List<String> summary = new ArrayList<>(List.of(
                facade.dialogText("common.town", Map.of("town", TownUiLegacyFacade.safeText(account.townName()))),
                facade.dialogText("finance.balance", Map.of("balance",
                        TownUiLegacyFacade.safeText(runtime.money(account.balanceMinor())))),
                facade.dialogText("finance.tax-rate", Map.of("rate",
                        TownUiLegacyFacade.safeText(TownRuntime.percent(account.taxRateBps())))),
                facade.dialogText("common.territory-units", Map.of("count", account.unitCount(),
                        "maximum", runtime.economySettings().maximumUnits())),
                facade.dialogText("finance.subsidy-twelve-hour", Map.of("amount", TownUiLegacyFacade.safeText(
                                runtime.money(view.subsidyQuota().twelveHourRemainingMinor())),
                        "refresh", TownUiLegacyFacade.safeText(view.subsidyQuota().twelveHourRefreshAt()))),
                facade.dialogText("finance.subsidy-week", Map.of("amount", TownUiLegacyFacade.safeText(
                                runtime.money(view.subsidyQuota().weeklyRemainingMinor())),
                        "refresh", TownUiLegacyFacade.safeText(view.subsidyQuota().weeklyRefreshAt())))));
        if (account.locked()) {
            summary.add(facade.dialogText("finance.locked", Map.of("reason",
                    TownUiLegacyFacade.safeText(account.lockReason()))));
        }
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(4, facade.button(account.locked() ? Material.REDSTONE_BLOCK
                : Material.EMERALD_BLOCK, facade.dialogText("common.finance-title"), summary, null, null)));
        if (runtime.consumptionEnabled()) {
            items.add(new MenuItem(10, facade.button(Material.SUNFLOWER,
                    facade.dialogText("finance.donation"),
                    List.of(facade.dialogText("tooltip.finance.donation"),
                            facade.dialogText("tooltip.finance.donation-vault")),
                    "DONATION_INPUT", null)));
        }
        if (account.role().equals("MAYOR") && runtime.taxEnabled()) {
            items.add(new MenuItem(12, facade.button(Material.GOLD_NUGGET,
                    facade.dialogText("finance.tax"),
                    List.of(facade.dialogText("tooltip.finance.tax")), "TAX_MENU", null)));
        }
        items.add(new MenuItem(14, facade.button(Material.WRITTEN_BOOK,
                facade.dialogText("finance.ledger"),
                List.of(facade.dialogText("tooltip.finance.ledger"),
                        facade.dialogText("tooltip.finance.ledger-subsidy")), "LEDGER", "0")));
        items.add(new MenuItem(15, facade.button(Material.BREWING_STAND, facade.dialogText("finance.buff"),
                List.of(runtime.buffs().buffShopEnabled()
                                ? facade.dialogText("tooltip.finance.buff-active")
                                : facade.dialogText("tooltip.finance.buff-paused")),
                "BUFF_SHOP", null)));
        if (account.role().equals("MAYOR") && runtime.consumptionEnabled()) {
            long expansionPriceMinor = ExpansionPricing.price(
                    runtime.economySettings().expansionCost(), runtime.settlement().scale())
                    .minorUnits();
            items.add(new MenuItem(16, facade.button(Material.FILLED_MAP, facade.dialogText("finance.expansion"),
                    List.of(facade.dialogText("tooltip.finance.expansion", Map.of("price",
                            TownUiLegacyFacade.safeText(runtime.money(expansionPriceMinor))))),
                    "EXPANSION_MENU", null)));
        }
        facade.openMenu(player, 27, facade.dialogText("common.finance-title"),
                new DialogRoute("MAIN", null), items);
        if (account.hasUnreadTaxChange()) {
            actions.acknowledgeTaxRevision(player, account.taxRevision(), outcome ->
                    facade.handleOutcome(player, outcome, ignored -> {
                    }));
        }
    }

    void openTaxMenu(Player player) {
        runtime.read(player, () -> runtime.finance().findFinanceByPlayer(player.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-required"))), account -> {
            boolean editable = account.role().equals("MAYOR") && runtime.taxEnabled();
            ItemStack summary = facade.button(Material.GOLD_INGOT, facade.dialogText("tax.summary-title"),
                    List.of(facade.dialogText("tax.current-rate", Map.of("rate",
                                    TownUiLegacyFacade.safeText(TownRuntime.percent(account.taxRateBps())))),
                            facade.dialogText("tax.scope"),
                            editable ? facade.dialogText("tax.editable-hint")
                                    : facade.dialogText("tax.readonly-hint")),
                    null, null);
            if (!editable) {
                facade.openMenu(player, 27, facade.dialogText("tax.menu-title"),
                        new DialogRoute("FINANCE", "0"),
                        List.of(new MenuItem(0, summary)));
                return;
            }
            DialogInput input = DialogInput.numberRange("tax_rate", 360,
                    facade.dialogComponent("tax.rate-label"),
                    facade.dialogFormat("tax.rate-format"), 5.0F,
                    runtime.economySettings().maximumTaxBps() / 100.0F,
                    account.taxRateBps() / 100.0F, 1.0F);
            facade.openDialogPage(player, facade.dialogText("tax.title"), List.of(facade.dialogTextBody(summary)),
                    List.of(input),
                    DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, session ->
                            DialogType.multiAction(List.of(
                                            ActionButton.create(facade.dialogComponent("common.save-changes"),
                                                    facade.dialogComponent("tax.save-tooltip"),
                                                    170, facade.dialogAction(player, session,
                                                            response -> applyTaxDialog(player, account, response))),
                                            ActionButton.create(facade.dialogComponent("common.cancel"),
                                                    null, 170,
                                                    facade.dialogAction(player, session, "FINANCE", "0"))))
                                    .exitAction(facade.returnButton(player, session,
                                            new DialogRoute("FINANCE", "0")))
                                    .columns(2).build(), new DialogRoute("FINANCE", "0"));
        });
    }

    private void applyTaxDialog(Player player, EconomyRepository.TownFinance account,
                                DialogResponseView response) {
        Float selected = response.getFloat("tax_rate");
        if (selected == null) {
            facade.openNotice(player, facade.dialogText("tax.select-title"),
                    facade.dialogText("tax.select-message"), facade.dialogText("common.back"),
                    "TAX_MENU", null);
            return;
        }
        int rate = Math.round(selected) * 100;
        if (rate == account.taxRateBps()) {
            facade.openNotice(player, facade.dialogText("tax.unchanged-title"),
                    facade.dialogText("tax.unchanged-message", Map.of(
                            "rate", TownRuntime.percent(rate))),
                    facade.dialogText("common.back"), "FINANCE", "0");
            return;
        }
        actions.changeTaxRate(player, account.townId(), rate, outcome ->
                facade.handleOutcome(player, outcome, change -> {
                    notifyTaxRateChange(change);
                    facade.openNotice(player, facade.dialogText("tax.saved-title"),
                            facade.dialogText("tax.saved-message", Map.of(
                                    "rate", TownRuntime.percent(change.basisPoints()))),
                            facade.dialogText("common.back"), "FINANCE", "0");
                }));
    }

    void openLedger(Player player, int page) {
        runtime.read(player, () -> {
            EconomyRepository.TownFinance account = runtime.finance()
                    .findFinanceByPlayer(player.getUniqueId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            plugin.messages().plainText("chat.runtime.town-required")));
            List<EconomyRepository.LedgerEntry> entries = runtime.finance()
                    .displayLedger(account.townId(), page, 6);
            return new LedgerPage(account, entries, page);
        }, ledger -> renderLedger(player, ledger));
    }

    private void renderLedger(Player player, LedgerPage ledger) {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(0, facade.button(Material.WRITTEN_BOOK,
                facade.dialogText("ledger.title", Map.of("town",
                        TownUiLegacyFacade.safeText(ledger.account().townName()))),
                List.of(facade.dialogText("ledger.page", Map.of("page", ledger.page() + 1)),
                        facade.dialogText("ledger.balance", Map.of("balance",
                                TownUiLegacyFacade.safeText(runtime.money(ledger.account().balanceMinor()))))),
                null, null)));
        if (ledger.entries().isEmpty()) {
            items.add(new MenuItem(1, facade.button(Material.PAPER,
                    facade.dialogText("ledger.empty"),
                    List.of(facade.dialogText("ledger.empty-hint")), null, null)));
        }
        int slot = 1;
        for (EconomyRepository.LedgerEntry entry : ledger.entries()) {
            boolean income = entry.amountMinor() > 0;
            String amount = facade.dialogText(income ? "ledger.income-amount" : "ledger.expense-amount",
                    Map.of("amount", TownUiLegacyFacade.safeText(runtime.money(Math.abs(entry.amountMinor())))));
            String actor = displayActorName(entry);
            if (actor == null) {
                String actorId = entry.actorId() == null ? "" : entry.actorId().toString()
                        .replace("-", "");
                String suffix = actorId.length() > 24 ? actorId.substring(24) : actorId;
                actor = facade.dialogText("common.unknown-player", Map.of("playerId", suffix));
            }
            items.add(new MenuItem(slot++, facade.button(income ? Material.LIME_DYE : Material.RED_DYE,
                    facade.dialogText("ledger.entry-title", Map.of("amount", amount,
                            "type", ledgerLabel(entry.entryType()))),
                    List.of(facade.dialogText("ledger.entry-balance", Map.of("balance",
                                    TownUiLegacyFacade.safeText(runtime.money(entry.balanceAfterMinor())))),
                            facade.dialogText("ledger.entry-actor", Map.of("actor", actor)),
                            facade.dialogText("ledger.entry-time", Map.of("time",
                                    TownUiLegacyFacade.safeText(entry.createdAt()))),
                            facade.dialogText("ledger.entry-note", Map.of("note",
                                    TownUiLegacyFacade.safeText(entry.note())))), null, null)));
        }
        if (ledger.page() > 0) {
            items.add(new MenuItem(20, facade.button(Material.ARROW, facade.dialogText("common.previous"),
                    List.of(), "LEDGER", String.valueOf(ledger.page() - 1))));
        }
        if (ledger.entries().size() == 6) {
            items.add(new MenuItem(21, facade.button(Material.ARROW, facade.dialogText("common.next"),
                    List.of(), "LEDGER", String.valueOf(ledger.page() + 1))));
        }
        facade.openMenu(player, 27, facade.dialogText("ledger.page-title", Map.of("page", ledger.page() + 1)),
                new DialogRoute("FINANCE", "0"), items);
    }

    private String ledgerLabel(String type) {
        String stableType = String.valueOf(type);
        String key = switch (stableType) {
            case "QUICKSHOP_TAX" -> "ledger.type.quickshop-tax";
            case "JOBS_TAX" -> "ledger.type.jobs-tax";
            case "GLOBALMARKETPLUS_TAX" -> "ledger.type.global-market-plus-tax";
            case "SERVER_TAX_SUBSIDY" -> "ledger.type.server-tax-subsidy";
            case "APPLICATION_FEE" -> "ledger.type.application-fee";
            case "DONATION" -> "ledger.type.donation";
            case "EXPANSION" -> "ledger.type.expansion";
            case "EXPANSION_REFUND" -> "ledger.type.expansion-refund";
            case "ADMIN_ADJUSTMENT" -> "ledger.type.admin-adjustment";
            case "BUFF_PURCHASE" -> "ledger.type.buff-purchase";
            case "BUFF_REFUND" -> "ledger.type.buff-refund";
            case "RESOURCE_PURCHASE" -> "ledger.type.resource-purchase";
            case "RESOURCE_REFUND" -> "ledger.type.resource-refund";
            default -> null;
        };
        return key == null
                ? facade.dialogText("ledger.type.unknown", Map.of("type", TownUiLegacyFacade.safeText(stableType)))
                : facade.dialogText(key);
    }

    private static String displayActorName(EconomyRepository.LedgerEntry entry) {
        if (entry.actorId() == null) {
            return TownUiLegacyFacade.safeText(entry.actorName());
        }
        try {
            UUID stored = UUID.fromString(entry.actorName());
            if (!stored.equals(entry.actorId())) {
                return TownUiLegacyFacade.safeText(entry.actorName());
            }
            String current = Bukkit.getOfflinePlayer(entry.actorId()).getName();
            return current == null || current.isBlank() ? null : TownUiLegacyFacade.safeText(current);
        } catch (IllegalArgumentException ignored) {
            return TownUiLegacyFacade.safeText(entry.actorName());
        }
    }

    private void notifyTaxRateChange(EconomyRepository.TaxChange change) {
        runtime.read(Bukkit.getConsoleSender(),
                () -> runtime.repository().listMemberIds(change.townId()), memberIds -> {
            for (UUID memberId : memberIds) {
                Player member = Bukkit.getPlayer(memberId);
                if (member == null) {
                    continue;
                }
                member.sendMessage(plugin.messages().component("chat.notification.tax-updated", Map.of(
                                "rate", TownRuntime.percent(change.basisPoints())))
                        .append(facade.callbackButton(member, "chat.buttons.view-tax",
                                () -> openTaxMenu(member)))
                        .append(Component.space())
                        .append(facade.callbackButton(member, "chat.buttons.view-finance",
                                () -> openFinance(member, 0))));
                facade.playSound(member, Sound.BLOCK_BELL_USE);
            }
        });
    }

    void startDonationInput(Player player) {
        if (!runtime.consumptionEnabled()) {
            facade.openNotice(player, facade.dialogText("donation.unavailable-title"),
                    facade.dialogText("donation.unavailable-message"),
                    facade.dialogText("common.back"), "FINANCE", "0");
            return;
        }
        openDonationDialog(player, null, "");
    }

    private void openDonationDialog(Player player, String error, String initial) {
        runtime.read(player, () -> runtime.finance().findFinanceByPlayer(player.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-required"))), account -> {
            List<String> description = new ArrayList<>(List.of(
                    facade.dialogText("common.town", Map.of("town", TownUiLegacyFacade.safeText(account.townName()))),
                    facade.dialogText("donation.balance", Map.of(
                            "balance", TownUiLegacyFacade.safeText(runtime.money(account.balanceMinor())))),
                    facade.dialogText("donation.amount-hint", Map.of(
                            "scale", runtime.settlement().scale()))));
            if (error != null && !error.isBlank()) {
                description.add(facade.dialogText("common.error", Map.of("error", TownUiLegacyFacade.safeText(error))));
            }
            ItemStack summary = facade.button(Material.SUNFLOWER,
                    facade.dialogText("donation.title"),
                    description, null, null);
            DialogInput amount = DialogInput.text("donation_amount", 360,
                    facade.dialogComponent("donation.amount-label"), true,
                    initial, 64, null);
            facade.openDialogPage(player, facade.dialogText("donation.title"),
                    List.of(facade.dialogTextBody(summary)), List.of(amount),
                    DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, session ->
                            DialogType.multiAction(List.of(
                                            ActionButton.create(facade.dialogComponent("donation.confirm"),
                                                    facade.dialogComponent("donation.confirm-tooltip"),
                                                    170, facade.dialogAction(player, session,
                                                            response -> applyDonationDialog(player, response))),
                                            ActionButton.create(facade.dialogComponent("common.cancel"),
                                                    null, 170,
                                                    facade.dialogAction(player, session, "FINANCE", "0"))))
                                    .exitAction(facade.returnButton(player, session,
                                            new DialogRoute("FINANCE", "0")))
                                    .columns(2).build(), new DialogRoute("FINANCE", "0"));
        });
    }

    private void applyDonationDialog(Player player, DialogResponseView response) {
        String value = Objects.requireNonNullElse(response.getText("donation_amount"), "").strip();
        try {
            BigDecimal decimal = new BigDecimal(value);
            MoneyAmount amount = MoneyAmount.from(decimal, runtime.settlement().scale());
            if (!amount.positive()) {
                throw new IllegalArgumentException(plugin.messages().plainText(
                        "validation.vault.donation-amount-positive"));
            }
            actions.donate(player, amount.minorUnits(), outcome ->
                    facade.handleOutcome(player, outcome, mutation ->
                            facade.openNotice(player, facade.dialogText("notice.donation-success-title"),
                                    facade.dialogText("notice.donation-success-message", Map.of(
                                            "amount", runtime.money(amount.minorUnits()),
                                            "balance", runtime.money(
                                                    mutation.balanceAfterMinor()))),
                                    facade.dialogText("common.back"), "FINANCE", "0")));
        } catch (ArithmeticException | NumberFormatException exception) {
            openDonationDialog(player, plugin.messages().plainText(
                    "dialog.donation.invalid-amount"), value);
        } catch (IllegalArgumentException exception) {
            openDonationDialog(player, TownUiLegacyFacade.safeText(TownUiLegacyFacade.safeMessage(exception)), value);
        }
    }

    private record FinanceView(EconomyRepository.TownFinance account,
                               EconomyRepository.SubsidyQuota subsidyQuota) {
    }

    private record LedgerPage(EconomyRepository.TownFinance account,
                              List<EconomyRepository.LedgerEntry> entries, int page) {
    }
}
