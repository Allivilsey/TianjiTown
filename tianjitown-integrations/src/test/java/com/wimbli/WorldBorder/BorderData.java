package com.wimbli.WorldBorder;

public final class BorderData {
    private final double minimum;
    private final double maximum;

    public BorderData(double minimum, double maximum) {
        this.minimum = minimum;
        this.maximum = maximum;
    }

    public boolean insideBorder(double x, double z) {
        return x >= minimum && x <= maximum && z >= minimum && z <= maximum;
    }
}
