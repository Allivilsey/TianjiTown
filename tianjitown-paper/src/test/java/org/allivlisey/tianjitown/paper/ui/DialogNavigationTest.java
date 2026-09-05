package org.allivlisey.tianjitown.paper.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DialogNavigationTest {
    @Test
    void rootPagesUseCloseWithoutAReturnRoute() {
        DialogNavigation navigation = DialogNavigation.bottom(DialogRoute.ROOT);

        assertEquals("common.close", navigation.labelKey());
        assertEquals("common.close-tooltip", navigation.tooltipKey());
        assertNull(navigation.route().action());
        assertNull(navigation.route().target());
    }

    @Test
    void forcedNoticesRemainRootPagesEvenWhenTheirCloseActionIsExplicit() {
        DialogNavigation navigation = DialogNavigation.bottom(DialogRoute.parent("CLOSE", null));

        assertEquals("common.close", navigation.labelKey());
        assertNull(navigation.route().action());
    }

    @Test
    void childPagesUseReturnAndPreserveTheirExplicitParentRoute() {
        DialogRoute parent = new DialogRoute("FINANCE", "0");
        DialogNavigation navigation = DialogNavigation.bottom(parent);

        assertEquals("common.back", navigation.labelKey());
        assertEquals("common.back-tooltip", navigation.tooltipKey());
        assertEquals("FINANCE", navigation.route().action());
        assertEquals("0", navigation.route().target());
    }
}
