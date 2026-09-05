package org.allivlisey.tianjitown.paper.runtime;

public record TownActionOutcome<T>(TownActionResult result, T value) {
    public static <T> TownActionOutcome<T> success(TownActionResult result, T value) {
        return new TownActionOutcome<>(result, value);
    }

    public static <T> TownActionOutcome<T> failure(TownActionResult result) {
        return new TownActionOutcome<>(result, null);
    }
}
