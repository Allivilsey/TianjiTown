# 插件审查：确定性缺陷与旧实现残留

审查日期：2026-09-07。版本：1.0.0-SNAPSHOT。

本轮检查四个模块的源码、默认配置、初始数据库结构和现有测试，重点追踪资金结算、成员治理、建镇/扩张、Buff、第三方事件、启动与 UI 回调。执行全量 Maven verify，并对资金问题补充隔离复现。没有修改生产代码、配置或用户数据库。以下不是“已证明不存在其他 bug”的结论；没有启动真实 Paper 服务器进行多插件联调。

## 确定存在的 bug

### 1. [P1] 清算对账覆盖补偿锁，随后错误解锁

位置：`tianjitown-storage/src/main/java/org/allivlisey/tianjitown/storage/economy/EconomyOperationStore.java:210`。

`requireCompensation()` 将账户锁定为 `ECONOMY_COMPENSATION:`。`reconcileSettlement()` 在余额不足时无条件覆盖所有账户的锁定原因；下一次余额充足时，又把所有 `SETTLEMENT_RECONCILIATION:` 锁清除，没有排除仍存在待补偿操作的账户。

确定复现：内部余额 1000，另有一笔 `COMPENSATION_REQUIRED` 捐款；调用 `reconcileSettlement(999)`，再调用 `reconcileSettlement(1000)`。待补偿记录仍存在，但账户 `locked=false`。这允许未完成资金核实的账户继续消费。

验证：`AuditEconomyReproductionTest.reconciliationErasesUnresolvedCompensationLock` 已运行通过，断言的是上述错误行为。

修复方向：对账不得覆盖其他业务锁；解锁时必须检查其他未解决锁因。若继续使用单一原因字段，至少保留补偿锁，并在清除对账锁时核实待补偿记录。

### 2. [P1] 管理员扣款未预留余额，外部扣款后可能无法记账

位置：`tianjitown-storage/src/main/java/org/allivlisey/tianjitown/storage/economy/EconomyOperationStore.java:49`；外部执行及落账：`tianjitown-paper/src/main/java/org/allivlisey/tianjitown/paper/runtime/TownEconomyRuntime.java:343`。

`prepareOperation()` 只拿当前余额检查负数操作，不扣减或预留，也不扣除其他未完成操作占用的余额。外部 Vault 操作与最终数据库记账跨多个异步/主线程阶段，两个请求可以在任一请求落账前都准备成功。单连接只串行化各个数据库事务，不能串行化整条结算链。

确定复现：某镇余额 1000，先后准备两笔各 -700 的管理员调账，均成功；都标记外部执行后，第一笔记账成功，第二笔抛出“小镇余额不足”。如果共享 Vault 清算账户还有其他镇的余额，两次外部扣款可以都成功，最终内部余额仍为 300，第二笔停留在 `EXTERNAL_APPLIED`。运行时的落账失败分支只上报异常，没有立即补偿外部扣款。

验证：`AuditEconomyReproductionTest.pendingAdminDebitsDoNotReserveBalance` 已复现仓储允许超额准备、后续落账失败。外部资金后果通过运行时调用顺序确认，未对真实 Vault 发起扣款。

修复方向：事务内预留扣款资金，并让其他消费也尊重预留；处理失败与取消时释放预留。外部成功、内部失败需要明确补偿/恢复机制。

### 3. [P1] Jobs 等待超时后，排队税务任务仍执行

位置：`tianjitown-integrations/src/main/java/org/allivlisey/tianjitown/integrations/jobs/JobsIncomeTaxAdapter.java:100`；资金副作用：`tianjitown-paper/src/main/java/org/allivlisey/tianjitown/paper/runtime/TownTaxRuntime.java:235`。

异步付款事件通过 `callSyncMethod(...).get(10, SECONDS)` 等待主线程税务处理。超时后抛出异常并被事件处理器记录，此时不会执行 `setAmount(net)`；但排队的 Future 没有被取消，也没有超时状态阻止后续副作用。

确定触发条件：主线程阻塞超过等待期限。事件处理结束后，主线程恢复，旧任务仍调用税务 processor。该 processor 向清算账户增加税款并提交税收/补贴处理，玩家原始付款金额却未扣税。由此产生没有对应玩家扣税的税款及潜在补贴。

验证：`AuditJobsTimeoutTest.timedOutProcessorStillExecutesLater` 用立即超时的模拟 Future 和可手动执行的排队 Callable，证明超时异常返回后 processor 仍执行；没有依赖真实等待或真实 Jobs 实例。

修复方向：明确任务未开始、执行中和已超时状态之间的互斥协议。仅增加 `cancel(false)` 无法完整处理已开始执行的竞态；必须保证放弃修改事件金额后不再提交征税副作用。

### 4. [P1] Buff 清理会删除非本插件施加的药水效果

位置：`tianjitown-paper/src/main/java/org/allivlisey/tianjitown/paper/buff/BuffPlayerEffects.java:295`。

