package org.allivlisey.tianjitown.paper.land;
import org.allivlisey.tianjitown.paper.message.LandProtectionMessages;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.core.ports.WorldBoundaryService;
import org.bukkit.World;
import org.bukkit.entity.Player;

import org.allivlisey.tianjitown.core.land.TownReservation;
import java.util.Map;

public final class SitePolicy {
    private final TianjiTownPlugin plugin;
    private final LandProtectionService landProtection;
    private final WorldBoundaryService worldBoundaries;

    public SitePolicy(TianjiTownPlugin plugin, LandProtectionService landProtection,
               WorldBoundaryService worldBoundaries) {
        this.plugin = plugin;
        this.landProtection = landProtection;
        this.worldBoundaries = worldBoundaries;
    }

    public Validation validate(Player player) {
        World world = player.getWorld();
        InitialTerritory territory = new InitialTerritory(new ChunkPosition(world.getUID(),
                world.getName(), player.getChunk().getX(), player.getChunk().getZ()));
        return validate(territory);
    }

    public Validation validate(InitialTerritory territory) {
        if (territory == null) return missingTerritory();
        for (InitialTerritory unit : new TownReservation(territory).units()) {
            Validation result = validateUnit(unit);
            if (!result.valid()) return result;
        }
        return Validation.success(territory);
    }

    public Validation validateReservationEnvironment(InitialTerritory territory) {
        if (territory == null) return missingTerritory();
        for (InitialTerritory unit : new TownReservation(territory).units()) {
            Validation result = validateEnvironment(unit);
            if (!result.valid()) return result;
        }
        return Validation.success(territory);
    }

    private Validation validateUnit(InitialTerritory territory) {
        Validation environment = validateEnvironment(territory);
        if (!environment.valid()) {
            return environment;
        }
        LandProtectionService.Collision collision;
        try {
            collision = landProtection.findCollision(territory);
        } catch (RuntimeException | LinkageError exception) {
            return Validation.failure(plugin.messages().plainText(
                    "chat.site-validation.residence-unavailable",
                    Map.of("detail", safeMessage(exception))));
        }
        if (collision.code() != null) {
            return Validation.failure(LandProtectionMessages.detail(plugin.messages(), collision));
        }
        if (collision.occupied()) {
            return Validation.failure(plugin.messages().plainText(
                    "chat.site-validation.residence-collision",
                    Map.of("name", String.valueOf(collision.residenceName()))));
        }
        return environment;
    }

    public Validation validateExpansion(InitialTerritory territory, String residenceName) {
        Validation environment = validateEnvironment(territory);
        if (!environment.valid()) {
            return environment;
        }
        LandProtectionService.Collision collision;
        try {
            collision = landProtection.findCollision(territory);
        } catch (RuntimeException | LinkageError exception) {
            return Validation.failure(plugin.messages().plainText(
                    "chat.site-validation.residence-unavailable",
                    Map.of("detail", safeMessage(exception))));
        }
        if (collision.code() != null) {
            return Validation.failure(LandProtectionMessages.detail(plugin.messages(), collision));
        }
        if (collision.occupied() && (collision.residenceName() == null
                || !collision.residenceName().equalsIgnoreCase(residenceName))) {
            return Validation.failure(plugin.messages().plainText(
                    "chat.site-validation.expansion-collision",
                    Map.of("name", String.valueOf(collision.residenceName()))));
        }
        return environment;
    }

    public Validation validateEnvironment(InitialTerritory territory) {
        if (territory == null) return missingTerritory();
        World world = plugin.getServer().getWorld(territory.center().worldId());
        if (world == null) {
            return Validation.failure(plugin.messages().plainText(
                    "chat.site-validation.world-unloaded"));
        }
        int bufferChunks = Math.max(0,
                plugin.getConfig().getInt("town.site.minimum-buffer-chunks", 1));
        WorldBoundaryService.Check boundary;
        try {
            boundary = worldBoundaries.check(territory, bufferChunks);
        } catch (RuntimeException | LinkageError exception) {
            return Validation.failure(plugin.messages().plainText(
                    "chat.site-validation.worldborder-unavailable",
                    Map.of("detail", safeMessage(exception))));
        }
        if (!boundary.configured()) {
            return Validation.failure(plugin.messages().plainText(
                    "chat.site-validation.worldborder-unconfigured"));
        }
        if (!boundary.inside()) {
            return Validation.failure(plugin.messages().plainText(
                    "chat.site-validation.worldborder-outside"));
        }
        return Validation.success(territory);
    }

    private Validation missingTerritory() {
        return Validation.failure(plugin.messages().plainText("chat.site-validation.territory-missing"));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return safeText(message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message);
    }

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    public record Validation(boolean valid, String error, InitialTerritory territory) {
        public static Validation success(InitialTerritory territory) {
            return new Validation(true, null, territory);
        }

        public static Validation failure(String error) {
            return new Validation(false, error, null);
        }
    }

}
