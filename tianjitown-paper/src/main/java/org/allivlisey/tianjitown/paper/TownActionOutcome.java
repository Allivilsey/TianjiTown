package org.allivlisey.tianjitown.paper;

record TownActionOutcome<T>(TownActionResult result, T value) {
    static <T> TownActionOutcome<T> success(TownActionResult result, T value) {
        return new TownActionOutcome<>(result, value);
    }

    static <T> TownActionOutcome<T> failure(TownActionResult result) {
        return new TownActionOutcome<>(result, null);
    }
}
