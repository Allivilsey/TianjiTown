# TASK-CN-037J：`TownUiController.java`—管理员审核与申请/资料表单

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：管理员审核与申请/资料表单
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 27 行，已迁移）
- 可见行号：`3144, 3148, 3150-3151, 3159-3161, 3165, 3173, 3179, 3182-3183, 3198-3200, 3208-3212, 3229, 3306, 3480, 3648, 3699, 3704, 3707`

## 可见文本位置

以下行号按源文件当前版本记录，范围表示包含首尾行：

`3144, 3148, 3150-3151, 3159-3161, 3165, 3173, 3179, 3182-3183, 3198-3200, 3208-3212, 3229, 3306, 3480, 3648, 3699, 3704, 3707`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 将管理员建镇审批的进度页、超时提示、失败结果与安全恢复结果迁入 `dialog.provision.*`；失败详情和恢复建议继续使用占位符渲染，保留配置中的颜色代码。
- 将管理员审批原因和页面关闭后的异步回调日志迁入 `log.provision.*`；审批原因以纯文本快照传入运行时，日志动态值使用占位符。
- 管理员审核资料不存在提示复用 `chat.application.not-found`；两个申请表单非法步骤异常使用新增的 `chat.application.invalid-form-step`。
- 将初始成员资料页的完成、保存草稿、成员未完整提示、放弃草稿及其说明迁入 `dialog.application.*`，并复用已有的 `dialog.application.save-draft`。
- 新增默认消息键及注释；`TownUiControllerMessagesTest` 覆盖默认值、完整渲染、无缺失配置/残留占位符和 `messages.yml` 热重载覆盖。相关定向测试 19/19 通过，根项目完整测试通过（core 24、storage 40、integrations 33、paper 195）。
- 目标管理员审核与申请/资料表单区段重新扫描为 0 行非注释 CJK，`git diff --check` 通过。
- 验证编译时发现工作区前序改动在 `openJoinTown` 的 `List.of` 结构中少一个右括号；仅补回该语法括号以恢复编译，未改变该区段文案或逻辑。
- 未重新生成全局 `docs/CHINESE_CODE_LINES.md`；本次仅完成 TASK-CN-037J，未处理 037K/037L 或其他任务范围。
- 例外：无。
