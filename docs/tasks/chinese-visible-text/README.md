# 可见中文文本文件级任务

本目录依据 [`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md) 的“可见性标记”拆分生成。

## 最终目标

将清单中所有会被玩家或管理员看到的固定游戏文本集中到 `tianjitown-paper/src/main/resources/messages.yml`，使服主可以修改文案而不必重新编译插件。迁移完成后，Java、`config.yml` 和其他资源文件只保留业务数据、稳定标识、消息代码和占位符参数，不再作为可见中文文案的来源。

“加入 `messages.yml`”不只是复制一份文本。每个调用点必须真正通过 `PluginMessages` 在使用时解析配置；修改 `messages.yml` 并执行重载后，后续新产生的界面、聊天、日志和诊断文本应使用新值。不可只在启动时读取并长期缓存已经渲染的文本，除非该文本是需要保留事件发生时快照的审计或账本记录。

## 汇总

- 源文件数：**39**
- 任务单元数：**62**（其中 4 个高密度源文件已拆成 27 个功能子任务）
- 可见文本行数：**972**
- 统计基准：原清单中的行号范围按首尾均包含计算。

| 模块 | 文件数 | 可见文本行数 |
| --- | ---: | ---: |
| `tianjitown-core` | 4 | 23 |
| `tianjitown-integrations` | 10 | 160 |
| `tianjitown-paper` | 25 | 789 |

这些数字是迁移前基线，不是迁移后需要维持的结果。最终验收目标是：除明确记录的启动期技术例外外，重新按原规则扫描时不再出现玩家或管理员可见的硬编码中文。

## 基本原则

1. `messages.yml` 是可见固定文本的唯一可配置来源；业务配置只描述开关、金额、范围、标识和第三方集成参数。
2. `tianjitown-core` 和 `tianjitown-integrations` 不得依赖 `tianjitown-paper`、Bukkit 的配置实现或 `PluginMessages`。底层模块返回稳定错误代码、枚举和参数，由 Paper 边界解析文案。
3. 稳定标识不得为了汉化而改变，包括枚举名、数据库值、权限节点、配置路径、命令子命令、第三方插件名和 API 字段。需要展示时为其建立单独的消息键。
4. 动态值必须使用有语义的占位符，例如 `{town}`、`{player}`、`{amount}`、`{detail}`；不得继续用字符串拼接组装半句话。
5. 同一消息键只有在语义、受众、颜色和标点都一致时才能复用。相似但用途不同的文本应使用不同键，避免以后修改一处误伤另一处。
6. 现有用户 `messages.yml` 可以只覆盖部分键；JAR 内默认值仍通过 `YamlConfiguration` defaults 补齐。迁移不得改成覆盖用户文件。
7. 原清单行号只是定位线索。执行任务时必须阅读完整方法并追踪最终输出，不能机械替换某一行。

## 文本分类与决策

| 文本类型 | 处理方式 |
| --- | --- |
| 固定标题、按钮、提示、日志或错误句子 | 整句放入 `messages.yml`，代码只保留消息键。 |
| 带名称、金额、版本、坐标或异常详情的句子 | 整句放入 `messages.yml`，动态部分改为命名占位符。 |
| 枚举的显示名称 | 枚举值继续作为业务标识；在 Paper 展示层按枚举映射消息键。不要把配置后的中文重新用于解析或业务判断。 |
| core 中的校验错误 | 返回类型化的校验项（错误代码和参数），由 Paper 转为消息；存储层若需要拒绝非法数据，应抛出包含类型化校验项的异常，而不是依赖中文文本。 |
| integrations 中的能力检查、操作结果和故障详情 | 优先返回结果代码和参数；如果适配器直接向玩家或日志输出，则从 Paper 构造处注入消息解析函数，沿用 Residence guard 已有的 resolver 模式。 |
| 第三方插件或异常提供的文本 | 只迁移 TianjiTown 的外层模板，例如 `Vault 调用失败：{detail}`；第三方 `{detail}` 保持为动态诊断数据。 |
| 命令参数、Tab 补全值、配置值和数据库标识 | 默认不迁移；如它们同时需要展示，拆分“机器值”和“显示标签”。Tab 补全会成为真实命令参数，不能只改显示而不改解析。 |
| 审计、账本和历史记录 | 明确选择存储“事件发生时已解析的文本快照”或“消息代码与参数”。不得因重载消息而无意改写历史语义。 |
| 纯内部断言或仅供开发者定位的内容 | 重新确认是否真的会进入控制台、诊断或管理员界面；不可见时记录依据并从本轮迁移中排除。 |

## 跨模块消息流

推荐让底层返回结构化信息，而不是让所有模块直接读取 YAML：

```text
core / integrations：结果代码 + 占位符参数 + 可选技术详情
        ↓
paper：把结果代码映射为 messages.yml 键
        ↓
PluginMessages：读取默认值/用户覆盖、替换占位符、处理颜色
        ↓
聊天、CommandSender、Dialog、Adventure Component、控制台或诊断报告
```

结构化结果可以是领域专用的 `ValidationIssue`、`ResultCode` 或类似 `MessageRef` 的不可变值。重点是保留稳定代码和原始参数，不要让底层业务根据翻译后的句子进行判断。一个结果若同时需要给程序判断和给管理员说明，应分别保存状态字段与可显示消息信息。

## 选择正确的消息读取接口

| 输出位置 | 推荐接口 | 注意事项 |
| --- | --- | --- |
| `CommandSender`、玩家聊天 | `messages.send(...)` 或 `messages.text(...)` | `text` 会把 `&` 转为 `§` 颜色码。 |
| Adventure/Paper 组件 | `messages.component(...)` | 避免先拼字符串再转组件。 |
| Paper Dialog 的字符串模型 | 现有 `dialogText(...)`，内部使用 `rawText("dialog." + key, ...)` | `rawText` 保留 `&`，供 Dialog 渲染入口应用颜色；不要提前转换两次。 |
| Paper Dialog 的组件 | 现有 `dialogComponent(...)` 或 `messages.component(...)` | 键应位于 `dialog.*`。 |
| 控制台、诊断、无颜色的错误详情 | `messages.plainText(...)` | 避免把 `§` 颜色控制符写入日志、文件或异常详情。 |
| 需要 `String.format` 的范围格式 | 保持现有 `%s` 契约并通过 `validateRangeFormat` 校验 | 不得混用 `%f`、花括号占位符和未转义 `%`。 |

`PluginMessages.resolve` 会先替换占位符再处理 legacy 颜色，因此来自用户输入或第三方异常的值必须确认不会注入 `&`/`§` 格式码。若参数不是项目已经验证过的安全文本，应在边界处转义或使用不会解析参数颜色码的组件组合方式。

## 消息键和 YAML 规则

1. 沿用小写 kebab-case 和功能分组。玩家聊天使用 `chat.<feature>.<outcome>`，Dialog 使用 `dialog.<feature>.<element>`；控制台与诊断建议使用 `log.*` 或 `diagnostic.*`，领域校验建议使用 `validation.*`。
2. 优先把键放入现有功能分组，不要仅按 Java 类名建组。类名重构不应迫使服主修改配置键。
3. YAML 值应包含完整语句、标点和颜色。默认使用 `&` 颜色码，不在配置中写 `§`。
4. 每组键前用注释列出占位符含义和单位；金额、时间、比率、坐标等格式由调用方准备好后再传入。
5. 不使用 `text.part-one`、`text.part-two` 之类句子碎片，也不把固定中文留在 Java 中与配置片段拼接。
6. 新增键前先搜索 `messages.yml`。只有完全同义时才复用，不能因为中文字面相似就合并不同业务状态。
7. 所有键都必须在 JAR 内默认 `messages.yml` 中存在且非空。启动关键键还应加入 `PluginMessages.validateRequiredMessages()` 或等价的集中校验。
8. 测试至少覆盖一个带全部占位符的完整渲染结果，并断言不会残留 `{placeholder}` 或出现“缺少消息配置”。

## 单个任务的执行流程

后续模型每次处理一个任务文件时，按以下顺序执行：

### 1. 建立当前基线

- 打开任务文件和对应源文件，确认任务状态仍为“待处理”。
- 读取命中行所在的完整方法、调用者、结果类型和测试，不只读取单行。
- 重新核对中文行号；代码变化导致行号漂移时，以实际调用链为准。
- 记录该文本最终可能到达的渠道：玩家聊天、Dialog、管理员命令、控制台、诊断、审计或配置元数据。

### 2. 逐条分类

- 判断它是固定模板、动态数据、稳定标识、第三方详情、历史快照还是启动期文本。
- 对被误标为可见的内部文本，必须找到调用链证据后才可排除，并在任务处理记录中说明。

### 3. 设计消息键与占位符

- 先写出完整目标句子，再抽取动态值；不要从原有字符串拼接结构直接推导碎片键。
- 为每个动态值选择稳定、可读的占位符名，并确认 `Map` 中不会传入 `null`。
- 明确输出是否需要颜色、纯文本或 Component，以及消息是否必须支持热重载。
- 若文本来自 core/integrations，先定义结果代码到消息键的唯一映射位置，避免多个调用方各自映射。

### 4. 先添加默认配置

- 将默认中文加入 `tianjitown-paper/src/main/resources/messages.yml` 的正确分组。
- 添加简短注释，说明使用场景、占位符及单位。
- 使用单引号或双引号避免 YAML 将冒号、井号、百分号等误解析。
- 立即用 `YamlConfiguration` 或现有 `PluginMessagesTest` 验证 YAML 可加载、键非空、占位符渲染正确。

### 5. 改造代码调用链

- `tianjitown-paper`：直接改用合适的 `PluginMessages` 接口；静态工具若拿不到消息实例，应通过构造参数传入 resolver，或返回结构化结果后在上层解析。
- `tianjitown-core`：移除显示名称和中文错误句子，保留枚举、错误代码和参数。禁止为了省事让 core 依赖 Paper。
- `tianjitown-integrations`：能力检查和操作结果改为稳定代码/参数；适配器内直接发送消息或写日志时，从 `TianjiTownPlugin` 创建适配器的位置注入 resolver。
- `config.yml`：行为配置继续留在原处；可见显示名称迁入 `messages.yml`，运行时保存稳定 key，并尽量在展示时解析，避免消息重载失效。
- 不得通过把中文挪到常量类、异常类、测试夹具或另一个 YAML 来绕过目标。

### 6. 处理调用方和持久化影响

- 搜索旧方法、旧显示名和旧中文的全部引用，不能只改任务标出的第一个调用点。
- 若结果会写入数据库、审计、账本或补偿原因，明确写入的是已解析快照还是结构化代码；保持现有幂等键、状态机和金额逻辑不变。
- 若文本同时用于日志和玩家界面，分别选择纯文本与带颜色输出，避免把日志格式直接展示给玩家。
- 若映射影响命令解析、Tab 补全、序列化或数据库枚举，增加独立显示映射，不修改稳定机器值。

### 7. 增加测试

- 为新增默认键添加存在性和非空断言。
- 为每种占位符组合断言最终完整文本，尤其是金额、时间、玩家名、世界名、第三方 `{detail}`。
- 为 core/integrations 的结构化代码添加单元测试，断言业务状态不依赖中文。
- 为调用方添加测试，确认使用的是配置值而不是硬编码回退；自定义临时 `messages.yml` 后应能看到覆盖值。
- 对热重载路径，验证重载后的下一次输出采用新文案；不应要求重启的启动期元数据除外。

### 8. 验证并收尾

- 先运行相关模块的定向测试，再运行根项目完整测试。
- 重新扫描该源文件的非注释中文，确认所有可见固定文本已移除或已记录技术例外。
- 搜索旧中文全文，确认没有同义硬编码副本、测试外生产回退或字符串拼接残留。
- 将任务文件状态改为“已完成”，勾选完成检查，并记录新增/复用的消息键、测试和例外。
- 不以“代码能编译”作为完成标准；必须证明修改 `messages.yml` 能改变实际输出。

## 分模块实施策略

### tianjitown-core

`ApplicationText`、`BuffDurationOption`、`ExpansionDirection` 和 `LandProtectionService` 属于领域或端口层。推荐先建立类型化校验/结果代码，再让 Paper 映射为 `validation.*`、`dialog.*` 或 `chat.*`。枚举可保留 `ONE_HOUR`、`NORTH` 等稳定值，但 `displayName()` 不应继续返回硬编码中文。`LandProtectionService.Result/Inspection` 的成功状态与显示文本要分开，调用方不得通过比较 message 字符串判断结果。

### tianjitown-integrations

适配器中的文本大多同时进入启动门禁、控制台、诊断或上层 `Result.message()`。先追踪每个结果的消费者，再决定使用结果代码还是注入 resolver。构造函数注入应从 `TianjiTownPlugin` 或 `TownRuntime` 统一完成，并为测试提供显式的测试 resolver；不要在 integrations 内自行读取 `messages.yml`。第三方 API 原始错误作为 `{detail}`，TianjiTown 的上下文句子放入配置。

### tianjitown-paper

Paper 层可以直接解析消息，但要避免在设置对象、单例或长生命周期缓存中保存已经渲染的文本。Dialog 优先复用 `dialogText/dialogComponent`，命令和聊天使用 `send/text/component`，日志与诊断使用 `plainText`。`TianjiTownPlugin`、`TownAdminCommand`、`TownRuntime` 和 `TownUiController` 已按方法与功能边界拆成子任务。后续模型一次只完成一个子任务，并在每个子任务后运行测试和扫描，避免一次替换数百行后无法定位回归。

## 特殊任务和启动期限制

### TASK-CN-022：PluginMessages.java

`PluginMessages` 正在加载或校验 `messages.yml` 时，不能无限递归地依赖同一个文件提供错误文案。正常运行期的“缺少消息配置”提示应尽量使用一个有内置默认值的系统键；JAR 资源丢失、YAML 完全损坏等灾难路径必须保留无配置也能输出的 bootstrap 诊断。该诊断建议使用稳定错误代码或非本地化技术文本，并在任务记录中明确列为启动期例外。

### TASK-CN-028A～028C：TianjiTownPlugin.java

当前 `onEnable()` 在 `PluginMessages` 创建前已经生成部分启动门禁文本。处理该任务时，应在生命周期允许的最早位置加载 `messages.yml`，让后续门禁状态、依赖检查和失败报告都能解析配置；早于消息加载器、消息文件自身加载失败或插件描述符解析失败的路径仍属于 bootstrap 例外。调整初始化顺序时必须验证停用、重新启用和门禁失败分支，避免为了文案迁移破坏资源保存、线程池或数据库关闭顺序。

### TASK-CN-038：config.yml

当前命中项是 Buff `display-name`。业务 catalog key、效果类型、价格和等级继续属于 `config.yml`；可见名称应转为 `messages.yml` 中按 Buff key 查找的标签。若允许服主新增自定义 Buff，需要同时定义其消息键约定和缺失键门禁，不能只为内置 `speed/health` 写死分支。

### TASK-CN-039：plugin.yml

`plugin.yml` 在 `onEnable()` 和 `messages.yml` 加载之前由 Paper 读取，不能直接调用运行期消息解析。命令和权限描述可在消息加载后通过 Paper/Bukkit API 设置时，应以 `messages.yml` 为来源并在 descriptor 中保留最小安全定义；插件元数据中无法运行期覆盖的字段，需要选择“省略非必要描述”“构建时生成”或“记录为启动描述例外”之一。不得直接删除 descriptor 必需字段，也不得声称它支持 `/townadmin reload`。

## 批次顺序与并行限制

建议按以下顺序实施：

1. 先以 TASK-CN-022 为入口完成消息加载、缺失键处理和测试基础；core/integrations 需要共享的结构化结果契约应在开始对应任务前先稳定下来。
2. 完成 core 的 TASK-CN-001～004，稳定领域错误与显示标签边界。
3. 完成 integrations 的 TASK-CN-005～014，统一适配器结果和日志解析方式。
4. 完成 Paper 中等规模任务 TASK-CN-015～027。
5. 依次完成索引中已拆分的 TASK-CN-028A～028C、030A～030E、036A～036G 和 037A～037L，每个子任务独立测试和扫描。
6. 完成资源任务 TASK-CN-038～039，并执行全局扫描与人工验收。

所有任务都会修改同一个 `messages.yml`，不适合让多个模型无协调地并行写入。若必须并行，应由一个任务统一维护 YAML 和键名注册，其他任务只提交所需键、占位符和代码修改；合并时保持功能分组，不要让格式化工具重排整个文件。

## 验收门禁

每个任务完成时至少满足：

- 任务所列可见固定中文已经由 `messages.yml` 提供。
- 修改默认值或用户覆盖值能够改变下一次实际输出。
- 新键在内置文件中存在、非空，颜色和占位符渲染正确。
- 没有残留未替换的 `{placeholder}`、`§` 污染日志或“缺少消息配置”提示。
- core/integrations 没有新增对 Paper 或 Bukkit 配置实现的反向依赖。
- 命令参数、数据库值、幂等键、权限和第三方 API 协议保持兼容。
- 相关模块测试和根项目完整测试通过。
- 任务状态、检查框、消息键和技术例外已经记录。

全部任务完成后，重新生成 `CHINESE_CODE_LINES.md`。对仍被标记为可见的每一行给出迁移、误判或启动期例外结论，并在真实 Paper 环境至少抽查玩家 Dialog、聊天/命令、控制台日志、诊断报告、重载和缺失键行为。

## 任务单元索引

| ID | 模块 | 源文件 | 可见文本行数 | 任务文件 |
| --- | --- | --- | ---: | --- |
| TASK-CN-001 | `tianjitown-core` | `tianjitown-core/src/main/java/cn/tianji/town/core/application/ApplicationText.java` | 10 | [`task-001-core-application-text.md`](task-001-core-application-text.md) |
| TASK-CN-002 | `tianjitown-core` | `tianjitown-core/src/main/java/cn/tianji/town/core/consumption/BuffDurationOption.java` | 4 | [`task-002-core-buff-duration-option.md`](task-002-core-buff-duration-option.md) |
| TASK-CN-003 | `tianjitown-core` | `tianjitown-core/src/main/java/cn/tianji/town/core/land/ExpansionDirection.java` | 4 | [`task-003-core-expansion-direction.md`](task-003-core-expansion-direction.md) |
| TASK-CN-004 | `tianjitown-core` | `tianjitown-core/src/main/java/cn/tianji/town/core/ports/LandProtectionService.java` | 5 | [`task-004-core-land-protection-service.md`](task-004-core-land-protection-service.md) |
| TASK-CN-005 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/globalmarketplus/GlobalMarketPlusIncomeTaxAdapter.java` | 7 | [`task-005-integrations-global-market-plus-income-tax-adapter.md`](task-005-integrations-global-market-plus-income-tax-adapter.md) |
| TASK-CN-006 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/jobs/JobsIncomeTaxAdapter.java` | 8 | [`task-006-integrations-jobs-income-tax-adapter.md`](task-006-integrations-jobs-income-tax-adapter.md) |
| TASK-CN-007 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/quickshop/QuickShopHistoryProbe.java` | 6 | [`task-007-integrations-quick-shop-history-probe.md`](task-007-integrations-quick-shop-history-probe.md) |
| TASK-CN-008 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/quickshop/QuickShopTaxAdapter.java` | 11 | [`task-008-integrations-quick-shop-tax-adapter.md`](task-008-integrations-quick-shop-tax-adapter.md) |
| TASK-CN-009 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/residence/ResidenceCommandGuard.java` | 2 | [`task-009-integrations-residence-command-guard.md`](task-009-integrations-residence-command-guard.md) |
| TASK-CN-010 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/residence/ResidenceDeletionGuard.java` | 4 | [`task-010-integrations-residence-deletion-guard.md`](task-010-integrations-residence-deletion-guard.md) |
| TASK-CN-011 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/residence/ResidenceLandProtectionService.java` | 64 | [`task-011-integrations-residence-land-protection-service.md`](task-011-integrations-residence-land-protection-service.md) |
| TASK-CN-012 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/vault/VaultEconomyProbe.java` | 3 | [`task-012-integrations-vault-economy-probe.md`](task-012-integrations-vault-economy-probe.md) |
| TASK-CN-013 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/vault/VaultSettlementService.java` | 49 | [`task-013-integrations-vault-settlement-service.md`](task-013-integrations-vault-settlement-service.md) |
| TASK-CN-014 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/worldborder/WorldBorderBoundaryService.java` | 6 | [`task-014-integrations-world-border-boundary-service.md`](task-014-integrations-world-border-boundary-service.md) |
| TASK-CN-015 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/BuffRuntime.java` | 21 | [`task-015-paper-buff-runtime.md`](task-015-paper-buff-runtime.md) |
| TASK-CN-016 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/BuffSettings.java` | 10 | [`task-016-paper-buff-settings.md`](task-016-paper-buff-settings.md) |
| TASK-CN-017 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/ConfigurationValues.java` | 15 | [`task-017-paper-configuration-values.md`](task-017-paper-configuration-values.md) |
| TASK-CN-018 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/DonationCompensationCoordinator.java` | 3 | [`task-018-paper-donation-compensation-coordinator.md`](task-018-paper-donation-compensation-coordinator.md) |
| TASK-CN-019 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/EconomySettings.java` | 8 | [`task-019-paper-economy-settings.md`](task-019-paper-economy-settings.md) |
| TASK-CN-020 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/GovernanceSettings.java` | 2 | [`task-020-paper-governance-settings.md`](task-020-paper-governance-settings.md) |
| TASK-CN-021 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/OnlineBackupService.java` | 8 | [`task-021-paper-online-backup-service.md`](task-021-paper-online-backup-service.md) |
| TASK-CN-022 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/PluginMessages.java` | 6 | [`task-022-paper-plugin-messages.md`](task-022-paper-plugin-messages.md) |
| TASK-CN-023 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/ProvisionResult.java` | 3 | [`task-023-paper-provision-result.md`](task-023-paper-provision-result.md) |
| TASK-CN-024 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/RuleEditorDialogRenderer.java` | 5 | [`task-024-paper-rule-editor-dialog-renderer.md`](task-024-paper-rule-editor-dialog-renderer.md) |
| TASK-CN-025 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/RuntimeConfigurationValidator.java` | 10 | [`task-025-paper-runtime-configuration-validator.md`](task-025-paper-runtime-configuration-validator.md) |
| TASK-CN-026 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/SitePolicy.java` | 9 | [`task-026-paper-site-policy.md`](task-026-paper-site-policy.md) |
| TASK-CN-027 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TerritoryService.java` | 12 | [`task-027-paper-territory-service.md`](task-027-paper-territory-service.md) |
| TASK-CN-028A | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TianjiTownPlugin.java` | 19 | [`task-028a-paper-tianji-town-plugin-lifecycle-scheduling.md`](task-028a-paper-tianji-town-plugin-lifecycle-scheduling.md) |
| TASK-CN-028B | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TianjiTownPlugin.java` | 13 | [`task-028b-paper-tianji-town-plugin-dependency-database-gates.md`](task-028b-paper-tianji-town-plugin-dependency-database-gates.md) |
| TASK-CN-028C | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TianjiTownPlugin.java` | 21 | [`task-028c-paper-tianji-town-plugin-runtime-activation-locking.md`](task-028c-paper-tianji-town-plugin-runtime-activation-locking.md) |
| TASK-CN-029 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownActions.java` | 7 | [`task-029-paper-town-actions.md`](task-029-paper-town-actions.md) |
| TASK-CN-030A | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCommand.java` | 12 | [`task-030a-paper-town-admin-command-system-operations.md`](task-030a-paper-town-admin-command-system-operations.md) |
| TASK-CN-030B | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCommand.java` | 11 | [`task-030b-paper-town-admin-command-station-application-town.md`](task-030b-paper-town-admin-command-station-application-town.md) |
| TASK-CN-030C | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCommand.java` | 14 | [`task-030c-paper-town-admin-command-member-vote-land.md`](task-030c-paper-town-admin-command-member-vote-land.md) |
| TASK-CN-030D | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCommand.java` | 32 | [`task-030d-paper-town-admin-command-economy-expansion-buff.md`](task-030d-paper-town-admin-command-economy-expansion-buff.md) |
| TASK-CN-030E | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCommand.java` | 13 | [`task-030e-paper-town-admin-command-help-pages.md`](task-030e-paper-town-admin-command-help-pages.md) |
| TASK-CN-031 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCompletionEngine.java` | 3 | [`task-031-paper-town-admin-completion-engine.md`](task-031-paper-town-admin-completion-engine.md) |
| TASK-CN-032 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminTabCompleter.java` | 2 | [`task-032-paper-town-admin-tab-completer.md`](task-032-paper-town-admin-tab-completer.md) |
| TASK-CN-033 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownBonusRuntime.java` | 11 | [`task-033-paper-town-bonus-runtime.md`](task-033-paper-town-bonus-runtime.md) |
| TASK-CN-034 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownBonusSettings.java` | 19 | [`task-034-paper-town-bonus-settings.md`](task-034-paper-town-bonus-settings.md) |
| TASK-CN-035 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownCommandParser.java` | 22 | [`task-035-paper-town-command-parser.md`](task-035-paper-town-command-parser.md) |
| TASK-CN-036A | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java` | 34 | [`task-036a-paper-town-runtime-initialization-recovery.md`](task-036a-paper-town-runtime-initialization-recovery.md) |
| TASK-CN-036B | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java` | 45 | [`task-036b-paper-town-runtime-votes-provisioning.md`](task-036b-paper-town-runtime-votes-provisioning.md) |
| TASK-CN-036C | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java` | 27 | [`task-036c-paper-town-runtime-application-recovery-land.md`](task-036c-paper-town-runtime-application-recovery-land.md) |
| TASK-CN-036D | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java` | 4 | [`task-036d-paper-town-runtime-quickshop-tax.md`](task-036d-paper-town-runtime-quickshop-tax.md) |
| TASK-CN-036E | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java` | 14 | [`task-036e-paper-town-runtime-external-income-finance.md`](task-036e-paper-town-runtime-external-income-finance.md) |
| TASK-CN-036F | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java` | 16 | [`task-036f-paper-town-runtime-expansion-projection.md`](task-036f-paper-town-runtime-expansion-projection.md) |
| TASK-CN-036G | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java` | 12 | [`task-036g-paper-town-runtime-external-operations-failures.md`](task-036g-paper-town-runtime-external-operations-failures.md) |
| TASK-CN-037A | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java` | 10 | [`task-037a-paper-town-ui-controller-station-entry-events.md`](task-037a-paper-town-ui-controller-station-entry-events.md) |
| TASK-CN-037B | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java` | 42 | [`task-037b-paper-town-ui-controller-main-governance-visitors.md`](task-037b-paper-town-ui-controller-main-governance-visitors.md) |
| TASK-CN-037C | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java` | 47 | [`task-037c-paper-town-ui-controller-pending-finance-tax-ledger.md`](task-037c-paper-town-ui-controller-pending-finance-tax-ledger.md) |
| TASK-CN-037D | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java` | 17 | [`task-037d-paper-town-ui-controller-expansion-buff-shop.md`](task-037d-paper-town-ui-controller-expansion-buff-shop.md) |
| TASK-CN-037E | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java` | 39 | [`task-037e-paper-town-ui-controller-rules-application-town.md`](task-037e-paper-town-ui-controller-rules-application-town.md) |
| TASK-CN-037F | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java` | 20 | [`task-037f-paper-town-ui-controller-members-transfer-votes.md`](task-037f-paper-town-ui-controller-members-transfer-votes.md) |
| TASK-CN-037G | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java` | 25 | [`task-037g-paper-town-ui-controller-join-applications.md`](task-037g-paper-town-ui-controller-join-applications.md) |
| TASK-CN-037H | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java` | 28 | [`task-037h-paper-town-ui-controller-admin-application-review.md`](task-037h-paper-town-ui-controller-admin-application-review.md) |
| TASK-CN-037I | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java` | 20 | [`task-037i-paper-town-ui-controller-actions-notifications-site.md`](task-037i-paper-town-ui-controller-actions-notifications-site.md) |
| TASK-CN-037J | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java` | 27 | [`task-037j-paper-town-ui-controller-admin-review-forms.md`](task-037j-paper-town-ui-controller-admin-review-forms.md) |
| TASK-CN-037K | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java` | 18 | [`task-037k-paper-town-ui-controller-donation-form-save-invitations.md`](task-037k-paper-town-ui-controller-donation-form-save-invitations.md) |
| TASK-CN-037L | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java` | 22 | [`task-037l-paper-town-ui-controller-outcomes-dialog-helpers.md`](task-037l-paper-town-ui-controller-outcomes-dialog-helpers.md) |
| TASK-CN-038 | `tianjitown-paper` | `tianjitown-paper/src/main/resources/config.yml` | 2 | [`task-038-paper-config.md`](task-038-paper-config.md) |
| TASK-CN-039 | `tianjitown-paper` | `tianjitown-paper/src/main/resources/plugin.yml` | 9 | [`task-039-paper-plugin.md`](task-039-paper-plugin.md) |

## 快速执行清单

1. 领取一个“待处理”任务并阅读完整调用链。
2. 在 `messages.yml` 设计完整句子、键和占位符。
3. 按模块边界改造为配置解析，不把 YAML 依赖下沉。
4. 添加默认键、覆盖测试、业务测试和重载验证。
5. 定向测试、完整测试、重新扫描。
6. 更新任务状态与处理记录后再开始下一个文件。
