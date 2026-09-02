# TASK-CN-037B：`TownUiController.java`—主菜单、治理与访客

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：主菜单、治理与访客
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 42 行，已迁移）
- 可见行号：`762-764, 770, 773, 775, 777, 780, 782, 786-789, 796-798, 800, 804, 807, 813, 819, 824, 844-845, 848, 851, 860, 863, 868, 880, 883, 886, 889, 917-918, 921, 925, 928, 958, 962, 966, 969`

## 可见文本位置

以下行号按源文件当前版本记录，范围表示包含首尾行：

`762-764, 770, 773, 775, 777, 780, 782, 786-789, 796-798, 800, 804, 807, 813, 819, 824, 844-845, 848, 851, 860, 863, 868, 880, 883, 886, 889, 917-918, 921, 925, 928, 958, 962, 966, 969`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 将 `renderMain` 的小镇摘要、资金/税率/领地单元、待办状态、申请状态、未入镇入口和管理员审核菜单迁入 `dialog.main.*`；余额、税率、数量、审核意见和审核队列数量均使用命名占位符，并在动态意见进入 Dialog 前过滤 legacy 颜色代码。
- 将成员治理中心的标题、当前小镇、待处理入镇申请、成员/申请/访客按钮迁入 `dialog.governance.*`，将访客中心、访客列表和在线邀请页的标题、玩家名、空状态、分页文本迁入 `dialog.visitor.*`。所有文本在使用时通过 `dialogText` 读取，保留消息重载后的下一次输出可见覆盖。
- 新增默认消息键：`dialog.main.*` 23 个、`dialog.governance.*` 7 个、`dialog.visitor.*` 12 个；继续复用已有 `dialog.member-role.*`、`dialog.votes.*`、`dialog.tooltip.*` 和通知/返回按钮键。
- 测试：`TownUiControllerMessagesTest` 覆盖完整占位符渲染、非空/无缺失配置/无残留占位符断言，以及修改 `messages.yml` 后主菜单、治理和访客文案的 reload 覆盖。
- 验证通过：`mvn -B -pl tianjitown-paper -am "-Dtest=TownUiControllerMessagesTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`（4/4）；`mvn -B test`（Core 24、Storage 40、Integrations 33、Paper 180）。目标方法区间重新扫描为 0 行非注释 CJK。
- 无技术例外。本次仅完成 TASK-CN-037B，未处理 037C～037L，也未重新生成全局 `docs/CHINESE_CODE_LINES.md`。
