package cn.tianji.town.core.land;

import java.util.Locale;

public enum ExpansionDirection {
    NORTH(0, -1, "北"),
    EAST(1, 0, "东"),
    SOUTH(0, 1, "南"),
    WEST(-1, 0, "西");

    private final int gridX;
    private final int gridZ;
    private final String displayName;

    ExpansionDirection(int gridX, int gridZ, String displayName) {
        this.gridX = gridX;
        this.gridZ = gridZ;
        this.displayName = displayName;
    }

    public int gridX() {
        return gridX;
    }

    public int gridZ() {
        return gridZ;
    }

    public String displayName() {
        return displayName;
    }

    public static ExpansionDirection parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("扩张方向不能为空");
        }
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "N", "NORTH", "北" -> NORTH;
            case "E", "EAST", "东" -> EAST;
            case "S", "SOUTH", "南" -> SOUTH;
            case "W", "WEST", "西" -> WEST;
            default -> throw new IllegalArgumentException("扩张方向必须是 NORTH/EAST/SOUTH/WEST");
        };
    }
}
