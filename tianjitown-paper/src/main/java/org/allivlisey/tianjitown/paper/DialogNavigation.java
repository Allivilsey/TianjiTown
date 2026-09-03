package cn.tianji.town.paper;

/** Declarative parent route and its one bottom-dialog navigation action. */
record DialogRoute(String action, String target) {
    static final DialogRoute ROOT = new DialogRoute(null, null);

    static DialogRoute parent(String action, String target) {
        return action == null || action.equals("CLOSE") ? ROOT : new DialogRoute(action, target);
    }

    boolean isRoot() {
        return action == null;
    }
}

/** Keeps player-facing labels independent from the route implementation. */
record DialogNavigation(String labelKey, String tooltipKey, DialogRoute route) {
    static DialogNavigation bottom(DialogRoute parent) {
        return parent.isRoot()
                ? new DialogNavigation("common.close", "common.close-tooltip", parent)
                : new DialogNavigation("common.back", "common.back-tooltip", parent);
    }
}
