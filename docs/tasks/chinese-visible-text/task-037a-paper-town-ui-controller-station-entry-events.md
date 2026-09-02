# TASK-CN-037A：`TownUiController.java`—服务台、手册、主入口与事件通知

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：服务台、手册、主入口与事件通知
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 10 行，已迁移）
- 可见行号：无

## 可见文本位置

处理后重新扫描 `TownUiController.java`，本任务基线中的固定可见文本已全部迁移；原基线行号如下：

`134, 233, 321, 372, 467, 469-470, 487, 587, 596`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 将停服逐玩家 UI 关闭失败、服务台公共归属、服务台登记无效/缺失整数诊断迁入 `log.lifecycle.ui-viewer-close-failure`、`chat.station.public-owner`、`log.station.invalid-record`、`log.station.invalid-record-missing-integer`；异常详情和字段使用命名占位符，并按纯文本输出。
- 将手册书名、显示名、页面内容和主入口加载标题迁入 `handbook.item-title`、`handbook.item-display-name`、`handbook.item-pages`、`dialog.main.loading-title`；手册页面用 YAML 双引号保留换行，并在生成时读取最新消息配置。
- 审批 NEED_CHANGES/REJECTED 的缺失审核原因改为 `chat.notification.application-needs-changes-default-reason` / `chat.notification.application-rejected-default-reason`，通知保留现有完整模板；审核原因进入 Component 前隔离 legacy 控制符。
- 测试：`TownUiControllerMessagesTest` 覆盖默认完整渲染、全部占位符、无缺失键/残留占位符、配置覆盖和 `messages.yml` reload；定向测试、Paper 相关全量测试及根项目全量测试均通过。
- 验证：本任务原 10 个命中位置重新扫描为 0；未重新生成全局 `docs/CHINESE_CODE_LINES.md`。
- 例外：无。仅完成 TASK-CN-037A，未处理 037B～037L。
