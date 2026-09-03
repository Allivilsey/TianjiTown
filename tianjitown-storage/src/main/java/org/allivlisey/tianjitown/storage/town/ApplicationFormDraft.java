package cn.tianji.town.storage.town;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 可持久化的不完整申请表单。这里刻意不使用 {@code ApplicationText}，因为草稿字段
 * 在玩家完成相应步骤前可以为空。
 */
public record ApplicationFormDraft(
        UUID applicantId,
        UUID applicationId,
        long applicationVersion,
        int currentStep,
        String name,
        String shortName,
        String residenceName,
        String description,
        List<String> rules,
        UUID memberOneId,
        String memberOneName,
        UUID memberTwoId,
        String memberTwoName,
        Instant updatedAt
) {
    public ApplicationFormDraft {
        Objects.requireNonNull(applicantId, "applicantId");
        if (currentStep < 1 || currentStep > 3) {
            throw new IllegalArgumentException("申请草稿步骤必须在 1~3 之间");
        }
        name = Objects.requireNonNullElse(name, "");
        shortName = Objects.requireNonNullElse(shortName, "");
        residenceName = Objects.requireNonNullElse(residenceName, "");
        description = Objects.requireNonNullElse(description, "");
        rules = rules == null ? List.of() : List.copyOf(rules);
        memberOneName = Objects.requireNonNullElse(memberOneName, "");
        memberTwoName = Objects.requireNonNullElse(memberTwoName, "");
    }
}
