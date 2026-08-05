package cn.tianji.town.storage.profile;

import cn.tianji.town.core.profile.TownProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlProfileStoreTest {
    @TempDir
    Path directory;

    @Test
    void atomicallyRoundTripsSignedProfile() throws Exception {
        YamlProfileStore store = new YamlProfileStore();
        Path file = directory.resolve("town.yml");
        TownProfile profile = new TownProfile(1, UUID.randomUUID(), 1,
                Instant.parse("2026-08-04T00:00:00Z"), "测试镇", "测试", "简介",
                List.of("规则"), Map.of("listed", true), "");
        store.writeAtomically(file, profile);
        YamlProfileStore.ReadResult result = store.readAndValidate(file);
        assertTrue(result.valid(), () -> String.join("; ", result.errors()));
        assertEquals(profile.townId(), result.profile().townId());
    }

    @Test
    void rejectsUnknownFields() throws Exception {
        Path file = directory.resolve("bad.yml");
        Files.writeString(file, "schema-version: 1\nunknown-money: 10\n");
        YamlProfileStore.ReadResult result = new YamlProfileStore().readAndValidate(file);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("unknown-money")));
    }
}
