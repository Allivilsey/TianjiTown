package org.allivlisey.tianjitown.storage.economy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.land.*;
import org.allivlisey.tianjitown.storage.database.*;
import org.allivlisey.tianjitown.storage.town.*;
import org.allivlisey.tianjitown.storage.town.ApplicationFeeOperation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SettlementRecoveryTest {
    @TempDir Path directory;
    private final UUID administrator = UUID.randomUUID();

    @Test void unreadableStartupBalanceLocksConsumptionAndSuccessfulRecheckOnlyClearsSettlementLocks() {
        try (var gate = database()) {
            var towns = new TownRepository(gate.dataSource(), () -> false);
            UUID alpha = activeTown(towns, "alpha", 5000);
            UUID bravo = activeTown(towns, "bravo", 5000);
            var economy = new EconomyRepository(gate.dataSource(), () -> false);
            var uncertain = economy.prepareOperation(alpha, "ADMIN_ADJUSTMENT", 100,
                    administrator, "Admin", "uncertain", "已有待核实操作");
            economy.requireCompensation(uncertain.operationId(), "外部结果不明");
            String compensationReason = economy.findFinanceByTown(alpha).orElseThrow().lockReason();

            economy.lockSettlementUnavailable();
            assertTrue(economy.findFinanceByTown(alpha).orElseThrow().locked());
            assertEquals(compensationReason, economy.findFinanceByTown(alpha).orElseThrow().lockReason());
            assertTrue(economy.findFinanceByTown(bravo).orElseThrow().locked());
            assertTrue(economy.findFinanceByTown(bravo).orElseThrow().lockReason().startsWith("SETTLEMENT_RECONCILIATION:"));
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> economy.prepareOperation(bravo, "ADMIN_ADJUSTMENT", -100, administrator,
                            "Admin", "debit-while-unknown", "清算未复核时支出"));

            var inspected = economy.inspectSettlement(10_000);
            assertTrue(economy.findFinanceByTown(bravo).orElseThrow().locked(), "只读检查不能解锁");
            assertEquals(inspected, economy.reconcileSettlement(10_000));
            assertTrue(economy.findFinanceByTown(alpha).orElseThrow().locked());
            assertEquals(compensationReason, economy.findFinanceByTown(alpha).orElseThrow().lockReason());
            assertFalse(economy.findFinanceByTown(bravo).orElseThrow().locked());
        }
    }

    @Test void collectedApplicationEscrowCountsBeforeTownCreationWithoutDoubleCountingAfterward() {
        try (var gate = database()) {
            var towns = new TownRepository(gate.dataSource(), () -> false);
            activeTown(towns, "alpha", 5000);
            var pending = submitted(towns, "pending");
            var collection = towns.claimApplicationFeeCollection(pending.id(), pending.version(), 1000, administrator, "Admin");
            towns.completeApplicationFeeOperation(collection, Outcome.SUCCESS, "完整到账", administrator, "Admin");
            var economy = new EconomyRepository(gate.dataSource(), () -> false);

            var escrow = economy.inspectSettlement(6000);
            assertEquals(6000, escrow.internalBalanceMinor());
            assertEquals(6000, escrow.requiredMinor());
            assertTrue(escrow.healthy());
            assertEquals(escrow, economy.reconcileSettlement(6000));
            assertFalse(economy.inspectSettlement(5999).healthy());

            towns.beginProvision(pending.id(), administrator, "Admin", "批准", "pending-provision", 1000);
            assertEquals(escrow, economy.inspectSettlement(6000));
            towns.finishProvision(pending.id(), true, "创建完成");
            assertEquals(escrow, economy.reconcileSettlement(6000));
        }
    }

    @Test void refundPendingCountsButInFlightAndUnknownRefundsStayIsolatedFromOtherTowns() {
        try (var gate = database()) {
            var towns = new TownRepository(gate.dataSource(), () -> false);
            UUID alpha = activeTown(towns, "alpha", 5000);
            var pending = submitted(towns, "pending");
            var collection = towns.claimApplicationFeeCollection(pending.id(), pending.version(), 1000, administrator, "Admin");
            towns.completeApplicationFeeOperation(collection, Outcome.SUCCESS, "完整到账", administrator, "Admin");
            towns.beginProvision(pending.id(), administrator, "Admin", "批准", "failed-provision", 1000);
            towns.finishProvision(pending.id(), false, "领地失败");
            towns.recoverFailedProvision(pending.id(), administrator, "Admin", "取消并退款", TownRepository.RecoveryMode.CANCEL_AND_REFUND);
            var economy = new EconomyRepository(gate.dataSource(), () -> false);
            assertEquals(6000, economy.inspectSettlement(6000).requiredMinor());

            var refund = towns.claimApplicationFeeRefund(pending.id(), administrator, "Admin");
            assertEquals(State.REFUNDING, refund.state());
            var inFlight = economy.inspectSettlement(5000);
            assertEquals(5000, inFlight.requiredMinor());
            assertEquals(inFlight, economy.reconcileSettlement(5000));
            assertFalse(economy.findFinanceByTown(alpha).orElseThrow().locked());

            var unknown = towns.completeApplicationFeeOperation(refund, Outcome.UNKNOWN, "外部返回丢失", administrator, "Admin");
            assertEquals(State.REFUND_UNKNOWN, unknown.state());
            assertEquals(inFlight, economy.inspectSettlement(5000));
            assertEquals(inFlight, economy.reconcileSettlement(5000));
            assertFalse(economy.findFinanceByTown(alpha).orElseThrow().locked());
            assertThrows(TownRepository.ConflictException.class,
                    () -> towns.claimApplicationFeeRefund(pending.id(), administrator, "Admin"));

            towns.resolveApplicationFee(pending.id(), unknown.version(), Resolution.REFUND_NOT_PAID,
                    administrator, "Admin", "玩家未收到，完整资金仍在清算账户");
            assertEquals(6000, economy.inspectSettlement(6000).requiredMinor());
            assertEquals(economy.inspectSettlement(6000), economy.reconcileSettlement(6000));
        }
    }

    @Test void uncertainNegativeOperationReservesPossibleDebitWithoutFreezingUnrelatedTown() {
        try (var gate = database()) {
            var towns = new TownRepository(gate.dataSource(), () -> false);
            UUID alpha = activeTown(towns, "alpha", 5000);
            UUID bravo = activeTown(towns, "bravo", 5000);
            var economy = new EconomyRepository(gate.dataSource(), () -> false);
            var debit = economy.prepareOperation(alpha, "ADMIN_ADJUSTMENT", -1000,
                    administrator, "Admin", "uncertain-debit", "扣款返回丢失");
            economy.markOperationExternalApplied(debit.operationId());
            economy.recoverInterruptedOperations();

            var inspected = economy.inspectSettlement(9000);
            assertEquals(10_000, inspected.internalBalanceMinor());
            assertEquals(-1000, inspected.pendingMinor());
            assertEquals(9000, inspected.requiredMinor());
            assertTrue(inspected.healthy());
            assertEquals(inspected, economy.reconcileSettlement(9000));
            assertTrue(economy.findFinanceByTown(alpha).orElseThrow().locked());
            assertTrue(economy.findFinanceByTown(alpha).orElseThrow().lockReason().startsWith("ECONOMY_COMPENSATION:"));
            assertFalse(economy.findFinanceByTown(bravo).orElseThrow().locked());
            assertEquals(5000, economy.findFinanceByTown(alpha).orElseThrow().balanceMinor());
            assertEquals("COMPENSATION_REQUIRED", economy.pendingOperations().getFirst().status());
        }
    }

    private UUID activeTown(TownRepository towns, String code, long fee) {
        var application = submitted(towns, code);
        var provision = towns.beginProvision(application.id(), administrator, "Admin", "批准", "approve:" + code, fee);
        towns.finishProvision(application.id(), true, "完成");
        return provision.town().id();
    }

    private ApplicationSnapshot submitted(TownRepository towns, String code) {
        UUID mayor = UUID.randomUUID(), one = UUID.randomUUID(), two = UUID.randomUUID();
        var application = towns.createDraft(mayor,
                new ApplicationText("测试" + code, code, "测试简介", List.of("测试规则")), List.of(one, two), Duration.ZERO);
        UUID token = application.initialMembers().getFirst().invitationToken();
        towns.respondInitialMember(application.id(), one, token, true);
        towns.respondInitialMember(application.id(), two, token, true);
        towns.selectSite(application.id(), mayor,
                new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 0, 0)), Instant.now().plusSeconds(3600), 1);
        return towns.submit(application.id(), mayor);
    }

    private DatabaseGate database() {
        var gate = new DatabaseGate(new DatabaseConfig("jdbc:sqlite:" + directory.resolve("settlement.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5)));
        var health = gate.verifyAndMigrate();
        assertTrue(health.healthy(), health.detail());
        return gate;
    }
}
