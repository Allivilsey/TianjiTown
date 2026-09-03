package org.allivlisey.tianjitown.integrations.residence;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void parsesOnlyResidenceCommandsAndSeparatesTeleportFromWrites() {
        ResidenceCommandGuard.ParsedCommand allowed = ResidenceCommandGuard.parse(
                "  /RES tp SKY  ");
        assertTrue(allowed.protectedOperation());
        assertTrue(allowed.teleport());
        assertEquals("sky", allowed.targetName());

        ResidenceCommandGuard.ParsedCommand write = ResidenceCommandGuard.parse(
                "/res pset sky container true");
        assertTrue(write.protectedOperation());
        assertFalse(write.teleport());

        assertNull(ResidenceCommandGuard.parse("/resadmin tp sky"));
        ResidenceCommandGuard.ParsedCommand list = ResidenceCommandGuard.parse("/res list");
        assertFalse(list.protectedOperation());
        assertTrue(ResidenceCommandGuard.parse("/res future-write sky").protectedOperation());
        assertTrue(ResidenceCommandGuard.parse("/res tp sky extra").protectedOperation());
        assertTrue(ResidenceCommandGuard.parse("/res tp").protectedOperation());
    }

    @Test
    void resolvesFailureLogWithSanitizedExceptionDetail() {
        String rendered = ResidenceCommandGuard.resolveFailureMessage((key, placeholders) -> {
            assertEquals("log.residence.command-guard-failure", key);
            assertEquals("boom＆�", placeholders.get("detail"));
            return "custom residence failure: " + placeholders.get("detail");
        }, new IllegalStateException("boom&§"));

        assertEquals("custom residence failure: boom＆�", rendered);
    }
}
