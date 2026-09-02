# TASK-CN-024：`RuleEditorDialogRenderer.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/RuleEditorDialogRenderer.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/RuleEditorDialogRenderer.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 5 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号按源文件当前版本记录，范围表示包含首尾行：

迁移前原基线为：`62, 77, 80, 94, 101`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将规则编辑器的页面版本、显示序号、规则索引和删除请求校验文案迁入 `validation.rule-editor.*` 与 `dialog.rules.delete-request-invalid`；生产渲染和删除目标解析通过 `PluginMessages.rawText` 在使用时读取，保留 Dialog 的 `&` 颜色码。
- 保留无 resolver 重载作为稳定内部调用兼容入口；消息加载或解析失败时仅回退到稳定消息键，不再以内嵌中文作为文案来源。
- 测试：`RuleEditorDialogRendererTest` 覆盖默认键非空、完整异常渲染、无缺失消息/未替换占位符，以及 `messages.yml` 重载后的页面版本和删除请求文案。
- 源文件重新扫描后非注释可见中文为 0 行；未重新生成全局 `docs/CHINESE_CODE_LINES.md`，其他规则编辑调用方不在本单任务边界内。
