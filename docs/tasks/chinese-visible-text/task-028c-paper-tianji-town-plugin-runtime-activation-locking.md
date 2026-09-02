# TASK-CN-028C：`TianjiTownPlugin.java`—运行时激活、周期任务与锁定

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：运行时激活、周期任务与锁定
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TianjiTownPlugin.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TianjiTownPlugin.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 21 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行：

`401-402, 433, 435, 483, 492, 495, 498, 501, 505, 508, 511, 516, 520, 533-535, 537, 551, 563, 571`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 将业务运行时激活失败、运行时初始化失败、运行时门禁原因和三条就绪状态迁入 `diagnostic.lifecycle.*`；异常详情使用 `{detail}`，WorldBorder API 加载失败使用 `diagnostic.world-border.api-load-failure`。
- 将 Residence 名称清单读取失败、运行时启动完成和锁定日志迁入 `log.lifecycle.*`；锁定日志通过 `{reason}` 接收当前门禁原因。
- 九个周期任务改为传递稳定消息键，在任务实际失败时通过当前 `PluginMessages` 解析 `log.scheduler.periodic.*`；周期失败抑制集合继续使用稳定键，不缓存渲染后的任务名称。
- 新增的启动期诊断键加入 `PluginMessages.validateRequiredMessages()`；异常详情和锁定原因在纯文本日志边界使用安全文本，避免 `&`/`§` 注入日志颜色。
- 测试覆盖全部新增默认文案、完整占位符渲染、无残留占位符，以及 `messages.yml` 重载后激活、周期失败和锁定日志使用自定义值。
- 验证：Paper 定向 `PluginMessagesTest` 通过；源文件复扫后，原 21 个命中位置均无非注释可见中文。未重新生成全局 `docs/CHINESE_CODE_LINES.md`，也未处理 028A/028B 或其他任务范围。
