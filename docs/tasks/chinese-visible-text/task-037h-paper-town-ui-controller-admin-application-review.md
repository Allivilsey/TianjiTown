# TASK-CN-037H：`TownUiController.java`—管理员申请列表与审核详情

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：管理员申请列表与审核详情
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 28 行，已迁移）
- 可见行号：`2319-2320, 2323, 2327, 2330-2331, 2343, 2345-2352, 2354, 2359, 2362, 2365, 2368, 2371, 2375, 2379-2380, 2384, 2388, 2392, 2396`

## 可见文本位置

以下行号按源文件当前版本记录，范围表示包含首尾行：

`2319-2320, 2323, 2327, 2330-2331, 2343, 2345-2352, 2354, 2359, 2362, 2365, 2368, 2371, 2375, 2379-2380, 2384, 2388, 2392, 2396`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 将管理员申请列表和审核详情中的标题、申请条目、空状态、分页按钮、申请摘要、审核操作及失败申请恢复操作迁入 `dialog.admin.*`；保留 `tianjitown.admin` 权限节点、申请状态枚举和 GUI 动作标识不变。
- 申请名称、申请人、申请资料、规则、世界名、坐标、时间和创建错误均通过命名占位符传入，并在界面边界过滤颜色控制符；状态显示继续由 `ApplicationStatusText` 映射稳定状态键。申请不存在时复用 `chat.application.not-found`，并在异步读取调用时解析以支持 messages 重载。
- 新增默认消息键及占位符注释；`TownUiControllerMessagesTest` 覆盖全部 `dialog.admin.*` 键的完整渲染、无残留占位符和 messages.yml 重载覆盖。定向测试 15/15 通过，根项目完整测试通过（core 24、storage 40、integrations 33、paper 191）。
- 当前管理员申请列表与详情方法区间重新扫描为 0 行非注释 CJK，`git diff --check` 通过。
- 未重新生成全局 `docs/CHINESE_CODE_LINES.md`；仅完成 TASK-CN-037H，其他 TownUiController 子任务及任务目录外文件未在本次处理。
- 例外：无。其他方法中同一申请错误或管理员结果文本仍按其所属后续任务边界保留，未在本任务越界修改。
