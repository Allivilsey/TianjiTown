# TASK-CN-037F：`TownUiController.java`—成员、镇长转让与治理投票

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：成员、镇长转让与治理投票
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 20 行，已迁移）
- 可见行号：`1926, 1930, 1933, 1941, 1962, 1969, 1975, 1994, 2001, 2010-2014, 2018, 2022, 2072, 2129, 2132, 2146`

## 可见文本位置

以下行号按源文件当前版本记录，范围表示包含首尾行：

`1926, 1930, 1933, 1941, 1962, 1969, 1975, 1994, 2001, 2010-2014, 2018, 2022, 2072, 2129, 2132, 2146`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 将成员分页菜单、成员详情操作、镇长转让确认页的固定文案迁入 `dialog.town-members.*`、`dialog.member-detail.*` 和 `dialog.transfer.*`；小镇不存在/无小镇身份继续复用 `chat.runtime.town-not-found` 与 `chat.runtime.town-required`。
- 将投票列表标题中的状态、类型、目标和分隔符整句迁入 `dialog.votes.entry-title` / `dialog.votes.pending-entry-title`，避免继续拼接可见句子；UUID 标签也迁入 `dialog.member-detail.uuid`。
- `displayName` 改为实例方法，在使用时读取 `dialog.common.unknown` / `dialog.common.unknown-player`，玩家名、镇名、截止时间和投票目标在进入消息前过滤 `&`/`§`；成员排序改用同一解析器，消息重载后下一次输出使用新值。
- 通过 `TownUiControllerMessagesTest` 验证新增键的完整占位符渲染、非空/无缺失键/无残留占位符、颜色和 `messages.yml` reload 覆盖；定向测试 11/11 通过。
- 验证通过：`mvn -B test`（Core 24、Storage 40、Integrations 33、Paper 187），`git diff --check` 通过；成员、转让、投票方法及玩家名回退辅助逻辑重新扫描为 0 行非注释 CJK。
- 干净编译时发现同一源文件前置任务改动在 `openTown` 中缺少一个列表闭合括号，已补齐该纯语法问题；不改变业务逻辑，作为本次验证所需的兼容性修正记录。
- 未重新生成全局 `docs/CHINESE_CODE_LINES.md`；仅完成 TASK-CN-037F，未处理 037G～037L 及后续任务。
- 例外：无。
