# TASK-CN-003：`ExpansionDirection.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-core`
- 源文件：[`tianjitown-core/src/main/java/cn/tianji/town/core/land/ExpansionDirection.java`](../../../tianjitown-core/src/main/java/cn/tianji/town/core/land/ExpansionDirection.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 4 行，已移除）
- 可见行号：无

## 可见文本位置

处理后重新扫描源文件，已无可见中文文本；原基线行号为：

`6-9`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- `ExpansionDirection` 的四个中文 `displayName` 仅存在于 core 枚举，仓库内没有生产或测试调用，不会到达玩家、管理员、控制台或诊断渠道；按 core 不生成展示文案的边界将其移除。
- 保留 `NORTH`、`EAST`、`SOUTH`、`WEST` 稳定枚举值、网格坐标以及 `parse()` 的英文/中文输入兼容性；未修改扩张规则、命令参数或持久化值。
- 因无运行时展示调用点，本任务不新增 `messages.yml` 键；`parse()` 中的中文输入别名和内部异常仍属于 `docs/CHINESE_CODE_LINES.md` 明确标记为 ❌ 的非展示文本。
- 测试：`ExpansionRulesTest` 新增四个方向的稳定坐标和解析兼容性断言；`mvn -pl tianjitown-core -am test` 与根项目 `mvn test` 均通过。
- 例外：无。`docs/CHINESE_CODE_LINES.md` 保留为全量迁移基线，未在本单任务中重生成。
