# TASK-CN-037K：`TownUiController.java`—捐款、表单保存与初始成员确认

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：捐款、表单保存与初始成员确认
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 18 行，已迁移）
- 可见行号：`3850, 3889, 3948, 3950, 3957, 3999-4002, 4014, 4079, 4082, 4087, 4117, 4188, 4192, 4194, 4199`

## 可见文本位置

以下行号按源文件当前版本记录，范围表示包含首尾行：

`3850, 3889, 3948, 3950, 3957, 3999-4002, 4014, 4079, 4082, 4087, 4117, 4188, 4192, 4194, 4199`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 捐款账户归属、金额校验和申请不存在提示分别复用 `chat.runtime.town-required`、`validation.vault.donation-amount-positive` 与 `chat.application.not-found`；无效金额文案按当前 Dialog 配置读取。
- 将初始成员必填、在线、申请人排除、重复校验、预检失败、成员冲突及未知值提示迁入 `dialog.application.*`；保存前预检和提交竞态冲突均使用命名占位符，动态玩家/小镇文本先过滤 `&` 与 `§`。
- 将已提交申请草稿清理失败日志迁入 `log.application.draft-cleanup-failure`，以纯文本读取配置并对玩家 UUID、异常详情做安全占位符渲染；初始成员邀请和响应通知中的动态名称同样完成过滤。
- 新增默认消息键及注释；`TownUiControllerMessagesTest` 覆盖默认值、完整渲染、无缺失配置/残留占位符和 `messages.yml` 热重载覆盖。定向测试 21/21 通过，根项目完整测试通过（core 24、storage 40、integrations 33、paper 197）。
- 037k 对应源代码方法区重新扫描为 0 行非注释 CJK，`git diff --check` 通过；未重新生成全局 `docs/CHINESE_CODE_LINES.md`。
- 本次仅完成 TASK-CN-037K，未处理 037L 或其他任务范围。
- 例外：无。
