# TASK-CN-026：`SitePolicy.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/SitePolicy.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/SitePolicy.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 9 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行；处理后源文件已无非注释可见中文：

`212, 224, 244, 311, 316, 320, 340, 349, 372`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将预览范围标签迁入 `chat.site.preview-scope-single` 与 `chat.site.preview-scope-multiple`，预览开始提示继续使用 `chat.site.preview-started`；`{count}` 和 `{duration}` 在每次预览开始时解析。
- 将预览区域参数校验迁入 `validation.site.areas-required`、`validation.site.world-mismatch`、`validation.site.focus-missing`、`validation.site.blacklist-integer-required` 和 `validation.site.blacklist-bounds-invalid`；黑名单解析、预览对象失效和清理任务失败日志迁入 `log.site.blacklist-invalid-area`、`log.site.preview-invalidated` 和 `log.site.preview-cancel-failed`。
- `SitePolicy` 的玩家提示、异常详情和控制台日志均在使用时通过 `PluginMessages.plainText` 解析；静态校验辅助方法接收 resolver，动态路径、字段和异常详情会隔离 `&`/`§`，不缓存已渲染文案。
- 测试：新增 `SitePolicyTest`，覆盖全部新增键的非空/占位符渲染、三类预览区域校验、单区域/多区域范围标签和 `messages.yml` 重载后的下一次输出。定向测试、`mvn -B -pl tianjitown-paper -am test`、`mvn -B test` 全部通过。
- 源文件复扫后非注释可见中文为 0 行，仅保留注释；未重新生成全局 `docs/CHINESE_CODE_LINES.md`，未修改 TASK-CN-027 及后续调用方的外层文案。
