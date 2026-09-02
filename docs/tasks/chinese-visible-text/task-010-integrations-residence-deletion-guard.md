# TASK-CN-010：`ResidenceDeletionGuard.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-integrations`
- 源文件：[`tianjitown-integrations/src/main/java/cn/tianji/town/integrations/residence/ResidenceDeletionGuard.java`](../../../tianjitown-integrations/src/main/java/cn/tianji/town/integrations/residence/ResidenceDeletionGuard.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 4 行，已迁移）
- 可见行号：无

## 可见文本位置

处理后重新扫描源文件，已无可见中文文本；原基线行号为：

`73, 85, 98, 105`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- Residence 删除事件的非玩家来源日志、删除后的对账恢复失败日志和失败关闭日志均改为在输出时通过注入的日志消息解析器解析；玩家删除提示继续使用 `messages()::text`，生产日志使用 `messages()::plainText`，因此重载后下一次日志输出会使用新文案。
- 新增默认消息键：`log.residence.non-player-deletion-cancelled`、`log.residence.deletion-recovery-failure`、`log.residence.deletion-guard-failure`；分别使用 `{residence}` 和 `{detail}` 占位符，保留原有完整日志语义。
- 领地名和异常详情中的 `&`/`§` 会在进入日志解析前转义，避免动态值注入颜色控制符；解析器失效时仅回退到稳定消息键和参数，不让 integrations 读取 Paper 配置。
- 测试覆盖完整默认日志渲染、无缺失配置/残留占位符、动态值转义、解析器失败回退，以及 `messages.yml` 重载后的用户覆盖值。
- 目标源文件重新扫描后非注释可见中文为 0 行；保留的中文仅位于技术注释中。
- 验证：`mvn -pl tianjitown-integrations -am test`、`mvn -pl tianjitown-paper -am test` 和根项目 `mvn test` 均通过（根项目共 184 个测试：Core 24、Storage 40、Integrations 24、Paper 96）。
- 例外：无。`docs/CHINESE_CODE_LINES.md` 保留为全量迁移基线，未在本单任务中重生成。
