# 插件 Java 类分类方案

本文针对 TianjiTown 项目自身的 Java 类组织。当前保留四个 Maven 模块，以“模块划定依赖边界，模块内按职责与业务分类”为原则。本轮已完成 Paper 模块的目录迁移、包声明、依赖导入和测试迁移；业务用例深拆仍作为后续阶段单独推进。

## 1. 模块职责

| 模块 | 应包含的内容 | 边界要求 |
| --- | --- | --- |
| `tianjitown-core` | 业务规则、值对象、状态枚举、外部能力接口 | 不依赖 Paper、数据库实现或第三方插件实现 |
| `tianjitown-storage` | 数据库连接、迁移、Repository、持久化快照 | 负责数据读写和事务，不操作玩家、Dialog 或第三方插件 |
| `tianjitown-integrations` | Residence、Vault、WorldBorder、QuickShop、Jobs、GlobalMarketPlus 适配 | 封装第三方 API 与事件，不负责页面展示和业务流程总调度 |
| `tianjitown-paper` | 插件启动装配、用例编排、命令、界面、监听器、任务调度 | 连接其他模块，隔离 Paper 主线程操作与数据库异步操作 |

目标依赖方向：`paper → core / storage / integrations`，`storage → core`，`integrations → core`。禁止底层模块反向依赖 `paper`，避免 `storage` 与 `integrations` 互相调用。

## 2. Paper 模块包分类

以下包均以 `org.allivlisey.tianjitown.paper` 为前缀。表中列出当前生产类的实际归属；根包入口的全限定名保持不变。

| 目标包 | 职责 | 当前类 |
| --- | --- | --- |
| 根包 | Paper 注册入口 | `TianjiTownPlugin` |
| `runtime` | 运行状态、业务入口与动作结果 | `TownRuntime`、`GateStatus`、`TownActions`、`TownActionResult`、`TownActionOutcome`、`TownActionFailures` |
| `config` | 配置读取、类型化配置与校验 | `ConfigurationValues`、`RuntimeConfigurationValidator`、`EconomySettings`、`GovernanceSettings`、`BuffSettings`、`TownBonusSettings` |
| `message` | 消息加载、消息契约和跨界面业务文案 | `PluginMessages`、`MessageContract`、`ApplicationTextMessages`、`LandProtectionMessages` |
| `task` | 异步任务跟踪和重试队列 | `AsyncTaskTracker`、`RetryingWorkQueue` |
| `command` | 管理员命令分发、子命令、解析、权限与补全 | `TownAdminCommand`、`TownAdminApplicationCommands`、`TownAdminEconomyCommands`、`TownAdminGovernanceCommands`、`TownAdminLandCommands`、`TownCommandParser`、`TownAdminPermissions`、`TownAdminTabCompleter`、`TownAdminCompletionEngine`、`TownAdminCompletionHints`、`CommandConfirmationManager` |
| `ui` | UI 总入口、路由、导航与公共展示能力 | `TownUiController`、`TownUiActionRouter`、`TownUiPresentation`、`TownUiLegacyFacade`、`TownDialogService`、`DialogNavigation`、`DialogRoute` |
| `ui.home` | 首页、小镇详情与规则展示编辑 | `TownHomeUi`、`TownHomeDialogs`、`TownDetailsMenuModel`、`ReadOnlyRulesDialogRenderer`、`RuleEditorDialogRenderer` |
| `ui.application` | 建镇申请、表单草稿、初始成员确认与管理员审核 | `TownApplicationUi`、`TownApplicationDialogs`、`TownApplicationFormUi`、`TownApplicationFormDialogs`、`TownApplicationDrafts`、`TownAdminApplicationUi`、`TownAdminApplicationDialogs`、`TownInitialMemberDialogs`、`InitialMemberDialogLayout`、`ApplicationSubmissionDialogRenderer`、`ApplicationStatusText` |
| `ui.membership` | 入镇申请、成员与访客管理 | `TownMembershipUi`、`TownMembershipDialogs`、`TownJoinApplicationUi`、`TownJoinApplicationDialogs`、`JoinTownMenuModel`、`VisitorManagementMenuModel`、`MemberDisplayOrder`、`MemberRoleText` |
| `ui.governance` | 治理投票与镇长变更界面 | `TownGovernanceUi`、`TownGovernanceDialogs` |
| `ui.finance` | 捐款、税率、公共资金与账本界面 | `TownFinanceUi`、`TownFinanceDialogs` |
| `ui.buff` | 公共 Buff 商店与展示 | `TownBuffShopUi`、`TownBuffShopDialogs`、`BuffDialogRenderer` |
| `ui.territory` | 领地和扩张界面 | `TownTerritoryUi`、`TerritoryDialogRenderer` |
| `station` | 讲台服务台交互与监听 | `ServiceStationController`、`ServiceStationInteractionPolicy` |
| `land` | 选址、领地预览、创建投影与自动对账 | `SitePolicy`、`TerritoryService`、`ProvisionCoordinator`、`ProvisionResult`、`AutomaticLandReconciler` |
| `economy` | 捐款退款协调 | `DonationRefundCoordinator` |
| `buff` | 公共 Buff 的应用、刷新和清理 | `BuffRuntime` |
| `bonus` | 建筑返还、领地信标运行逻辑 | `TownBonusRuntime` |

分类约定：

