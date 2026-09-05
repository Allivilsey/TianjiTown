package org.allivlisey.tianjitown.paper.ui.application;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import org.allivlisey.tianjitown.core.application.ApplicationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationStatusTextTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void providesANonEmptyChineseLabelForEveryApplicationStatus() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        for (ApplicationStatus status : ApplicationStatus.values()) {
            String label = messages.text("dialog." + ApplicationStatusText.messageKey(status));

            assertFalse(label.isBlank(), () -> "缺少状态文案: " + status);
            assertFalse(label.contains("缺少消息配置"), () -> "状态文案键必须只添加一次 dialog. 前缀: " + status);
            assertFalse(label.contains(status.name()), () -> "状态文案泄露枚举名: " + status);
            assertTrue(label.chars().anyMatch(character -> character >= '\u4e00' && character <= '\u9fff'),
                    () -> "状态文案必须为中文: " + status);
        }
    }
}
