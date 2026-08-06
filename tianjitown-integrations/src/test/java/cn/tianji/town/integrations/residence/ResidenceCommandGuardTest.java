package cn.tianji.town.integrations.residence;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResidenceCommandGuardTest {
    @Test
    void matchesOnlyCompleteDatabaseRegisteredNames() {
        Set<String> managedNames = Set.of("sky", "abc");

        assertTrue(ResidenceCommandGuard.mentionsManagedName(
                "/res remove SKY", managedNames::contains));
        assertTrue(ResidenceCommandGuard.mentionsManagedName(
                "/resadmin setowner abc player", managedNames::contains));
        assertFalse(ResidenceCommandGuard.mentionsManagedName(
                "/res remove skycity", managedNames::contains));
        assertFalse(ResidenceCommandGuard.mentionsManagedName(
                "/res list", managedNames::contains));
    }
}
