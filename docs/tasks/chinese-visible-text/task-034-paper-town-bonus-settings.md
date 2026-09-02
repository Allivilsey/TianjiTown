# TASK-CN-034：`TownBonusSettings.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownBonusSettings.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownBonusSettings.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 19 行，已迁移）
- 可见行号：无

## 可见文本位置

以下为迁移前的原基线行号，范围表示包含首尾行；处理后源文件已无非注释可见中文：

`32, 35, 39, 45, 49, 60, 73, 79, 93, 96, 99, 114, 125, 131, 142, 147, 159, 166, 172`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将建筑返还概率、周上限、计数保留周期、重置时区、黑名单、信标刷新周期、允许世界、QuickShop 诊断天数、备份范围和备份目录校验文案迁入 `validation.bonus.*`；整数类型、布尔类型、数字类型、长整数、文本和文本列表校验复用已有 `validation.configuration.*` 消息。黑名单材料值使用 `{value}`，配置路径与范围使用 `{path}`、`{minimum}`、`{maximum}` 占位符。
- `TownBonusSettings.load` 新增 resolver 重载；`RuntimeConfigurationValidator` 和 `TownRuntime` 的生产调用均传入 `messages()::plainText`，异常文案按每次配置读取解析，不缓存已渲染文本。无 resolver 入口保留稳定消息键与路径诊断回退。
- 新增默认键：`validation.bonus.building-refund-chance-range`、`integer-range`、`building-refund-weekly-limit-range`、`building-refund-retention-range`、`building-refund-reset-zone-invalid`、`building-refund-blacklist-required`、`building-refund-blacklist-material-invalid`、`beacon-refresh-interval-range`、`beacon-worlds-required`、`diagnostic-days-range`、`backup-range`、`backup-directory-required`。
- 测试：`TownBonusSettingsTest` 覆盖新增键的完整占位符渲染、全部自定义校验分支和 `messages.yml` 重载后的下一次配置读取。
- 验证通过：`mvn -B -pl tianjitown-paper -am "-Dtest=TownBonusSettingsTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`（5/5）、`mvn -B -pl tianjitown-paper -am test`（Core 24、Storage 40、Integrations 33、Paper 161）、`mvn -B test`（同上）。目标源文件重新扫描后非注释可见中文为 0 行。
- 未重新生成全局 `docs/CHINESE_CODE_LINES.md`，未处理其他任务；仅修改对应生产调用方以传入 resolver，并保留配置解析失败时的稳定 bootstrap 回退逻辑。
