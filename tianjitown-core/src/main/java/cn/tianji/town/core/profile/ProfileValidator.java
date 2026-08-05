package cn.tianji.town.core.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ProfileValidator {
    private static final Pattern CHECKSUM = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> PUBLIC_SETTING_KEYS = Set.of("listed", "accepting-invites");

    public List<String> validate(TownProfile profile) {
        List<String> errors = new ArrayList<>();
        if (profile.schemaVersion() != TownProfile.CURRENT_SCHEMA_VERSION) {
            errors.add("不支持的 schema-version: " + profile.schemaVersion());
        }
        if (profile.revision() < 0) {
            errors.add("revision 不能小于 0");
        }
        if (profile.name().isBlank() || profile.name().length() > 24) {
            errors.add("name 长度必须为 1..24");
        }
        if (profile.shortName().isBlank() || profile.shortName().length() > 8) {
            errors.add("short-name 长度必须为 1..8");
        }
        if (profile.description().length() > 500) {
            errors.add("description 不能超过 500 字符");
        }
        if (profile.rules().size() > 50 || profile.rules().stream().anyMatch(rule -> rule.length() > 300)) {
            errors.add("rules 最多 50 条且每条不超过 300 字符");
        }
        profile.publicSettings().keySet().stream()
                .filter(key -> !PUBLIC_SETTING_KEYS.contains(key))
                .forEach(key -> errors.add("不允许的 public-settings 字段: " + key));
        if (!CHECKSUM.matcher(profile.checksum()).matches()) {
            errors.add("checksum 必须是 64 位小写 SHA-256");
        } else if (!ProfileChecksum.matches(profile)) {
            errors.add("checksum 与内容不匹配");
        }
        return List.copyOf(errors);
    }
}

