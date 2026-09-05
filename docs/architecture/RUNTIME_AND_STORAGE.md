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
