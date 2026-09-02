# TASK-CN-023：`ProvisionResult.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/ProvisionResult.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/ProvisionResult.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 3 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号按源文件当前版本记录，范围表示包含首尾行：

迁移前原基线为：`15, 24, 29`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- `ProvisionResult` 现在保存配置消息引用或动态原文；成功、并发处理中和超时分支的固定详情/恢复建议迁入 `dialog.provision.*`，在 Dialog 使用时通过 `PluginMessages.rawText` 解析，避免重载后继续使用旧渲染文本。
- 动态失败详情仍作为原文传递，不改变结果状态、申请快照或幂等流程；`TownUiController` 的结果展示改为在使用处解析消息引用。
- 测试：新增 `ProvisionResultTest`，覆盖默认键的完整渲染、无未替换占位符、动态失败文本保留，以及 `messages.yml` 重载后的自定义文案。
- 源文件复扫后非注释可见中文为 0 行；未重新生成全局 `docs/CHINESE_CODE_LINES.md`，其他建镇流程调用方不在 TASK-CN-023 边界内。
