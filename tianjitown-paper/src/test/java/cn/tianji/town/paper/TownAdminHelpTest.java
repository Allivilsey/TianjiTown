package cn.tianji.town.paper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownAdminHelpTest {
    @Test
    void rootHelpListsSystemOnceAndDoesNotAdvertiseBuffRefunds() {
        List<String> entries = TownAdminCommand.rootHelpEntries(
                Set.of(TownAdminPermissions.ROOT)::contains);

        assertEquals(1, entries.stream().filter(line -> line.startsWith("§esystem ")).count());
        assertTrue(entries.stream().anyMatch(line -> line.startsWith("§ebuff ")));
        assertFalse(entries.stream().anyMatch(line -> line.contains("退款取消")));
    }

    @Test
    void operationsOnlyHelpStillListsSystemOnce() {
        List<String> entries = TownAdminCommand.rootHelpEntries(
                Set.of(TownAdminPermissions.OPERATIONS)::contains);

        assertEquals(List.of("§esystem §7状态、重载、维护、审计、统一诊断与在线备份"), entries);
    }
}
