# 运行时与持久化职责

`TownRuntime`、`TownRepository`、`EconomyRepository` 和 `CommerceRepository` 保留现有公开方法、返回类型和异常类型，调用方通过这些入口访问业务。具体流程由包内组件实现，组件接收实际依赖，不持有入口类实例。

## Paper 运行时

| 组件 | 职责 |
| --- | --- |
| `TownRuntime` | 组装依赖、公开入口、启动恢复与周期任务协调 |
| `TownRuntimeTasks` | 工作线程执行、主线程回调、共享数据库可用状态和失败处理 |
| `TownTaxRuntime` | 税率缓存、QuickShop/Jobs/GlobalMarketPlus 税收结算、账本重试队列 |
| `TownEconomyRuntime` | 捐款、管理员调账、税率修改、外部清算与补偿退款 |
| `TownProvisionRuntime` | 审核扣费、建镇、初始 Residence 投影与传送点 |
| `TownProvisionRecovery` | 失败建镇的检查、投影清理和申请费退款 |
| `TownExpansionRuntime` | 扩张预览、单格/批量投影、失败回滚与启动恢复 |
| `TownLandRuntime` | 领地巡检、自动修复、传送点修改及审计 |

数据库操作继续在工作线程执行，Residence/Vault 操作及玩家回调沿用原有主线程调度。所有运行时组件共享同一个数据库可用状态；税款重试只重写账本，不重复执行已经完成的外部资金转移。

## Buff 运行时

| 组件 | 职责 |
| --- | --- |
| `BuffRuntime` | 保留公开入口和玩家事件监听，协调购买校验、失败退款、数据库刷新与到期清理 |
| `BuffPlayerEffects` | 校验效果定义与生效记录，应用/修复属性修饰符，管理生命值恢复及失败回滚 |
| `BuffExpirationScheduler` | 统一管理刷新版本、到期任务与截止时间，取消旧任务、过滤旧回调并保留调度失败后的重试时间 |

效果操作和调度状态仍只在主线程访问，数据库读写继续经由 `TownRuntime` 执行。新刷新保留已知截止时间；玩家正常退出时保留效果和追踪标记，以便跨服同步，同时取消本服到期任务并释放缓存；停服时取消任务并使未返回的刷新回调失效。购买效果应用失败时仍先完成退款，再通知调用方。

## 领地加成运行时

| 组件 | 职责 |
| --- | --- |
| `TownBonusRuntime` | 保留公开入口、`DiagnosticResult` 和事件监听，协调共享索引的异步刷新 |
| `TownBuildingRefunds` | 放置方块校验、每周返还额度预留、物品返还与过期计数清理 |
| `TownBeaconEffects` | 信标编辑权限、效果记录、玩家效果刷新与托管效果清理 |
| `TownBonusDiagnostics` | 诊断互斥、SQLite/QuickShop 数据采集、Residence/Vault 检查、结果通知与报告保留 |

建筑返还和信标继续共享同一份不可变索引；刷新失败保留旧快照，信标关闭界面的延迟回调读取执行时的最新快照。返还仍通过 `TownRuntime.write` 先预留额度，再在主线程回调中发放物品。事件优先级、热更新开关和托管药水效果的移除条件保持不变。

诊断在提交前读取 Vault 余额，在工作线程查询 SQLite 与 QuickShop，再回到主线程检查 Residence 和通知调用方；报告写入仍在工作线程执行，并保留最近 30 份诊断报告。

## 小镇存储

| 组件 | 职责 |
| --- | --- |
| `TownApplicationStore` | 申请编辑、选址、提交、审核与申请查询 |
| `TownProvisioningStore` | 建镇数据准备、完成、失败恢复和退款确认 |
| `TownMembershipStore` | 成员列表、角色与镇长变更、离镇、访客管理 |
| `TownJoinApplicationStore` | 入镇申请、审批、撤回与过期检查 |
| `TownInvitationStore` | 邀请创建、接受与拒绝 |
| `ApplicationFormDraftStore` / `TownVisitorStore` | 使用调用方连接执行表单草稿和访客 SQL |
| `TownPersistence` / `TownSqlValues` | 跨流程共享的连接级查询、校验、审计及 JDBC 值转换 |
| `TownDatabase` | 连接生命周期、线程检查、事务与异常转换 |

`TownRepository` 自身保留小镇概览、资料维护、归档/删除与公开接口。申请、成员及建镇流程通过独立组件运行。

## 经济存储

| 组件 | 职责 |
| --- | --- |
| `EconomyTaxStore` | 税率、补贴周期与额度、交易税款入账 |
| `EconomyOperationStore` | 外部经济操作状态机、补偿和清算对账 |
| `EconomyLedgerStore` | 账本分页、展示聚合和操作人名称补全 |
| `TerritoryExpansionStore` | 领地预留、单格/批量扩张、结算与退款 |
| `EconomyPersistence` | 同一连接上的账户检查、余额更新、账本写入及审计 |
| `EconomyDatabase` | 连接生命周期、线程检查、事务与异常转换 |

事务入口继续使用 SQLite `BEGIN IMMEDIATE`。共享查询和账本方法接收当前 `Connection`，不另开连接或嵌套事务，因此余额、领地、申请状态与审计的原子性保持不变。批量扩张仍在一个事务内预留所有单元并扣除一次总价。

## Buff 消费存储

| 组件 | 职责 |
| --- | --- |
| `CommerceRepository` | 组装组件、公开入口、保留报价/购买结果和异常类型 |
| `BuffPurchaseStore` | 报价、角色检查、固定时长/自选周数购买及管理员代购 |
| `BuffLifecycleStore` | 玩家/小镇生效 Buff 查询、到期清理、退款与上一层快照恢复 |
| `CommercePersistence` | 使用调用方连接查询 Buff、校验小镇/账户、写入账本与审计 |
| `CommerceSqlValues` | JDBC 值转换、更新行数检查 |
| `CommerceDatabase` | 连接生命周期、主线程拦截、事务回滚及异常转换 |

购买和退款继续使用同一个 `BEGIN IMMEDIATE` 事务更新账户、账本、Buff 状态和审计。业务键幂等、退款恢复上一层未到期快照、报价时清理到期记录的行为保持不变。

## 验证

执行 `mvn -B clean verify`。存储测试覆盖真实 SQLite 的申请/成员流程、事务回滚、并发约束和扩张退款；运行时行为测试覆盖数据库写入门禁、工作线程与主线程回调，以及税款账本重试不会重复外部清算。

`BuffExpirationSchedulerTest` 覆盖到期时间选择、旧回调失效、退出取消本服任务、调度失败恢复与停服取消；`BuffRuntimeTest` 覆盖异步刷新顺序、重生清理与恢复、应用失败退款，以及离线玩家到期清理失败后的重试。

`TownBonusRuntimeTest` 覆盖索引刷新合并与失败重试、信标权限及延迟回调、建筑返还预留与离线处理、热更新开关和周计数清理；`TownBonusDiagnosticsTest` 覆盖线程切换、诊断互斥、执行器拒绝与失败恢复、健康状态判定及报告保留。
