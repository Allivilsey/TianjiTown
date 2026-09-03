# TASK-CN-037E：`TownUiController.java`—规则确认、申请详情与小镇规则

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：规则确认、申请详情与小镇规则
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 39 行，已迁移）
- 可见行号：`1566-1570, 1572, 1576, 1579, 1583, 1586, 1589, 1593, 1598, 1603, 1607, 1613, 1620, 1625, 1629, 1634-1636, 1642, 1650-1652, 1655, 1659, 1662, 1667, 1678, 1682, 1688, 1712, 1797, 1800, 1803, 1814, 1851`

## 可见文本位置

以下行号按源文件当前版本记录，范围表示包含首尾行：

`1566-1570, 1572, 1576, 1579, 1583, 1586, 1589, 1593, 1598, 1603, 1607, 1613, 1620, 1625, 1629, 1634-1636, 1642, 1650-1652, 1655, 1659, 1662, 1667, 1678, 1682, 1688, 1712, 1797, 1800, 1803, 1814, 1851`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 将规则确认页、建镇申请摘要和小镇详情菜单的固定文案迁入 `dialog.rules.*`、`dialog.application.*`、`dialog.town.*`；小镇不存在的读取错误使用 `chat.runtime.town-not-found`，规则删除边界错误使用 `dialog.rules.minimum-one`、`dialog.rules.delete-missing`、`dialog.rules.delete-conflict`。
- 申请人、初始成员、申请资料、规则、审核意见、自动创建错误、小镇简介等动态值改为命名占位符，并在进入 Dialog 前过滤 `&`/`§`；稳定申请状态和初始成员状态继续通过状态到消息键的映射展示。
- 规则确认、规则查看和规则编辑均在使用时读取 `PluginMessages`；默认键存在且非空，消息重载后的覆盖由 `TownUiControllerMessagesTest` 验证。
- 测试：`TownUiControllerMessagesTest` 定向测试 9/9 通过。
- 验证通过：`mvn -B -pl tianjitown-paper -am test`（Core 24、Storage 40、Integrations 33、Paper 185）；`mvn -B test`（同样 24/40/33/185）；目标范围重新扫描为 0 行非注释 CJK，`git diff --check` 通过。
- 未重新生成全局 `docs/CHINESE_CODE_LINES.md`；仅完成 TASK-CN-037E，未处理 037F～037L 及后续任务。
- 例外：无。
