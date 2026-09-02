# TASK-CN-025：`RuntimeConfigurationValidator.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/RuntimeConfigurationValidator.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/RuntimeConfigurationValidator.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 10 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行；处理后源文件已无非注释可见中文：

`31, 40, 67, 71, 78, 88, 94, 99, 112-113`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将未加载世界、数据库文件为空、黑名单结构/世界/坐标、黑名单字段类型和运行时范围校验文案迁入 `validation.runtime-configuration.*`；动态值使用 `{worlds}`、`{world}`、`{path}`、`{minimum}` 和 `{maximum}` 占位符。
- `RuntimeConfigurationValidator` 新增 resolver 重载，并将 `messages()::plainText` 贯穿运行时配置、数据库设置、范围校验和黑名单字段校验；文案在每次校验时读取，支持重载后的下一次输出。无 resolver 入口保留稳定键与参数诊断回退。
- 测试：`RuntimeConfigurationValidatorTest` 覆盖 9 个默认键的非空/完整占位符渲染、全部目标失败分支，以及重载 `messages.yml` 后范围错误采用自定义文案。
- 验证：`mvn -B -pl tianjitown-paper -am "-Dtest=RuntimeConfigurationValidatorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`、`mvn -B -pl tianjitown-paper -am test`、`mvn -B test` 全部通过；目标源文件复扫为 0 行非注释可见中文。
- 例外与边界：未重新生成全局 `docs/CHINESE_CODE_LINES.md`；`TianjiTownPlugin.resolveDatabaseUrl()` 中同一数据库空值保护属于 TASK-CN-028B，不在本单任务修改范围内。
