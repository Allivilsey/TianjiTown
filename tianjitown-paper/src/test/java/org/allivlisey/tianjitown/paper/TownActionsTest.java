package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TownActionsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void invalidMiniMessageTextAlwaysUsesValidationFailedReason() {
        ApplicationText text = new ApplicationText("<red>镇", "TJ", "SKY", "简介",
                List.of("友善交流"));
        AtomicReference<TownActionOutcome<Object>> outcome = new AtomicReference<>();

        assertFalse(TownActions.validateText("APPLICATION_CREATE", text, outcome::set));
        assertEquals("VALIDATION_FAILED", outcome.get().result().reason());
    }

    @Test
    void rendersTownActionMessagesAndUsesReloadedOverrides() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("申请不存在", messages.plainText("chat.application.not-found"));
        assertEquals("领地名与已存在的领地重复。",
                messages.plainText("chat.site-validation.residence-name-conflict"));
        assertEquals("捐款金额必须大于 0",
                messages.plainText("validation.vault.donation-amount-positive"));
        assertEquals("同步 Residence 成员失败 town=town-1: boom",
                messages.plainText("log.residence.member-sync-failure",
                        Map.of("town", "town-1", "detail", "boom")));
        assertEquals("原因不能为空。", messages.plainText("dialog.review.empty-error"));
        assertEquals("原因不能超过 500 个字符。",
                messages.plainText("dialog.review.too-long-error"));

        for (String key : List.of(
                "chat.application.not-found",
                "chat.site-validation.residence-name-conflict",
                "validation.vault.donation-amount-positive",
                "dialog.review.empty-error",
                "dialog.review.too-long-error",
                "log.residence.member-sync-failure")) {
            String rendered = messages.plainText(key, Map.of(
                    "town", "town-1", "detail", "boom"));
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }

        YamlConfiguration override = new YamlConfiguration();
        override.set("chat.application.not-found", "自定义申请状态");
        override.set("chat.site-validation.residence-name-conflict", "自定义领地名冲突");
        override.set("log.residence.member-sync-failure", "自定义同步日志 {town}/{detail}");
        override.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义申请状态", messages.plainText("chat.application.not-found"));
        assertEquals("自定义领地名冲突",
                messages.plainText("chat.site-validation.residence-name-conflict"));
        assertEquals("自定义同步日志 town-1/boom",
                messages.plainText("log.residence.member-sync-failure",
                        Map.of("town", "town-1", "detail", "boom")));
    }

}
