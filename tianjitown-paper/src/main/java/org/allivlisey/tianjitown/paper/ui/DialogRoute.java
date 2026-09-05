package org.allivlisey.tianjitown.paper.ui;

/** Declarative parent route and its one bottom-dialog navigation action. */
public record DialogRoute(String action, String target) {
    public static final DialogRoute ROOT = new DialogRoute(null, null);

    public static DialogRoute parent(String action, String target) {
        return action == null || action.equals("CLOSE") ? ROOT : new DialogRoute(action, target);
    }

    public boolean isRoot() {
        return action == null;
    }
}
