package cn.tianji.town.core.profile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record TownProfile(
        int schemaVersion,
        UUID townId,
        long revision,
        Instant createdAt,
        String name,
        String shortName,
        String description,
        List<String> rules,
        Map<String, Boolean> publicSettings,
        String checksum
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public TownProfile {
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(shortName, "shortName");
        Objects.requireNonNull(description, "description");
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        publicSettings = Map.copyOf(Objects.requireNonNull(publicSettings, "publicSettings"));
        Objects.requireNonNull(checksum, "checksum");
    }

    public TownProfile withoutChecksum() {
        return new TownProfile(schemaVersion, townId, revision, createdAt, name, shortName,
                description, rules, publicSettings, "");
    }

    public TownProfile withChecksum(String value) {
        return new TownProfile(schemaVersion, townId, revision, createdAt, name, shortName,
                description, rules, publicSettings, value);
    }
}

