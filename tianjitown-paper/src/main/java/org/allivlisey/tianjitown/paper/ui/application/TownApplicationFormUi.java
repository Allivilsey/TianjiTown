package org.allivlisey.tianjitown.paper.ui.application;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owns application/profile form routes and the player-scoped form lifecycle state. */
public final class TownApplicationFormUi {
    private final TownUiLegacyFacade facade;
    private final Map<UUID, ApplicationFormSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> initialMemberReminderCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> reminderOwners = new ConcurrentHashMap<>();

    public TownApplicationFormUi(TownUiLegacyFacade facade) {
        this.facade = facade;
    }

    public void route(Player player, String action, String target) {
        try {
            switch (action) {
                case "CREATE_APPLICATION" -> facade.loadApplicationFormDraft(player);
                case "EDIT_APPLICATION" -> facade.loadApplicationForForm(player, uuid(target));
                case "EDIT_TOWN", "EDIT_TOWN_DESCRIPTION" -> facade.loadTownForForm(player, uuid(target));
                case "APPLICATION_BASICS_FORM" -> facade.renderApplicationFormStage(player,
                        uuid(target), FormStage.BASICS.step());
                case "APPLICATION_CONTENT_FORM" -> facade.renderApplicationFormStage(player,
                        uuid(target), FormStage.CONTENT.step());
                case "APPLICATION_MEMBERS_FORM" -> facade.renderApplicationFormStage(player,
                        uuid(target), FormStage.MEMBERS.step());
                case "SELECT_INITIAL_MEMBER" -> {
                    InitialMemberTarget member = InitialMemberTarget.parse(target);
                    facade.openInitialMemberOptions(player, member.formId(), member.index());
                }
                case "CHOOSE_INITIAL_MEMBER" -> facade.chooseInitialMember(player,
                        InitialMemberChoice.parse(target).encode());
                case "CLEAR_INITIAL_MEMBER" -> {
                    InitialMemberTarget member = InitialMemberTarget.parse(target);
                    facade.clearInitialMember(player, member.formId(), member.index());
                }
                case "SAVE_APPLICATION_DRAFT" -> facade.saveApplicationForm(player, uuid(target));
                case "SAVE_FORM_DRAFT" -> facade.persistCurrentApplicationForm(player, uuid(target));
                case "DISCARD_FORM_DRAFT" -> facade.discardApplicationForm(player, uuid(target));
                default -> throw new IllegalArgumentException("unsupported application form action: " + action);
            }
        } catch (IllegalArgumentException exception) {
            facade.openStaleMenu(player);
        }
    }

    public ApplicationFormSession session(UUID playerId) {
        return sessions.get(playerId);
    }

    public void putSession(UUID playerId, ApplicationFormSession session) {
        sessions.put(playerId, session);
    }

    public void removeSession(UUID playerId) {
        sessions.remove(playerId);
    }

    public boolean removeSession(UUID playerId, ApplicationFormSession session) {
        return sessions.remove(playerId, session);
    }

    public void clearPlayer(UUID playerId) {
        sessions.remove(playerId);
        reminderOwners.entrySet().removeIf(entry -> {
            if (!entry.getValue().equals(playerId)) {
                return false;
            }
            initialMemberReminderCooldowns.remove(entry.getKey());
            return true;
        });
    }

    public void clear() {
        sessions.clear();
        initialMemberReminderCooldowns.clear();
        reminderOwners.clear();
    }

    public void clearReminder(UUID applicationId) {
        initialMemberReminderCooldowns.remove(applicationId);
        reminderOwners.remove(applicationId);
    }

    public Instant reminderAvailableAt(UUID applicationId) {
        return initialMemberReminderCooldowns.get(applicationId);
    }

    public void setReminderAvailableAt(UUID applicationId, UUID applicantId, Instant availableAt) {
        initialMemberReminderCooldowns.put(applicationId, availableAt);
        reminderOwners.put(applicationId, applicantId);
    }

    public enum FormStage {
        BASICS(1), CONTENT(2), MEMBERS(3);

        private final int step;

        FormStage(int step) {
            this.step = step;
        }

        int step() {
            return step;
        }
    }

    public record InitialMemberTarget(UUID formId, int index) {
        static InitialMemberTarget parse(String target) {
            String[] values = parts(target, 2);
            int index = Integer.parseInt(values[1]);
            if (index < 0 || index > 1) {
                throw new IllegalArgumentException("invalid initial-member index");
            }
            return new InitialMemberTarget(UUID.fromString(values[0]), index);
        }
    }

    public record InitialMemberChoice(UUID formId, int index, String playerName) {
        static InitialMemberChoice parse(String target) {
            String[] values = parts(target, 3);
            int index = Integer.parseInt(values[1]);
            if (index < 0 || index > 1 || values[2].isBlank()) {
                throw new IllegalArgumentException("invalid initial-member choice");
            }
            return new InitialMemberChoice(UUID.fromString(values[0]), index, values[2]);
        }

        String encode() {
            return formId + ":" + index + ":" + playerName;
        }
    }

    public record ApplicationFormSession(UUID id, FormPurpose purpose, UUID targetId, long version,
                                  ApplicationText text, List<String> initialMemberNames) {
        public ApplicationFormSession {
            initialMemberNames = normalizedMemberNames(initialMemberNames);
        }
    }

    public enum FormPurpose {
        APPLICATION,
        TOWN_PROFILE
    }

    public enum ApplicationField {
        NAME("name"),
        RESIDENCE_NAME("residence-name"),
        DESCRIPTION("description"),
        RULES("rules"),
        INITIAL_MEMBER_ONE("initial_member_one"),
        INITIAL_MEMBER_TWO("initial_member_two");

        private final String key;

        ApplicationField(String key) {
            this.key = key;
        }

        String label(PluginMessages messages) {
            return message(messages, "label");
        }

        String requirement(PluginMessages messages) {
            return message(messages, "requirement");
        }

        String suggestion(PluginMessages messages) {
            return message(messages, "suggestion");
        }

        private String message(PluginMessages messages, String property) {
            return messages.rawText("dialog.application.field." + key + "." + property);
        }
    }

    private static UUID uuid(String target) {
        if (target == null || target.isBlank() || target.indexOf(':') >= 0) {
            throw new IllegalArgumentException("invalid form UUID target");
        }
        return UUID.fromString(target);
    }

    private static String[] parts(String target, int expected) {
        String[] values = target == null ? new String[0] : target.split(":", -1);
        if (values.length != expected) {
            throw new IllegalArgumentException("invalid form route target");
        }
        return values;
    }

    private static List<String> normalizedMemberNames(List<String> names) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>(List.of("", ""));
        if (names != null) {
            for (int index = 0; index < Math.min(2, names.size()); index++) {
                result.set(index, java.util.Objects.requireNonNullElse(names.get(index), "").strip());
            }
        }
        return List.copyOf(result);
    }
}
