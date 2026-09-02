# TASK-CN-016：`BuffSettings.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/BuffSettings.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/BuffSettings.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 10 行，已迁移）
- 可见行号：无

## 可见文本位置

以下为迁移前的原基线行号，范围表示包含首尾行；处理后源文件已无非注释可见中文：

`26, 40, 43, 71, 81, 91, 99, 115, 123, 131`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将未知 Buff、目录为空/超限、重复 key、购买角色为空、配置节/文本为空、价格范围和枚举值错误等 10 个配置与运行期校验文案迁入 `validation.buff.*`，动态值改用 `{key}`、`{path}`、`{value}` 和 `{maximum}` 占位符。
- `BuffSettings` 注入 `BiFunction<String, Map<String, ?>, String>` resolver；设置对象保存 resolver，使 `requireBuff` 在实际调用时读取当前 `messages.yml`。启动配置门禁和运行时构造分别由 `TianjiTownPlugin`、`RuntimeConfigurationValidator` 与 `TownRuntime` 传入 `messages()::plainText`。原有无 resolver 构造/加载入口保留稳定消息键回退，不作为生产文案来源。
- 新增默认键：`validation.buff.unknown`、`validation.buff.catalog-required`、`validation.buff.catalog-limit`、`validation.buff.duplicate-key`、`validation.buff.purchasing-roles-required`、`validation.buff.section-required`、`validation.buff.value-required`、`validation.buff.price-overflow`、`validation.buff.price-range`、`validation.buff.enum-unsupported`。
- 测试：`BuffSettingsTest` 覆盖 resolver、价格范围异常和未知 Buff 在 `messages.yml` 重载后的新文案；`PluginMessagesTest` 覆盖 10 个默认键的完整占位符渲染、非空/无缺失配置提示和重载覆盖值。
- 验证通过：`mvn -B clean -pl tianjitown-paper -am '-Dtest=BuffSettingsTest,PluginMessagesTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`、`mvn -B -pl tianjitown-paper -am test`、`mvn -B test`；Paper 109 项测试、根项目全量测试均通过。目标源文件重新扫描后非注释可见中文为 0 行。
- 例外：无。本任务未重新生成全局 `docs/CHINESE_CODE_LINES.md`；其他配置校验器的命中文案仍由各自任务负责。
