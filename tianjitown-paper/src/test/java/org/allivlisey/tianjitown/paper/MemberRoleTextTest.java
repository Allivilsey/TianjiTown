package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.town.MemberRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class MemberRoleTextTest {
    @TempDir Path temporaryDirectory;

    @Test
    void everyRoleHasAChinesePlayerFacingLabel() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        for (MemberRole role : MemberRole.values()) {
            String text = messages.text("dialog." + MemberRoleText.messageKey(role));
            assertFalse(text.contains(role.name()));
            assertFalse(text.contains("缺少消息配置"));
        }
    }
}
