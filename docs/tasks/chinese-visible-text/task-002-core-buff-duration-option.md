# TASK-CN-002：`BuffDurationOption.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-core`
- 源文件：[`tianjitown-core/src/main/java/cn/tianji/town/core/consumption/BuffDurationOption.java`](../../../tianjitown-core/src/main/java/cn/tianji/town/core/consumption/BuffDurationOption.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 4 行，已移除）
- 可见行号：无

## 可见文本位置

处理后重新扫描源文件，已无可见中文文本；原基线行号为：

`7-10`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- `BuffDurationOption` 的四个本地化 `displayName` 仅存在于 core 枚举，仓库内没有生产或测试调用，也不会到达玩家、管理员、控制台或诊断渠道；按 core 不生成展示文案的边界将其移除。
- 保留 `ONE_HOUR`、`ONE_DAY`、`ONE_WEEK`、`ONE_MONTH` 稳定枚举值，以及小时数、折扣、`duration()` 和 `parse()` 行为；未修改计费、持久化或命令参数。
- 因无运行时展示调用点，本任务不新增 `messages.yml` 键，也不存在可验证的热重载输出；现有 `ConsumptionRulesTest` 新增稳定业务值断言，覆盖四个时长和折扣。
- 定向测试：`mvn -pl tianjitown-core -am clean test` 通过（21 tests）。目标文件重新扫描后仅剩清单明确标记为 ❌ 的两条内部 `parse` 校验异常，不属于本任务可见文本。
- 例外：无。`docs/CHINESE_CODE_LINES.md` 保留为全量迁移基线，未在本单任务中重生成。
