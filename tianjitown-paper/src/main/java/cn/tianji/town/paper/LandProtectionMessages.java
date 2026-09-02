package cn.tianji.town.paper;

import cn.tianji.town.core.ports.LandProtectionService;

import java.util.Objects;

/**
 * Resolves structured land-protection results at the Paper boundary.
 *
 * <p>Legacy integrations may still provide a rendered detail through
 * {@code message()}; those details are returned unchanged until their adapter
 * task migrates them to a result code.</p>
 */
final class LandProtectionMessages {
    private LandProtectionMessages() {
    }

    static String text(PluginMessages messages, LandProtectionService.Result result) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(result, "result");
        return result.code() == null
                ? result.message()
                : messages.text(key(result.code()), result.parameters());
    }

    static String text(PluginMessages messages, LandProtectionService.Inspection inspection) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(inspection, "inspection");
        return inspection.code() == null
                ? inspection.message()
                : messages.text(key(inspection.code()), inspection.parameters());
    }

    /**
     * Returns a color-free detail suitable for nesting in another configured
     * message, a log line, an audit snapshot, or a persisted failure reason.
     */
    static String detail(PluginMessages messages, LandProtectionService.Result result) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(result, "result");
        return result.code() == null
                ? result.message()
                : messages.plainText(key(result.code()), result.parameters());
    }

    static String detail(PluginMessages messages, LandProtectionService.Inspection inspection) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(inspection, "inspection");
        return inspection.code() == null
                ? inspection.message()
                : messages.plainText(key(inspection.code()), inspection.parameters());
    }

    static String detail(PluginMessages messages, LandProtectionService.Collision collision) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(collision, "collision");
        if (collision.code() == null) {
            return String.valueOf(collision.residenceName());
        }
        return messages.plainText(key(collision.code()), collision.parameters());
    }

    static String key(LandProtectionService.ResultCode code) {
        return switch (Objects.requireNonNull(code, "code")) {
            case UNSUPPORTED_TELEPORT_POINT ->
                    "chat.land-protection.unsupported-teleport-point";
            case UNSUPPORTED_MULTI_AREA -> "chat.land-protection.unsupported-multi-area";
            case UNSUPPORTED_ADD_AREA -> "chat.land-protection.unsupported-add-area";
            case UNSUPPORTED_REMOVE_AREA ->
                    "chat.land-protection.unsupported-remove-area";
            case WORLD_UNLOADED -> "chat.land-protection.world-unloaded";
            case RESIDENCE_API_UNAVAILABLE -> "chat.land-protection.api-unavailable";
            case PROJECTION_MISSING -> "chat.land-protection.projection-missing";
            case INITIAL_PROJECTION_COLLISION ->
                    "chat.land-protection.initial-projection-collision";
            case PROJECTION_CREATE_REJECTED ->
                    "chat.land-protection.projection-create-rejected";
            case PROJECTION_CREATE_READBACK_FAILED ->
                    "chat.land-protection.projection-create-readback-failed";
            case PROJECTION_ALREADY_ABSENT -> "chat.land-protection.projection-already-absent";
            case PROJECTION_REMOVED -> "chat.land-protection.projection-removed";
            case PROJECTION_STILL_PRESENT -> "chat.land-protection.projection-still-present";
            case PROJECTION_REMOVE_REJECTED -> "chat.land-protection.projection-remove-rejected";
            case REBUILD_OWNER_MISMATCH -> "chat.land-protection.rebuild-owner-mismatch";
            case CONTROLLED_PROJECTION_MISSING ->
                    "chat.land-protection.controlled-projection-missing";
            case TELEPORT_POINT_OUTSIDE_PROJECTION ->
                    "chat.land-protection.teleport-point-outside-projection";
            case TELEPORT_POINT_UPDATED -> "chat.land-protection.teleport-point-updated";
            case EXPANSION_PROJECTION_MISSING ->
                    "chat.land-protection.expansion-projection-missing";
            case PROJECTION_OWNER_NOT_CONTROLLED ->
                    "chat.land-protection.projection-owner-not-controlled";
            case AREA_OWNER_NOT_CONTROLLED ->
                    "chat.land-protection.area-owner-not-controlled";
            case AREA_BOUNDS_MISMATCH -> "chat.land-protection.area-bounds-mismatch";
            case EXPANSION_COLLISION -> "chat.land-protection.expansion-collision";
            case AREA_ADD_REJECTED -> "chat.land-protection.area-add-rejected";
            case AREA_ADD_ROLLBACK_FAILED -> "chat.land-protection.area-add-rollback-failed";
            case EXPANSION_AREA_ALREADY_ABSENT ->
                    "chat.land-protection.expansion-area-already-absent";
            case MAIN_AREA_REMOVAL_REJECTED ->
                    "chat.land-protection.main-area-removal-rejected";
            case AREA_STILL_PRESENT -> "chat.land-protection.area-still-present";
            case EXPANSION_AREA_REMOVED -> "chat.land-protection.expansion-area-removed";
            case PROJECTION_REBUILT_FROM_DATABASE ->
                    "chat.land-protection.projection-rebuilt-from-database";
            case MAIN_AREA_MISSING -> "chat.land-protection.main-area-missing";
            case DATABASE_AREA_CREATE_FAILED ->
                    "chat.land-protection.database-area-create-failed";
            case DATABASE_REBUILD_COLLISION ->
                    "chat.land-protection.database-rebuild-collision";
            case DATABASE_REBUILD_REMOVE_FAILED ->
                    "chat.land-protection.database-rebuild-remove-failed";
            case PROJECTION_AUTO_REPAIRED -> "chat.land-protection.projection-auto-repaired";
            case AREA_COUNT_MISMATCH -> "chat.land-protection.area-count-mismatch";
            case AREA_MISSING_OR_BOUNDS_MISMATCH ->
                    "chat.land-protection.area-missing-or-bounds-mismatch";
            case PROJECTION_BOUNDS_OR_AREA_COUNT_MISMATCH ->
                    "chat.land-protection.projection-bounds-or-area-count-mismatch";
            case PROJECTION_BOUNDARY_MISMATCH ->
                    "chat.land-protection.projection-boundary-mismatch";
            case EXPLOSION_FLAG_MISMATCH -> "chat.land-protection.explosion-flag-mismatch";
            case EXPLOSION_FLAG_WRITE_FAILED ->
                    "chat.land-protection.explosion-flag-write-failed";
            case MEMBERSHIP_MISMATCH -> "chat.land-protection.membership-mismatch";
            case MEMBER_PADD_PERMISSION_MISMATCH ->
                    "chat.land-protection.member-padd-permission-mismatch";
            case MEMBER_IGNITE_PERMISSION_MISMATCH ->
                    "chat.land-protection.member-ignite-permission-mismatch";
            case MEMBER_PADD_PERMISSION_WRITE_FAILED ->
                    "chat.land-protection.member-padd-permission-write-failed";
            case MEMBER_IGNITE_PERMISSION_WRITE_FAILED ->
                    "chat.land-protection.member-ignite-permission-write-failed";
            case PROJECTION_HEALTHY -> "chat.land-protection.projection-healthy";
        };
    }
}
