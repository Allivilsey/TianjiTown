# TASK-CN-038：`config.yml` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/resources/config.yml`](../../../tianjitown-paper/src/main/resources/config.yml)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**
- 可见行号：`无`

## 可见文本位置

处理前命中行号为 `78, 89`；按当前源文件重新扫描后已无非注释中文可见文本。

## 处理记录

- 从 `config.yml` 的 `buffs.catalog.speed/health` 移除 `display-name`；效果类型、效果键、价格、等级和其他业务字段仍保留在配置中。
- 在 `messages.yml` 增加 `dialog.buff.labels.<buffKey>` 标签约定及内置 `speed`/`health` 默认值；`BuffSettings` 对每个 catalog key 执行启动期缺失标签门禁，支持自定义 Buff。
- Buff 商店、管理员发放确认和购买账本均按使用时的消息标签解析；`messages.yml` 重载后后续 UI、确认和新账本记录使用覆盖值，稳定的 Buff key、业务配置和幂等键保持不变。
- 新增默认键存在性、默认标签、自定义标签缺失门禁及重载覆盖测试；`mvn -B clean test` 全量通过（298 项，无失败）。
- 仅扫描本任务源文件的非注释中文：0 行；未修改全局中文索引或其他任务文件。

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。
