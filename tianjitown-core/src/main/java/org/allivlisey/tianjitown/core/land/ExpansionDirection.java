package org.allivlisey.tianjitown.core.land;

import java.util.Locale;

public enum ExpansionDirection {
    NORTH(0, -1),
    EAST(1, 0),
    SOUTH(0, 1),
    WEST(-1, 0);

    private final int gridX;
    private final int gridZ;

    ExpansionDirection(int gridX, int gridZ) {
        this.gridX = gridX;
        this.gridZ = gridZ;
    }

    public int gridX() {
        return gridX;
    }

    public int gridZ() {
        return gridZ;
    }

    public static ExpansionDirection parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("激活方向不能为空");
        }
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "N", "NORTH", "北" -> NORTH;
            case "E", "EAST", "东" -> EAST;
            case "S", "SOUTH", "南" -> SOUTH;
            case "W", "WEST", "西" -> WEST;
            default -> throw new IllegalArgumentException("激活方向必须是 NORTH/EAST/SOUTH/WEST");
        };
    }
}
