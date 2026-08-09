package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationStatus;
import cn.tianji.town.core.town.TownStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownAdminCompletionEngineTest {
    private final UUID submitted = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID review = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID failed = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private final UUID town = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final UUID member = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private final TownAdminCompletionEngine engine = new TownAdminCompletionEngine();
    private final TownAdminCompletionEngine.Snapshot snapshot = new TownAdminCompletionEngine.Snapshot(
            List.of(
                    new TownAdminCompletionEngine.ApplicationCandidate(submitted,
                            "待审小镇", ApplicationStatus.SUBMITTED),
                    new TownAdminCompletionEngine.ApplicationCandidate(review,
                            "补件小镇", ApplicationStatus.UNDER_REVIEW),
                    new TownAdminCompletionEngine.ApplicationCandidate(failed,
                            "失败小镇", ApplicationStatus.PROVISION_FAILED)),
            List.of(new TownAdminCompletionEngine.TownCandidate(town, "天际 之城", TownStatus.ACTIVE)),
            Map.of(town, List.of(member)));
    private final TownAdminCompletionEngine.Dynamic dynamic = new TownAdminCompletionEngine.Dynamic(
            List.of(new TownAdminCompletionEngine.PlayerCandidate(member, "MemberOne", true)),
            List.of("world", "resource"), 12, -8, true, true);

    @Test
    void filtersRootCommandsAndIncludesContextualCommands() {
        List<String> result = engine.complete(new String[]{"st"}, snapshot, dynamic);
        assertEquals(List.of("station", "status"), result);
        assertTrue(engine.complete(new String[]{"phase"}, snapshot, dynamic).contains("phase0"));
        assertTrue(engine.complete(new String[]{"main"}, snapshot, dynamic).contains("maintenance"));
        assertFalse(engine.complete(new String[]{""}, snapshot, dynamic).contains("data"));
        assertEquals(List.of("application"), engine.complete(
                new String[]{"help", "app"}, snapshot, dynamic));
    }

    @Test
    void filtersApplicationsByWorkflowStatus() {
        assertEquals(List.of("approve", "change", "list", "reject"), engine.complete(
                new String[]{"application", ""}, snapshot, dynamic));
        assertEquals(List.of("待审小镇"), engine.complete(
                new String[]{"application", "approve", "待"}, snapshot, dynamic));
        assertEquals(List.of("失败小镇"), engine.complete(
                new String[]{"application", "approve", "失"}, snapshot, dynamic));
        assertEquals(List.of("补件小镇"), engine.complete(
                new String[]{"application", "change", "补"}, snapshot, dynamic));
        assertEquals(List.of("<原因>"), engine.complete(
                new String[]{"application", "approve", "待审小镇", ""}, snapshot, dynamic));
        assertEquals(List.of(), engine.complete(
                new String[]{"application", "approve", "待审小镇", "已"}, snapshot, dynamic));
    }

    @Test
    void completesTownMembersReasonHintsAndRepairAction() {
        assertEquals(List.of("create", "info", "list", "remove"), engine.complete(
                new String[]{"station", ""}, snapshot, dynamic));
        assertEquals(List.of("MemberOne"), engine.complete(
                new String[]{"member", "remove", "天际", "之城", ""},
                snapshot, dynamic));
        assertEquals(List.of("<原因>"), engine.complete(
                new String[]{"member", "remove", "天际", "之城", "MemberOne", ""},
                snapshot, dynamic));
        assertEquals(List.of("repair"), engine.complete(
                new String[]{"land", "reconcile", "天际", "之城", ""}, snapshot, dynamic));
        assertEquals(List.of("<原因>"), engine.complete(
                new String[]{"town", "delete", "天际之城", ""}, snapshot, dynamic));
        assertEquals(List.of("off", "on", "status"), engine.complete(
                new String[]{"maintenance", ""}, snapshot, dynamic));
    }

    @Test
    void completesPhaseZeroWorldCoordinatesWithoutConfirmationFlags() {
        assertEquals(List.of("world"), engine.complete(
                new String[]{"phase0", "residence-smoke", "wo"}, snapshot, dynamic));
        assertEquals(List.of("12"), engine.complete(
                new String[]{"phase0", "residence-smoke", "world", ""}, snapshot, dynamic));
        assertEquals(List.of(), engine.complete(new String[]{"phase0", "residence-smoke",
                "world", "12", "-8", member.toString(), ""}, snapshot, dynamic));
    }

    @Test
    void limitsPlayerOnlyActionsAndPhaseZeroRootsWhenUnavailable() {
        TownAdminCompletionEngine.Dynamic console = new TownAdminCompletionEngine.Dynamic(
                List.of(), List.of("world"), null, null, false, false);
        List<String> roots = engine.complete(new String[]{""}, snapshot, console);
        assertTrue(roots.contains("station"));
        assertFalse(roots.contains("phase0"));
        assertFalse(roots.contains("confirm"));
        assertFalse(roots.contains("cancel"));
        assertEquals(List.of("list"), engine.complete(
                new String[]{"station", ""}, snapshot, console));
        assertFalse(engine.complete(new String[]{"land", ""}, snapshot, console)
                .contains("preview"));
    }
}
