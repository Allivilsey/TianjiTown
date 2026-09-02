# TASK-CN-004：`LandProtectionService.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-core`
- 源文件：[`tianjitown-core/src/main/java/cn/tianji/town/core/ports/LandProtectionService.java`](../../../tianjitown-core/src/main/java/cn/tianji/town/core/ports/LandProtectionService.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 5 行，已迁移）
- 可见行号：无

## 可见文本位置

处理后重新扫描源文件，已无可见中文文本；原基线行号为：

`42, 48, 54, 67, 73`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将四个默认能力失败和多区域检查分支改为稳定的 `ResultCode`：`UNSUPPORTED_TELEPORT_POINT`、`UNSUPPORTED_MULTI_AREA`、`UNSUPPORTED_ADD_AREA`、`UNSUPPORTED_REMOVE_AREA`；结果同时保留参数容器，core 不再生成玩家可见中文，也不依赖 Paper/Bukkit 配置。
- 为当前仍返回已渲染详情的旧适配器保留兼容工厂和 `message()` 访问器；Paper 新增 `LandProtectionMessages`，在命令、Dialog、日志、审计和持久化详情的使用点解析 `chat.land-protection.*`，不会把稳定代码直接展示给用户。
- 新增默认消息键：`chat.land-protection.unsupported-teleport-point`、`unsupported-multi-area`、`unsupported-add-area`、`unsupported-remove-area`；测试覆盖内置值、无缺失配置、纯文本详情、代码映射和 `messages.yml` 重载后的用户覆盖值。
- 测试：`mvn -pl tianjitown-paper -am clean test` 通过（169 tests）。目标文件重新扫描后可见中文为 0 行。
- 例外：无。`docs/CHINESE_CODE_LINES.md` 保留为全量迁移基线，未在本单任务中重生成；`ResidenceLandProtectionService` 的旧详情迁移属于后续 `task-011`。