`managedPotionTypes()` 除了读取已施加效果和 PDC 标记，还把配置目录中所有 POTION 类型无条件加入“已托管”集合。`reconcilePotions()` 随后删除其中不在当前购买列表的效果。

默认配置包含夜视、水肺、抗火。因此一个从未购买小镇 Buff、也没有托管标记的玩家，自行喝下抗火药水后触发换世界等刷新，空 Buff 列表仍会导致 `removePotionEffect(FIRE_RESISTANCE)`。`clearAll()` 也使用同一集合，且购买成功会刷新所有在线玩家，影响范围不限于购买者所在小镇。

验证：完整静态调用链为 `BuffRuntime.refreshPlayer()` → `effects.applyBuffs()` → `reconcilePotions()` → `managedPotionTypes()`。默认配置和无条件删除分支直接确定该行为；本项未在真实服务器或模拟 Registry 环境执行药水测试。

修复方向：不要把配置中支持的效果等同于已施加的效果；仅清理实际托管且仍匹配本插件施加特征的效果，处理外部药水覆盖场景。

## 旧实现残留

1. **消息兼容迁移仍运行，直接违背当前开发约定。** `PluginMessages.java:27` 保留大量 `LEGACY_ALIASES`；`reload():168` 调用 `migrateLegacyMessages()`，把旧用户消息键映射到新键；`canonicalKey():426` 继续兼容旧调用键。应统一调用方键名后移除迁移和别名映射，保留默认消息和必要内容校验。
2. **旧消息校验死代码。** 同文件 `validateLegacyRequiredMessages():209` 标有 `@Deprecated(forRemoval=true)`，全仓搜索没有调用；实际加载调用的是新校验函数。可直接清理该私有方法。
3. **已移除资源商店仍留数据库结构和查询。** 初始 SQL 的 `resource_orders:194`、相关索引/触发器、`RESOURCE_PURCHASE/RESOURCE_REFUND` 账本类型仍存在；`TownDiagnosticRepository.java:59` 仍统计 `legacyOpenResourceOrders`，`TownProvisioningStore.java:322` 仍删除该表记录，`TownFinanceDialogs.java:283` 仍渲染旧账目类型。没有发现当前生产代码创建这些订单。应同步清理初始结构和引用；不需要新增历史迁移或处理旧开发数据库。
4. **Buff 保留两套购买模型。** 玩家界面使用周数/强度接口，另一路仍保留 `BuffDurationOption` 的小时、天、月选项与旧叠加报价链；管理员 `TownAdminEconomyCommands.java:113` 仍走旧链的 `ONE_WEEK`，所以不能直接删除整条链。另有 `allowed_worlds` 列、读取字段及 `ActiveBuff.allowsWorld()`，当前购买均写空字符串，运行时没有调用该世界筛选方法。应先统一管理员购买语义，再清理旧接口和失效字段；本项按重构残留记录，没有把两套语义差异直接当作确定性 bug。
5. **领地结果仍有旧字符串兼容通道。** `LandProtectionService.java:169` 起同时保留 `code/parameters` 与 `legacyMessage`、字符串工厂和 `message()`。当前 Residence 生产实现使用结构化结果，但消息渲染器仍支持旧分支，测试也使用字符串构造。可在统一测试及内部调用后移除旧协议。

`TownUiLegacyFacade` 和 `TownActions` 虽然名称/注释写着兼容，但当前功能仍通过它们组装和调用，不能仅凭名称视为可删除死代码。`LegacyComponentSerializer` 是实际颜色文本序列化用途，也不属于旧插件版本兼容层。

未发现项目版本偏离 `1.0.0-SNAPSHOT`，配置版本字段/版本门禁，或新增历史数据库迁移。Flyway 的初始结构及完整性校验不作为旧版本残留报告。

## 验证材料与复跑

- 全量 `mvn -B verify`：401 项现有测试通过，0 失败、0 错误、0 跳过。日志为同目录 `verify.log`。
- 移出临时探针后再次运行 `mvn -B clean verify`，401 项现有测试全部通过，日志为 `final-verify.log`。
- 三项审查探针通过，证明错误路径可复现，日志为 `reproduction.log`。
- 探针源码保存为同目录 `AuditEconomyReproductionTest.java.txt` 和 `AuditJobsTimeoutTest.java.txt`。它们断言当前错误行为，不应作为永久回归测试直接合入。
- 复跑时，将两个文件分别复制到 storage 的 `src/test/java/org/allivlisey/tianjitown/storage/economy/` 和 integrations 的 `src/test/java/org/allivlisey/tianjitown/integrations/jobs/`，移除文件名末尾 `.txt`；执行：

```powershell
mvn -B -pl tianjitown-storage,tianjitown-integrations -am '-Dtest=Audit*Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

SQLite 测试使用 JUnit 临时目录；审查结束已移出临时测试源文件，仅保留本目录的报告、探针副本和日志。
