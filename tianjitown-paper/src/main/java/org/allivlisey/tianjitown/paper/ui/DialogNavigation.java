package org.allivlisey.tianjitown.paper.ui;

/** Keeps player-facing labels independent from the route implementation. */
record DialogNavigation(String labelKey, String tooltipKey, DialogRoute route) {
    public static DialogNavigation bottom(DialogRoute parent) {
        return parent.isRoot()
                ? new DialogNavigation("common.close", "common.close-tooltip", parent)
                : new DialogNavigation("common.back", "common.back-tooltip", parent);
    }
}
