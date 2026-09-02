# TASK-CN-021：`OnlineBackupService.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/OnlineBackupService.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/OnlineBackupService.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 8 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号按源文件当前版本记录，范围表示包含首尾行：

迁移前原基线为：`43, 93, 98, 115, 119, 122, 146, 180`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将主线程约束、备份目录边界/真实路径校验、唯一文件名耗尽和 SHA-256 能力异常迁入 `diagnostic.backup.*` 与 `validation.backup.*`；路径使用 `{path}` 命名占位符，保留异常 cause 和原有安全边界逻辑。
- `OnlineBackupService` 的实例调用将当前 `PluginMessages::plainText` 解析器传入静态辅助方法；无解析器的测试入口仅回退到稳定消息键，不缓存新的渲染文案。动态路径会先转义 `&`/`§`，避免配置路径污染消息格式。
- 测试：`OnlineBackupServiceTest` 覆盖 8 个默认键的非空/完整占位符渲染、绝对路径错误和 `messages.yml` 重载后的自定义文案；定向测试 5/5 通过，`mvn -pl tianjitown-paper -am test` 全部通过。
- 源文件复扫后非注释可见中文为 0 行，仅保留原有注释；未重新生成全局 `docs/CHINESE_CODE_LINES.md`，其他备份调用方不在 TASK-CN-021 边界内。
