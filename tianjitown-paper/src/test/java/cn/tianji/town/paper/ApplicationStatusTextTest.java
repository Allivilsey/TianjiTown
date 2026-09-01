package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationStatus;
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
            String label = messages.text(ApplicationStatusText.messageKey(status));

            assertFalse(label.isBlank(), () -> "缺少状态文案: " + status);
            assertFalse(label.contains(status.name()), () -> "状态文案泄露枚举名: " + status);
            assertTrue(label.chars().anyMatch(character -> character >= '\u4e00' && character <= '\u9fff'),
                    () -> "状态文案必须为中文: " + status);
        }
    }
}
