# TASK-CN-037I：`TownUiController.java`—动作分发、通知、选址、传送与提交

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：动作分发、通知、选址、传送与提交
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 20 行，已迁移）
- 可见行号：`2652-2653, 2889, 2895, 2903, 2918, 2933, 2939, 2948, 2950, 2958, 2964, 2969, 2980, 2983, 2991, 2998, 3004, 3023-3024`

## 可见文本位置

以下行号按源文件当前版本记录，范围表示包含首尾行：

`2652-2653, 2889, 2895, 2903, 2918, 2933, 2939, 2948, 2950, 2958, 2964, 2969, 2980, 2983, 2991, 2998, 3004, 3023-3024`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 将失败申请恢复确认的标题与后果迁入 `dialog.confirmation.*`；申请/小镇不存在、无生效领地和非镇长设置传送点等运行时详情复用 `chat.application.*`、`chat.runtime.*` 与既有 `chat.site.*` 消息键。
- 将领地传送点选址、位置安全原因、传送结果、不可用/失败/超时提示迁入 `dialog.site.*`；安全校验方法只返回稳定消息键后缀，动态领地结果继续由 `LandProtectionMessages` 解析。消息在使用点读取，支持 `messages.yml` 重载。
- 复核动作分发、通知、投票、税率与提交调用链，保留既有消息键调用；将提交成功提示修正为“管理员审核中，批准前仍可撤回申请。”，避免遗留语义错误。
- 新增默认消息键及分组注释；`TownUiControllerMessagesTest` 与 `PluginMessagesTest` 覆盖默认值、完整渲染、无缺失配置/残留占位符和重载覆盖。相关定向测试 68/68 通过，根项目完整测试通过（core 24、storage 40、integrations 33、paper 193）。
- 当前 037I 对应的动作分发、选址、传送与提交方法区间重新扫描为 0 行非注释 CJK，`git diff --check` 通过。
- 未重新生成全局 `docs/CHINESE_CODE_LINES.md`；仅完成 TASK-CN-037I，其他 TownUiController 子任务及任务目录外文件未在本次处理。
- 例外：无。
