package cn.tianji.town.core.profile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

public final class ProfileChecksum {
    private ProfileChecksum() {
    }

    public static String calculate(TownProfile profile) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "schema-version", Integer.toString(profile.schemaVersion()));
        append(canonical, "town-id", profile.townId().toString());
        append(canonical, "revision", Long.toString(profile.revision()));
        append(canonical, "created-at", profile.createdAt().toString());
        append(canonical, "name", normalize(profile.name()));
        append(canonical, "short-name", normalize(profile.shortName()));
        append(canonical, "description", normalize(profile.description()));
        for (int index = 0; index < profile.rules().size(); index++) {
            append(canonical, "rules[" + index + "]", normalize(profile.rules().get(index)));
        }
        profile.publicSettings().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> append(canonical, "public-settings." + entry.getKey(),
                        Boolean.toString(entry.getValue())));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    public static boolean matches(TownProfile profile) {
        return MessageDigest.isEqual(
                calculate(profile).getBytes(StandardCharsets.US_ASCII),
                profile.checksum().getBytes(StandardCharsets.US_ASCII));
    }

    private static void append(StringBuilder target, String key, String value) {
        String encoded = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        target.append(key).append('=').append(encoded).append('\n');
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value.replace("\r\n", "\n").replace('\r', '\n'),
                Normalizer.Form.NFC);
    }
}

