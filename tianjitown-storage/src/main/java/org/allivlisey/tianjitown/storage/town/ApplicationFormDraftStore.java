package org.allivlisey.tianjitown.storage.town;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.allivlisey.tianjitown.storage.town.TownRepository.NotFoundException;

import static org.allivlisey.tianjitown.storage.town.TownSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.setNullableUuid;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.uuid;

/** Form draft SQL executed on the caller's connection and transaction. */
final class ApplicationFormDraftStore {
    private static final String RULE_SEPARATOR = "\u001e";

    private ApplicationFormDraftStore() {}

    static Optional<ApplicationFormDraft> find(Connection connection, UUID applicantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM application_form_drafts WHERE applicant_uuid = ?
                """)) {
            statement.setBytes(1, uuid(applicantId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readFormDraft(result)) : Optional.empty();
            }
        }
    }

    static void save(Connection connection, ApplicationFormDraft draft) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_form_drafts
                    (applicant_uuid, application_id, application_version, current_step,
                     name, short_name, residence_name, description, rules_text,
                     member_one_uuid, member_one_name, member_two_uuid, member_two_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (applicant_uuid) DO UPDATE SET
                    application_id = excluded.application_id,
                    application_version = excluded.application_version,
                    current_step = excluded.current_step,
                    name = excluded.name,
                    short_name = excluded.short_name,
                    residence_name = excluded.residence_name,
                    description = excluded.description,
                    rules_text = excluded.rules_text,
                    member_one_uuid = excluded.member_one_uuid,
                    member_one_name = excluded.member_one_name,
                    member_two_uuid = excluded.member_two_uuid,
                    member_two_name = excluded.member_two_name
                """)) {
            statement.setBytes(1, uuid(draft.applicantId()));
            if (draft.applicationId() == null) {
                statement.setNull(2, java.sql.Types.BLOB);
            } else {
                statement.setBytes(2, uuid(draft.applicationId()));
            }
            statement.setLong(3, draft.applicationVersion());
            statement.setInt(4, draft.currentStep());
            statement.setString(5, draft.name());
            statement.setString(6, draft.shortName());
            statement.setString(7, draft.residenceName());
            statement.setString(8, draft.description());
            statement.setString(9, String.join(RULE_SEPARATOR, draft.rules()));
            setNullableUuid(statement, 10, draft.memberOneId());
            statement.setString(11, draft.memberOneName());
            setNullableUuid(statement, 12, draft.memberTwoId());
            statement.setString(13, draft.memberTwoName());
            statement.executeUpdate();
        }
    }

    static void delete(Connection connection, UUID applicantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM application_form_drafts WHERE applicant_uuid = ?")) {
            statement.setBytes(1, uuid(applicantId));
            statement.executeUpdate();
        }
    }

    static ApplicationFormDraft requireFormDraft(Connection connection, UUID applicantId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM application_form_drafts WHERE applicant_uuid = ?")) {
            statement.setBytes(1, uuid(applicantId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new NotFoundException("找不到申请表单草稿");
                }
                return readFormDraft(result);
            }
        }
    }

    private static ApplicationFormDraft readFormDraft(ResultSet result) throws SQLException {
        byte[] application = result.getBytes("application_id");
        byte[] memberOne = result.getBytes("member_one_uuid");
        byte[] memberTwo = result.getBytes("member_two_uuid");
        String rulesText = result.getString("rules_text");
        List<String> rules = rulesText == null || rulesText.isEmpty() ? List.of()
                : List.of(rulesText.split(RULE_SEPARATOR, -1));
        return new ApplicationFormDraft(readUuid(result, "applicant_uuid"),
                application == null ? null : uuid(application),
                result.getLong("application_version"), result.getInt("current_step"),
                result.getString("name"), result.getString("short_name"),
                result.getString("residence_name"), result.getString("description"), rules,
                memberOne == null ? null : uuid(memberOne), result.getString("member_one_name"),
                memberTwo == null ? null : uuid(memberTwo), result.getString("member_two_name"),
                Instant.ofEpochMilli(result.getLong("updated_at")));
    }

}
