# TASK-CN-027：`TerritoryService.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TerritoryService.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TerritoryService.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 12 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行；处理后源文件已无非注释可见中文：

`54, 58, 64, 67, 73, 97, 194, 196, 204, 227, 235, 289`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将批量扩张的 6 个校验提示迁入 `validation.territory.batch-selection-required`、`validation.territory.batch-capacity-exceeded`、`validation.territory.batch-grid-out-of-bounds`、`validation.territory.batch-duplicate-selection`、`validation.territory.batch-occupied-selection` 和 `validation.territory.batch-not-connected`；镇身份、初始领地和容量校验迁入其余 `validation.territory.*` 键。
- 将地图中不可扩张单元的非相邻详情复用 `dialog.territory.cell.not-adjacent-detail`，相邻但暂不可用的情况使用新增 `dialog.territory.cell.unavailable-detail`；通过结构化相邻判断避免把 core 层异常原文泄露到玩家界面。
- 所有玩家可见异常和单元详情均在使用时通过 `PluginMessages.plainText` 解析，不缓存渲染后的文案；`TerritoryMap` 的 25 格数量不变量改为稳定技术标识 `territory.map-size-invalid`，该异常仅用于内部不变量失败，不作为玩家文案。
- 测试：新增 `TerritoryServiceTest`，覆盖全部新增键的非空/占位符渲染、批量扩张和身份/容量校验、地图详情、`messages.yml` 重载后的下一次输出，以及无效地图形状的技术异常标识。定向测试、`mvn -B -pl tianjitown-paper -am test`、`mvn -B test` 全部通过。
- 源文件复扫后非注释可见中文为 0 行，仅保留注释；未重新生成全局 `docs/CHINESE_CODE_LINES.md`，未修改 TASK-CN-028 及后续调用方的外层文案。
