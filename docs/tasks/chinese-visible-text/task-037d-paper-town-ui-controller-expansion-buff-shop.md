# TASK-CN-037D：`TownUiController.java`—领地扩张与 Buff 商店

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：领地扩张与 Buff 商店
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 17 行，已迁移）
- 可见行号：`1372, 1374-1375, 1388, 1505-1517`

## 可见文本位置

以下行号按源文件当前版本记录，范围表示包含首尾行：

`1372, 1374-1375, 1388, 1505-1517`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 将资金菜单中的 `公共 Buff`、`领地扩张` 入口标签迁入 `dialog.finance.buff` 和 `dialog.finance.expansion`。
- 将 Buff 商店的摘要标题、可购买/暂停说明和菜单标题迁入 `dialog.buff.shop-summary-title`、`dialog.buff.shop-enabled-hint`、`dialog.buff.shop-paused-hint` 和 `dialog.buff.shop-title`；所有调用点通过 `dialogText` 在使用时解析，支持 `messages.yml` 重载后的下一次输出。
- 原任务行号中的账本类型文本已在 TASK-CN-037C 中迁移；本次只补齐 037D 功能范围内仍存在的扩张与 Buff 商店固定文案。配置中的 Buff `display-name` 属于 TASK-CN-038，保持稳定业务配置边界不变。
- 通过 `TownUiControllerMessagesTest` 验证默认完整渲染、键非空/无缺失配置/无残留占位符，以及修改 `messages.yml` 后的覆盖与 reload；定向测试共 7/7 通过。
- 目标方法区间重新扫描为 0 行非注释 CJK；未重新生成全局 `docs/CHINESE_CODE_LINES.md`。
- 例外：无。仅完成 TASK-CN-037D，未处理 037E～037L 及后续任务。
