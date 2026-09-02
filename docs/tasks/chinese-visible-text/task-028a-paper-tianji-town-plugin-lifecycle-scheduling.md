# TASK-CN-028A：`TianjiTownPlugin.java`—生命周期、重载与线程调度

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：生命周期、重载与线程调度
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TianjiTownPlugin.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TianjiTownPlugin.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 19 行，已迁移或记录为启动期技术例外）
- 可见行号：无

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行：

`36, 53, 61, 69, 76, 78-79, 86, 101, 113-114, 127, 132, 140, 171, 199, 222, 242, 255`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 将启动门禁状态和配置校验结果迁入 `diagnostic.lifecycle.*`，调度参数异常迁入 `diagnostic.scheduler.negative-delay`；停服清理、异步等待和主线程调度异常分别迁入 `log.lifecycle.*` 与 `log.scheduler.*`，动态异常详情使用 `{detail}`，未结束任务数使用 `{active}`。
- `onEnable()` 在启动线程池和写入门禁状态前完成默认资源保存与 `PluginMessages` 加载，后续门禁文案通过当前消息实例解析；`PluginMessages` 的集中必需键校验覆盖生命周期启动键。保留用户 `messages.yml` 覆盖，不覆盖已有文件。
- `messages()` 在消息加载前被调用时改用稳定的 `TT-MESSAGES-NOT-LOADED` bootstrap 标识；字段初始化阶段的“尚未开始”改用 `TT-PLUGIN-NOT-STARTED`。这两个路径早于可用消息加载器，属于 README 允许的启动期技术例外。
- 调度和停服日志在产生时读取当前 `PluginMessages`；测试覆盖本组默认文案的完整占位符渲染、无残留占位符、消息重载后的自定义调度日志，以及生命周期键的缺失校验。
- 验证：`mvn -B -pl tianjitown-paper -am "-Dtest=PluginMessagesTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`、`mvn -B -pl tianjitown-paper -am test` 均通过；本源文件重新扫描后，任务列出的 19 行无非注释可见中文。028b/028c 的依赖、数据库和运行时激活文本仍按单任务边界保留，未在本任务处理。
