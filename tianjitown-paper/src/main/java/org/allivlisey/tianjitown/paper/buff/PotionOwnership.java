package org.allivlisey.tianjitown.paper.buff;

/** The effect signature we actually installed; catalog entries confer no ownership. */
record PotionOwnership(int amplifier, int maximumTicks, boolean ambient,
                       boolean particles, boolean icon) {
    boolean matches(int amplifier, int ticks, boolean ambient, boolean particles, boolean icon) {
        return this.amplifier == amplifier && ticks >= 0 && ticks <= maximumTicks
                && this.ambient == ambient && this.particles == particles && this.icon == icon;
    }

    String encode() {
        return amplifier + "," + maximumTicks + "," + ambient + "," + particles + "," + icon;
    }

    static PotionOwnership decode(String value) {
        String[] parts = value.split(",", -1);
        if (parts.length != 5) throw new IllegalArgumentException("Invalid potion ownership");
        return new PotionOwnership(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Boolean.parseBoolean(parts[2]), Boolean.parseBoolean(parts[3]),
                Boolean.parseBoolean(parts[4]));
    }
}
