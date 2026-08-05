package cn.tianji.town.core.profile;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileChecksumTest {
    @Test
    void checksumIsStableAcrossMapOrderAndLineEndings() {
        UUID townId = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
        TownProfile first = profile(townId, "第一行\r\n第二行", Map.of("listed", true, "accepting-invites", false));
        TownProfile second = profile(townId, "第一行\n第二行", Map.of("accepting-invites", false, "listed", true));
        assertEquals(ProfileChecksum.calculate(first), ProfileChecksum.calculate(second));
    }

    @Test
    void validatorAcceptsSignedProfile() {
        TownProfile unsigned = profile(UUID.randomUUID(), "简介", Map.of("listed", true));
        TownProfile signed = unsigned.withChecksum(ProfileChecksum.calculate(unsigned));
        assertTrue(new ProfileValidator().validate(signed).isEmpty());
    }

    private TownProfile profile(UUID id, String description, Map<String, Boolean> settings) {
        return new TownProfile(1, id, 3, Instant.parse("2026-08-04T00:00:00Z"),
                "测试镇", "测试", description, List.of("规则一"), settings, "");
    }
}
