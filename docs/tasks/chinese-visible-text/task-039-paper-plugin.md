# TASK-CN-039：`plugin.yml` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/resources/plugin.yml`](../../../tianjitown-paper/src/main/resources/plugin.yml)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**
- 可见行号：`无`

## 可见文本位置

处理前命中行号为 `7, 11, 15, 25, 28, 31, 34, 37, 40`；按当前源文件重新扫描后已无非注释中文可见文本。

根级插件 `description` 未保留：该字段在 Paper 读取 descriptor 后没有运行期 setter，且不是插件加载必需字段；按 README 规则省略非必要启动描述。命令和权限节点、默认值、子权限、软依赖及 `/townadmin help` 用法保持不变。

## 处理记录

- 从 `plugin.yml` 移除根级、命令和 7 个权限的硬编码中文描述，保留 Paper 所需及业务兼容的稳定元数据；未添加或声称支持 `/townadmin reload` 的 descriptor 用法。
- 在 `messages.yml` 增加 `plugin.command.townadmin.description` 与 7 个 `plugin.permission.*.description` 默认键，并加入 `PluginMessages` 启动期非空校验。
- 新增 `PluginDescriptorMessages`，在 `onEnable()` 的消息加载后通过 Bukkit API 设置命令/权限描述；`reloadMessages()` 后重新应用，后续 `/help`、权限查看等元数据读取使用覆盖值。
- 扩展 `PluginDescriptorTest`，覆盖 descriptor 启动安全边界、描述键默认渲染及自定义 `messages.yml` 重载后的命令/权限描述更新。
- `mvn -B -pl tianjitown-paper -am -Dtest=PluginDescriptorTest -Dsurefire.failIfNoSpecifiedTests=false test` 定向测试通过（8 项）。
- 仅扫描本任务源文件 `plugin.yml` 的非注释中文：0 行；未修改全局中文索引或其他任务文件。

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。
