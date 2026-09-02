# TASK-CN-029：`TownActions.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownActions.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownActions.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 7 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行；处理后目标位置已无非注释可见中文：

`94, 101, 144, 418, 556, 560, 562`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将申请不存在和领地名称冲突详情迁入 `chat.application.not-found`、`chat.site-validation.residence-name-conflict`；捐款金额校验复用既有 `validation.vault.donation-amount-positive`，审核原因校验复用既有 `dialog.review.empty-error` 和 `dialog.review.too-long-error`。
- 三处 Residence 成员同步日志统一使用 `log.residence.member-sync-failure`，通过 `{town}` 和 `{detail}` 传入小镇 UUID 与技术详情；日志详情在嵌入配置模板前转义 `&`/`§`，避免第三方或异常文本污染日志颜色。
- 申请缺失使用稳定的内部异常标记后再映射为 `NOT_FOUND`，错误分类不依赖自定义翻译文本；所有文案在调用时通过当前 `PluginMessages` 解析，支持重载后的下一次输出。
- 测试：`TownActionsTest` 覆盖新增/复用键的完整渲染、无残留占位符和 `messages.yml` 重载后的自定义值。
- 验证：目标文件复扫后，任务列出的 7 个原基线位置均无非注释可见中文。`TownActions.java` 中另有 5 个由清单标为 `❌` 的内部/审计原因文本，属于本任务明确边界之外，未修改；未重新生成全局 `docs/CHINESE_CODE_LINES.md`。
