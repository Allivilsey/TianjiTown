# TASK-CN-037L：`TownUiController.java`—通用结果、Dialog 渲染与控件辅助

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：通用结果、Dialog 渲染与控件辅助
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 22 行，已迁移）
- 可见行号：`4245-4250, 4315, 4549, 4625, 4629, 4640-4651`

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行；处理后目标位置已无非注释可见中文：

`4245-4250, 4315, 4549, 4625, 4629, 4640-4651`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 通用动作失败的六种 reason 回退详情迁入 `dialog.notice.operation-failed-*`，继续使用 Dialog 文案入口即时解析；后端提供的动态 `detail` 先过滤 `&` 与 `§`，再嵌入现有 `system.operation-failed` 模板。
- Dialog 生命周期异常使用 `log.scheduler.dialog-closed` 的纯文本日志消息；无效领地网格目标使用 `chat.site.invalid-grid-target`；服务台 ID/世界名校验使用 `log.station.record-id-empty` 与 `log.station.record-world-name-empty`，通过类型化校验异常在登记解析日志边界解析配置。
- `ApplicationField` 的标签、校验要求和填写建议改为按字段键从 `dialog.application.field.*` 即时读取，保留字段枚举和业务判断，不缓存渲染文本。
- 新增默认消息键及注释；`TownUiControllerMessagesTest` 覆盖新增键的默认值、非空/无残留占位符检查和 `messages.yml` 热重载覆盖。定向测试 23/23 通过；根项目 clean 全量测试通过（Core 24、Storage 40、Integrations 33、Paper 199）。
- 037L 对应的通用结果、Dialog 生命周期、网格/服务台校验和申请字段辅助区段重新扫描为 0 行非注释 CJK，`git diff --check` 通过。
- 未重新生成全局 `docs/CHINESE_CODE_LINES.md`；本次仅完成 TASK-CN-037L，未处理其他任务范围。
- 例外：无。
