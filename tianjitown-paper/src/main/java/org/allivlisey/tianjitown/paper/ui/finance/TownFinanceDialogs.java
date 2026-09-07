package org.allivlisey.tianjitown.paper.ui.finance;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.paper.action.TownActions;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

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

import org.allivlisey.tianjitown.paper.ui.TownUiPresentation.MenuItem;

/** Loads town finances and ledgers, and handles tax changes and donations. */
public final class TownFinanceDialogs {
    private final TownUiPresentation presentation;
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;

    public TownFinanceDialogs(TownUiLegacyFacade facade) {
        this.facade = facade;
        this.presentation = facade.presentation();
        this.plugin = facade.plugin();
        this.runtime = facade.runtime();
        this.actions = facade.actions();
    }

    public void openFinance(Player player, int page) {
        runtime.read(player, () -> {
            EconomyRepository.TownFinance account = runtime.finance()
                    .findFinanceByPlayer(player.getUniqueId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            plugin.messages().plainText("chat.runtime.town-required")));
            return new FinanceView(account, runtime.taxSubsidyQuota(account.townId()));
        }, view -> renderFinance(player, view));
    }

    private void renderFinance(Player player, FinanceView view) {
        EconomyRepository.TownFinance account = view.account();
        List<String> summary = new ArrayList<>(List.of(
                presentation.dialogText("common.town", Map.of("town", TownUiLegacyFacade.safeText(account.townName()))),
                presentation.dialogText("finance.balance", Map.of("balance",
                        TownUiLegacyFacade.safeText(runtime.money(account.balanceMinor())))),
                presentation.dialogText("finance.tax-rate", Map.of("rate",
                        TownUiLegacyFacade.safeText(TownRuntime.percent(account.taxRateBps())))),
                presentation.dialogText("common.territory-units", Map.of("count", account.unitCount(),
                        "maximum", runtime.economySettings().maximumUnits())),
                presentation.dialogText("finance.subsidy-twelve-hour", Map.of("amount", TownUiLegacyFacade.safeText(
                                runtime.money(view.subsidyQuota().twelveHourRemainingMinor())),
                        "refresh", TownUiLegacyFacade.safeText(view.subsidyQuota().twelveHourRefreshAt()))),
                presentation.dialogText("finance.subsidy-week", Map.of("amount", TownUiLegacyFacade.safeText(
                                runtime.money(view.subsidyQuota().weeklyRemainingMinor())),
                        "refresh", TownUiLegacyFacade.safeText(view.subsidyQuota().weeklyRefreshAt())))));
        if (account.locked()) {
            summary.add(presentation.dialogText("finance.locked", Map.of("reason",
                    TownUiLegacyFacade.safeText(account.lockReason()))));
        }
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(4, presentation.button(account.locked() ? Material.REDSTONE_BLOCK
                : Material.EMERALD_BLOCK, presentation.dialogText("common.finance-title"), summary, null, null)));
        if (runtime.consumptionEnabled()) {
            items.add(new MenuItem(10, presentation.button(Material.SUNFLOWER,
                    presentation.dialogText("finance.donation"),
                    List.of(presentation.dialogText("tooltip.finance.donation"),
                            presentation.dialogText("tooltip.finance.donation-vault")),
                    "DONATION_INPUT", null)));
        }
        if (account.role().equals("MAYOR") && runtime.taxEnabled()) {
            items.add(new MenuItem(12, presentation.button(Material.GOLD_NUGGET,
                    presentation.dialogText("finance.tax"),
                    List.of(presentation.dialogText("tooltip.finance.tax")), "TAX_MENU", null)));
        }
        items.add(new MenuItem(14, presentation.button(Material.WRITTEN_BOOK,
                presentation.dialogText("finance.ledger"),
                List.of(presentation.dialogText("tooltip.finance.ledger"),
                        presentation.dialogText("tooltip.finance.ledger-subsidy")), "LEDGER", "0")));
        items.add(new MenuItem(15, presentation.button(Material.BREWING_STAND, presentation.dialogText("finance.buff"),
                List.of(runtime.buffs().buffShopEnabled()
                                ? presentation.dialogText("tooltip.finance.buff-active")
                                : presentation.dialogText("tooltip.finance.buff-paused")),
                "BUFF_SHOP", null)));
        if (account.role().equals("MAYOR") && runtime.consumptionEnabled()
                && account.unitCount() < runtime.economySettings().maximumUnits()) {
            long expansionPriceMinor = ExpansionPricing.price(
                    runtime.economySettings().expansionCost(), account.unitCount() - 1,
                    runtime.settlement().scale())
                    .minorUnits();
            items.add(new MenuItem(16, presentation.button(Material.FILLED_MAP, presentation.dialogText("finance.expansion"),
                    List.of(presentation.dialogText("tooltip.finance.expansion", Map.of("price",
                            TownUiLegacyFacade.safeText(runtime.money(expansionPriceMinor))))),
                    "EXPANSION_MENU", null)));
        }
        presentation.openMenu(player, 27, presentation.dialogText("common.finance-title"),
                new DialogRoute("MAIN", null), items);
        if (account.hasUnreadTaxChange()) {
            actions.acknowledgeTaxRevision(player, account.taxRevision(), outcome ->
                    facade.handleOutcome(player, outcome, ignored -> {
                    }));
        }
    }

    public void openTaxMenu(Player player) {
        runtime.read(player, () -> runtime.finance().findFinanceByPlayer(player.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-required"))), account -> {
            boolean editable = account.role().equals("MAYOR") && runtime.taxEnabled();
            ItemStack summary = presentation.button(Material.GOLD_INGOT, presentation.dialogText("tax.summary-title"),
                    List.of(presentation.dialogText("tax.current-rate", Map.of("rate",
                                    TownUiLegacyFacade.safeText(TownRuntime.percent(account.taxRateBps())))),
                            presentation.dialogText("tax.scope"),
                            editable ? presentation.dialogText("tax.editable-hint")
                                    : presentation.dialogText("tax.readonly-hint")),
                    null, null);
            if (!editable) {
                presentation.openMenu(player, 27, presentation.dialogText("tax.menu-title"),
                        new DialogRoute("FINANCE", "0"),
                        List.of(new MenuItem(0, summary)));
                return;
            }
            DialogInput input = DialogInput.numberRange("tax_rate", 360,
                    presentation.dialogComponent("tax.rate-label"),
                    presentation.dialogFormat("tax.rate-format"), 5.0F,
                    runtime.economySettings().maximumTaxBps() / 100.0F,
                    account.taxRateBps() / 100.0F, 1.0F);
            presentation.openDialogPage(player, presentation.dialogText("tax.title"), List.of(presentation.dialogTextBody(summary)),
                    List.of(input),
                    DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, session ->
                            DialogType.multiAction(List.of(
                                            ActionButton.create(presentation.dialogComponent("common.save-changes"),
                                                    presentation.dialogComponent("tax.save-tooltip"),
                                                    170, presentation.dialogAction(player, session,
                                                            response -> applyTaxDialog(player, account, response))),
                                            ActionButton.create(presentation.dialogComponent("common.cancel"),
                                                    null, 170,
                                                    presentation.dialogAction(player, session, "FINANCE", "0"))))
                                    .exitAction(presentation.returnButton(player, session,
                                            new DialogRoute("FINANCE", "0")))
                                    .columns(2).build(), new DialogRoute("FINANCE", "0"));
        });
    }

    private void applyTaxDialog(Player player, EconomyRepository.TownFinance account,
                                DialogResponseView response) {
        Float selected = response.getFloat("tax_rate");
        if (selected == null) {
            presentation.openNotice(player, presentation.dialogText("tax.select-title"),
                    presentation.dialogText("tax.select-message"), presentation.dialogText("common.back"),
                    "TAX_MENU", null);
            return;
        }
        int rate = Math.round(selected) * 100;
        if (rate == account.taxRateBps()) {
            presentation.openNotice(player, presentation.dialogText("tax.unchanged-title"),
                    presentation.dialogText("tax.unchanged-message", Map.of(
                            "rate", TownRuntime.percent(rate))),
                    presentation.dialogText("common.back"), "FINANCE", "0");
            return;
        }
        actions.changeTaxRate(player, account.townId(), rate, outcome ->
                facade.handleOutcome(player, outcome, change -> {
                    notifyTaxRateChange(change);
                    presentation.openNotice(player, presentation.dialogText("tax.saved-title"),
                            presentation.dialogText("tax.saved-message", Map.of(
                                    "rate", TownRuntime.percent(change.basisPoints()))),
                            presentation.dialogText("common.back"), "FINANCE", "0");
                }));
    }

    public void openLedger(Player player, int page) {
        runtime.read(player, () -> {
            EconomyRepository.TownFinance account = runtime.finance()
                    .findFinanceByPlayer(player.getUniqueId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            plugin.messages().plainText("chat.runtime.town-required")));
            List<EconomyRepository.DisplayLedgerEntry> entries = runtime.finance()
                    .displayLedger(account.townId(), page, 6);
            return new LedgerPage(account, entries, page);
        }, ledger -> renderLedger(player, ledger));
    }

    private void renderLedger(Player player, LedgerPage ledger) {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(0, presentation.button(Material.WRITTEN_BOOK,
                presentation.dialogText("ledger.title", Map.of("town",
                        TownUiLegacyFacade.safeText(ledger.account().townName()))),
                List.of(presentation.dialogText("ledger.page", Map.of("page", ledger.page() + 1)),
                        presentation.dialogText("ledger.balance", Map.of("balance",
                                TownUiLegacyFacade.safeText(runtime.money(ledger.account().balanceMinor()))))),
                null, null)));
        if (ledger.entries().isEmpty()) {
            items.add(new MenuItem(1, presentation.button(Material.PAPER,
                    presentation.dialogText("ledger.empty"),
                    List.of(presentation.dialogText("ledger.empty-hint")), null, null)));
        }
        int slot = 1;
        for (EconomyRepository.DisplayLedgerEntry entry : ledger.entries()) {
            boolean income = entry.amountMinor() > 0;
            String amount = presentation.dialogText(income ? "ledger.income-amount" : "ledger.expense-amount",
                    Map.of("amount", TownUiLegacyFacade.safeText(runtime.money(Math.abs(entry.amountMinor())))));
            List<String> details = new ArrayList<>();
            if (entry.summary() != null) {
                EconomyRepository.TaxIncomeSummary summary = entry.summary();
                details.add(presentation.dialogText("ledger.summary-period", Map.of(
                        "start", TownUiLegacyFacade.safeText(summary.periodStart()),
                        "end", TownUiLegacyFacade.safeText(summary.periodEnd()))));
                details.add(presentation.dialogText(java.time.Instant.now().isBefore(summary.periodEnd())
                        ? "ledger.summary-current" : "ledger.summary-completed"));
                details.add(presentation.dialogText("ledger.summary-tax", Map.of(
                        "amount", runtime.money(summary.taxMinor()))));
                details.add(presentation.dialogText("ledger.summary-subsidy", Map.of(
                        "amount", runtime.money(summary.subsidyMinor()))));
                details.add(presentation.dialogText("ledger.summary-count", Map.of(
                        "count", summary.transactionCount())));
            } else {
                String actor = displayActorName(entry);
                if (actor == null) {
                    String actorId = entry.actorId() == null ? "" : entry.actorId().toString()
                            .replace("-", "");
                    String suffix = actorId.length() > 24 ? actorId.substring(24) : actorId;
                    actor = presentation.dialogText("common.unknown-player", Map.of("playerId", suffix));
                }
                details.add(presentation.dialogText("ledger.entry-balance", Map.of("balance",
                        TownUiLegacyFacade.safeText(runtime.money(entry.balanceAfterMinor())))));
                details.add(presentation.dialogText("ledger.entry-actor", Map.of("actor", actor)));
                details.add(presentation.dialogText("ledger.entry-time", Map.of("time",
                        TownUiLegacyFacade.safeText(entry.createdAt()))));
                details.add(presentation.dialogText("ledger.entry-note", Map.of("note",
                        TownUiLegacyFacade.safeText(entry.note()))));
            }
            items.add(new MenuItem(slot++, presentation.button(income ? Material.LIME_DYE : Material.RED_DYE,
                    presentation.dialogText("ledger.entry-title", Map.of("amount", amount,
                            "type", ledgerLabel(entry.entryType()))), details, null, null)));
        }
        if (ledger.page() > 0) {
            items.add(new MenuItem(20, presentation.button(Material.ARROW, presentation.dialogText("common.previous"),
                    List.of(), "LEDGER", String.valueOf(ledger.page() - 1))));
        }
        if (ledger.entries().size() == 6) {
            items.add(new MenuItem(21, presentation.button(Material.ARROW, presentation.dialogText("common.next"),
                    List.of(), "LEDGER", String.valueOf(ledger.page() + 1))));
        }
        presentation.openMenu(player, 27, presentation.dialogText("ledger.page-title", Map.of("page", ledger.page() + 1)),
                new DialogRoute("FINANCE", "0"), items);
    }

    private String ledgerLabel(String type) {
        String stableType = String.valueOf(type);
        String key = switch (stableType) {
            case "JOBS_INCOME" -> "ledger.type.jobs-income";
            case "SHOP_INCOME" -> "ledger.type.shop-income";
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
            default -> null;
        };
        return key == null
                ? presentation.dialogText("ledger.type.unknown", Map.of("type", TownUiLegacyFacade.safeText(stableType)))
                : presentation.dialogText(key);
    }

    private static String displayActorName(EconomyRepository.DisplayLedgerEntry entry) {
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
                        .append(presentation.callbackButton(member, "chat.buttons.view-tax",
                                () -> openTaxMenu(member)))
                        .append(Component.space())
                        .append(presentation.callbackButton(member, "chat.buttons.view-finance",
                                () -> openFinance(member, 0))));
                presentation.playSound(member, Sound.BLOCK_BELL_USE);
            }
        });
    }

    public void startDonationInput(Player player) {
        if (!runtime.consumptionEnabled()) {
            presentation.openNotice(player, presentation.dialogText("donation.unavailable-title"),
                    presentation.dialogText("donation.unavailable-message"),
                    presentation.dialogText("common.back"), "FINANCE", "0");
            return;
        }
        openDonationDialog(player, null, "");
    }

    private void openDonationDialog(Player player, String error, String initial) {
        runtime.read(player, () -> runtime.finance().findFinanceByPlayer(player.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-required"))), account -> {
            List<String> description = new ArrayList<>(List.of(
                    presentation.dialogText("common.town", Map.of("town", TownUiLegacyFacade.safeText(account.townName()))),
                    presentation.dialogText("donation.balance", Map.of(
                            "balance", TownUiLegacyFacade.safeText(runtime.money(account.balanceMinor())))),
                    presentation.dialogText("donation.amount-hint", Map.of(
                            "scale", runtime.settlement().scale()))));
            if (error != null && !error.isBlank()) {
                description.add(presentation.dialogText("common.error", Map.of("error", TownUiLegacyFacade.safeText(error))));
            }
            ItemStack summary = presentation.button(Material.SUNFLOWER,
                    presentation.dialogText("donation.title"),
                    description, null, null);
            DialogInput amount = DialogInput.text("donation_amount", 360,
                    presentation.dialogComponent("donation.amount-label"), true,
                    initial, 64, null);
            presentation.openDialogPage(player, presentation.dialogText("donation.title"),
                    List.of(presentation.dialogTextBody(summary)), List.of(amount),
                    DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, session ->
                            DialogType.multiAction(List.of(
                                            ActionButton.create(presentation.dialogComponent("donation.confirm"),
                                                    presentation.dialogComponent("donation.confirm-tooltip"),
                                                    170, presentation.dialogAction(player, session,
                                                            response -> applyDonationDialog(player, response))),
                                            ActionButton.create(presentation.dialogComponent("common.cancel"),
                                                    null, 170,
                                                    presentation.dialogAction(player, session, "FINANCE", "0"))))
                                    .exitAction(presentation.returnButton(player, session,
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
                            presentation.openNotice(player, presentation.dialogText("notice.donation-success-title"),
                                    presentation.dialogText("notice.donation-success-message", Map.of(
                                            "amount", runtime.money(amount.minorUnits()),
                                            "balance", runtime.money(
                                                    mutation.balanceAfterMinor()))),
                                    presentation.dialogText("common.back"), "FINANCE", "0")));
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
                              List<EconomyRepository.DisplayLedgerEntry> entries, int page) {
    }
}
