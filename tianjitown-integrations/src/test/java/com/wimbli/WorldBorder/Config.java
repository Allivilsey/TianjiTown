package com.wimbli.WorldBorder;

public final class Config {
    public static BorderData border;

    private Config() {
    }

    public static BorderData Border(String worldName) {
        return "world".equals(worldName) ? border : null;
    }
}
