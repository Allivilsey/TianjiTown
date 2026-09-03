package org.allivlisey.tianjitown.core.land;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class TownResidenceName {
    private static final Pattern RESIDENCE_NAME = Pattern.compile("[a-z]{1,12}");

    private TownResidenceName() {
    }

    public static String initial(String residenceName) {
        Objects.requireNonNull(residenceName, "residenceName");
        String normalized = Normalizer.normalize(residenceName.strip(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
        if (!RESIDENCE_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("小镇代码必须为 1~12 个英文字母");
        }
        return normalized;
    }

    public static String key(String residenceName) {
        return initial(residenceName);
    }
}