- `Ui` 负责页面用例与交互组织，`Dialogs` / `DialogRenderer` 负责 Dialog 构建，`MenuModel` 负责展示数据；同一业务页面相关类放在同一包。
- 管理员审核页面属于 `ui.application`，不因类名包含 `Admin` 而放进命令包。
- 公共 Buff 与领地加成分别放入 `buff` 和 `bonus`，对应不同的效果生命周期。
- 只有跨业务通用能力才放在公共包；不建立无明确职责的 `utils`、`manager` 或 `common` 大包。
- `TownUiLegacyFacade` 暂随 UI 入口迁移，确认所有调用点已替换后再删除。

## 3. 其他模块分类

现有包结构总体已按职责划分，优先沿用，避免单纯为统一名称而大规模搬迁。

| 模块 | 保留的包分类 | 后续整理原则 |
| --- | --- | --- |
| `core` | `application`、`town`、`governance`、`land`、`economy`、`consumption`、`ports` | 纯规则留在对应业务包；抽取 Paper 中的规则时先移除平台类型依赖 |
| `storage` | `database`、`town`、`governance`、`economy`、`commerce`、`bonus` | Repository 与对应快照同包；连接和迁移基础设施集中在 `database` |
| `integrations` | `residence`、`vault`、`worldborder`、`quickshop`、`jobs`、`globalmarketplus` | 按外部插件分类；共享的 `ThirdPartyEventExecutor` 可留在模块根包，待确有更多公共事件基础设施再设 `event` 包 |

暂不增加新的 Maven 模块，也不强制为每个类新增接口。跨模块共享的数据类型只有在确实需要解耦时才提升到 `core`，数据库专用快照继续留在 `storage`。

## 4. 大类职责拆分方向

包迁移与逻辑拆分分开提交。先保持行为不变整理目录，再逐步减少集中式编排。

| 当前类 | 目标职责 | 后续可抽取的职责（拟新增类，名称待实施时确定） |
| --- | --- | --- |
| `TianjiTownPlugin` | 生命周期和组件装配 | 配置加载、依赖探测、启动诊断、后台任务注册与关闭 |
| `TownRuntime` | 业务门面与运行状态协调 | 建镇申请、成员管理、治理结算、公共资金与收入税、领地扩张与恢复、运维诊断等用例服务 |
| `TownUiController` / `TownUiActionRouter` | 页面入口和动作路由 | 具体业务交互交给各业务 UI，通过明确的动作接口调用用例 |

拆分约束：界面和命令调用用例服务；用例服务协调 Repository 与适配器。渲染器不直接执行 SQL 或扣款，Repository 不回调界面，领域规则不读取 Bukkit 配置。

## 5. 实施待办

- [x] P0：盘点跨类调用、包级可见成员、嵌套类型和源码路径断言，建立迁移清单。
- [x] P1：迁移 `config`、`message`、`task` 等基础类，按实际调用需求开放跨包入口。
- [x] P1：迁移 `runtime` 及其动作契约，明确命令、UI 和业务运行组件之间的依赖边界。
- [x] P1：按业务逐组迁移 UI 类，再迁移管理员命令与服务台类，同步更新 import 和测试包。
- [x] P1：迁移 `land`、`economy`、`buff`、`bonus` 运行组件，检查生命周期与回调依赖。
- [ ] P2：逐个抽取 `TownRuntime` 的业务用例；每次只拆一个领域，保留原有事务、幂等键、退款补偿和恢复顺序。
- [ ] P2：精简 `TianjiTownPlugin`，将可独立验证的启动配置、依赖检查和任务管理抽成协作类。
- [ ] P3：清理兼容门面和无用引用，更新架构文档与测试入口说明。

## 6. 迁移风险与验收

- 当前存在 `TownRuntime` 等跨包协作类。Java 子包不是同一个包，移动前必须检查类、构造器和成员访问；本轮为保持行为与编译边界稳定显式开放了迁移所需入口，后续 P3 继续收窄不必要的 `public` 成员。
- 保持 `TianjiTownPlugin` 的全限定名不变；如果未来迁移入口，必须同步修改 `plugin.yml` 的 `main`，验证插件可加载。
- 测试目录随生产包迁移；检查测试中的源码相对路径、反射类名、资源定位和结构断言，不以删除有效断言解决失败。
- 每批迁移完成后运行受影响模块测试及其依赖；阶段完成执行 `mvn -B clean verify`。
- 在隔离 Paper 服务器检查启动/停用、管理员命令与补全、服务台和 Dialog 路由；涉及运行逻辑拆分时，再验证资金补偿、领地恢复、治理结算和 Buff 刷新。
- 验收标准：类归属明确、无新增循环依赖、构建测试通过、插件加载正常，原有权限、配置、消息键及玩家行为保持一致。

### 本轮验收记录

- [x] Paper 根生产包仅保留 `TianjiTownPlugin`，且 `plugin.yml` 的入口全限定名未变化。
- [x] 生产源码路径与 `package` 声明一致；测试源码随包迁移，源码结构断言改为递归定位而非删除。
- [x] `mvn -B -ntp clean verify`：四个 Maven 模块构建成功，Paper 自动化测试 178 项全部通过，并生成 `tianjitown-paper/target/TianjiTown-1.4.0.jar`。
- [ ] P2/P3 的业务用例深拆、启动装配拆分、兼容门面清理及 Paper 服务器人工回归：作为后续阶段，尚未在本轮目录迁移中混入。
