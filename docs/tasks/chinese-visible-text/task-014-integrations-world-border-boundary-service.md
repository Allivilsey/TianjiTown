# TASK-CN-014：`WorldBorderBoundaryService.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-integrations`
- 源文件：[`tianjitown-integrations/src/main/java/cn/tianji/town/integrations/worldborder/WorldBorderBoundaryService.java`](../../../tianjitown-integrations/src/main/java/cn/tianji/town/integrations/worldborder/WorldBorderBoundaryService.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线：6）
- 可见行号：无

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行；处理后源文件已无非注释可见中文：

`32, 74, 87, 100, 109, 118`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 为 `WorldBorderBoundaryService` 注入 `BiFunction<String, Map<String, ?>, String>` 消息解析器；Paper 运行时在创建服务时传入 `messages()::plainText`，旧构造器保留稳定消息键回退，integrations 不读取 Paper 配置。
- 将主线程约束、WorldBorder 启用状态、反射能力缺失、返回值校验和 API 访问/调用失败等固定文案迁入 `diagnostic.world-border.*`；异常仍保留原始 cause，运行时消息按调用时 resolver 解析，供选址失败详情和启动门禁诊断使用。
- `WorldBorderBoundaryServiceTest` 覆盖经典 WorldBorder 反射能力、四角缓冲检查、缺失边界、主线程约束及注入 resolver 的自定义文案；`PluginMessagesTest` 覆盖 6 个默认键的非空渲染、无缺失键提示和 `messages.yml` 重载后的用户覆盖值。
- 验证通过：`mvn -B -pl tianjitown-integrations -am test`（Core 24、Integrations 33）、`mvn -B -pl tianjitown-paper -am '-Dtest=PluginMessagesTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`（PluginMessages 22）、根项目 `mvn -B test` 及 clean 全量 `mvn -B clean test`（Core 24、Storage 40、Integrations 33、Paper 104）。目标源文件重新扫描后非注释可见中文为 0 行。
- 例外：无。按目录规则，本任务未重新生成全局 `docs/CHINESE_CODE_LINES.md`。
