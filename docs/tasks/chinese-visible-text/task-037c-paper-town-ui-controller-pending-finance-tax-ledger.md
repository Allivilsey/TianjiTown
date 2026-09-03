# TASK-CN-037C：`TownUiController.java`—待办、个人中心、资金、税率与账本

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：待办、个人中心、资金、税率与账本
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 47 行，已迁移）
- 可见行号：`1005-1006, 1012, 1023, 1027, 1036-1039, 1043, 1048, 1052, 1060, 1068-1071, 1073, 1075-1076, 1078, 1080, 1084, 1086, 1092, 1095, 1098, 1104, 1107, 1117, 1119-1122, 1125, 1182, 1192-1194, 1197-1198, 1207, 1209-1210, 1214, 1218, 1221`

## 可见文本位置

以下行号按源文件当前版本记录，范围表示包含首尾行：

`1005-1006, 1012, 1023, 1027, 1036-1039, 1043, 1048, 1052, 1060, 1068-1071, 1073, 1075-1076, 1078, 1080, 1084, 1086, 1092, 1095, 1098, 1104, 1107, 1117, 1119-1122, 1125, 1182, 1192-1194, 1197-1198, 1207, 1209-1210, 1214, 1218, 1221`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 将待办中心、个人中心、公共资产、税率设置与小镇账本的固定可见文本迁入 `dialog.pending.*`、`dialog.personal.*`、`dialog.finance.*`、`dialog.tax.*` 和 `dialog.ledger.*`；不属于小镇的运行时错误统一使用 `chat.runtime.town-required`。
- 账本金额、余额、税率、数量、分页、刷新时间、操作人和备注均使用命名占位符；玩家名、小镇名、金额及运行时字符串在进入消息解析前隔离 `&`/`§` 控制符。账本流水类型保留稳定代码，在 Paper 展示层映射到 `dialog.ledger.type.*`，未知类型和未知玩家也由配置文案承接。
- 通过 `TownUiControllerMessagesTest` 验证默认完整渲染、非空/无缺失键/无残留占位符、配置覆盖与 `messages.yml` reload；定向测试共 6/6 通过。
- 验证通过：`mvn -B -pl tianjitown-paper -am test`（Core 24、Storage 40、Integrations 33、Paper 182）；`mvn -B test`（Core 24、Storage 40、Integrations 33、Paper 182）。037C 功能方法和账本辅助逻辑重新扫描为 0 行非注释 CJK。
- `renderFinance` 中的 `§d公共 Buff` 与 `§b领地扩张` 属于后续 TASK-CN-037D，本次保持未处理；未重新生成全局 `docs/CHINESE_CODE_LINES.md`。
- 例外：无。仅完成 TASK-CN-037C，未处理 037D 及后续任务。
