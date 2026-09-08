# 插件审查：确定性缺陷与旧实现残留

日期：2026-09-08。版本：1.0.0-SNAPSHOT。

检查范围覆盖 core、storage、integrations、paper 的业务模型、数据库事务与初始结构、资金与补偿、建镇与扩张恢复、成员治理、Buff/信标、启动调度、配置和 UI 调用链。对照了 2026-09-07 的审查与修复记录，没有把已修复问题重复列入。未修改生产源码、配置、初始 SQL 或用户数据库。

本报告区分执行复现与静态证据；没有启动真实 Paper/Vault/Residence/QuickShop 服务器。通过测试不等于排除其他缺陷。

## 确定存在的缺陷

### 1. [P1] 批量扩张被单笔与批次两套启动恢复重复处理，可重复退款

位置：`tianjitown-storage/src/main/java/org/allivlisey/tianjitown/storage/economy/TerritoryExpansionStore.java:201`；调用链：`TownRuntime.java:270`、`TownExpansionRuntime.java:387`。

`pendingExpansions()` 查询没有排除 `batch_id IS NOT NULL` 的批量子项。启动先把这些子项交给 `recoverExpansions()`，随后又把父批次交给 `recoverExpansionBatches()`；异步调度允许两份恢复快照都在状态完成前取得。

单笔失败走 `refundExpansion()`，使用子项 business key 退款并删除子项；批次失败走 `refundExpansionBatch()`，仍按父批次原始总价退款。两条退款幂等键不同，后者不扣除已单独退还的金额。

**执行复现**：初始余额 10000，准备一个价格 200 的批次；两个待恢复查询均返回同一笔业务。依次执行子项和父批次的失败退款后，余额为 **10200**，高于购买前余额。见 `AuditExpansionTest.batchChildIsRecoveredAsStandaloneAndCanBeRefundedTwice`。

修复方向：单笔恢复仅选择无父批次的操作；单笔完成/退款接口也拒绝批量子项，确保批次只有一个结算入口。

### 2. [P1] 自动退款忽略“不确定结果”，可能再次向玩家付款

位置：`tianjitown-paper/src/main/java/org/allivlisey/tianjitown/paper/economy/DonationRefundCoordinator.java:112`。

`VaultSettlementService.refundDebitedPlayer()` 在充值抛异常或返回空响应时，以 `compensationRequired=true` 表示结果不确定。自动退款协调器仅区分 `success()`，把所有失败都继续排队，没有检查该标记。

若提供方已完成充值，但返回结果丢失，下一次尝试会再次充值；随后还会调用 `resolveCompensation()`，把已经多退的操作标记为处理完成。

**执行复现**：测试替身每次调用先增加玩家余额，第一次返回“不确定”，第二次返回成功。应退款 100，实际调用两次、增加 **200**，最后仍完成补偿收尾。见 `AuditRefundTest.ambiguousSuccessfulCreditIsPaidAgain`。这是可控故障注入，未操作真实玩家账户。

修复方向：明确失败才允许重试外部付款；结果不确定应停止再次充值，保留待核实记录和锁。协调器自己捕获到的外部异常也不能默认解释为“肯定没有付款”。

### 3. [P1] QuickShop 补贴准备或支付失败，会遗失已经收取的税款记账任务

位置：`tianjitown-paper/src/main/java/org/allivlisey/tianjitown/paper/runtime/TownTaxRuntime.java:160`、`:182`。

进入 `acceptQuickShopTax()` 的是成功交易税款。补贴预留失败的 catch 只记录日志；补贴支付失败则取消预留并记录日志。只有补贴成功才把税款提交给 `pendingTaxes`，因此上述失败不会记录实际税款，也不会进入记账重试队列。

**执行复现**：分别注入 SQLite 预留失败与明确的 Vault 补贴失败，随后调用 `flushPendingTaxes()`；工作队列、主线程队列与延迟队列均为空，`recordQuickShopTax()` 从未调用。见 `AuditQuickShopTest.collectedTaxIsAbandonedWhenReservationOrSubsidyFails`，两种参数均通过。

影响：玩家已被扣税，外部清算账户已有收入，但小镇内部余额与交易证据缺失；常规重试不会补上。对账只检查外部余额是否不足，也不能据此恢复这笔税款。

修复方向：让税款事件从补贴预留阶段开始进入可恢复任务；把已收税款的入账责任与补贴失败解耦，同时保留补贴结果不确定时的人工核实证据。

### 4. [P1] 新扩张可以依赖未完成单元，前一批回滚后留下孤立领地

位置：`tianjitown-storage/src/main/java/org/allivlisey/tianjitown/storage/economy/TerritoryExpansionBatchStore.java:50`、`:91`、`:213`；预览：`TerritoryService.java:225`。

预览只排除 FAILED 单元，事务连通性校验使用所有现存单元，因此 PREPARED 扩张也充当连接点。每笔事务检查领地数量并不能防止后续请求在前一笔已预留、尚未完成时读取新数量并继续准备。

**执行复现**：已有 `(0,0)`，先准备 A=`(1,0)`，再准备 B=`(2,0)`；完成 B 后退款 A。最终仅剩 `(0,0)` 和 `(2,0)`，均为 ACTIVE，但 `TerritoryRules.requireConnected()` 明确失败。见 `AuditExpansionTest.rollbackOfPendingBridgeLeavesCompletedIsland`。

单笔扩张也使用相同的连接点逻辑。Residence 只是多个区域的投影，本轮没有证明真实服务器必然按此时序发生；已证明仓储允许该状态转换，且正常运行时没有同镇扩张互斥。

修复方向：同镇已有未完成扩张时拒绝另一笔扩张，或者建立明确依赖与级联回滚；不能把未完成单元当作独立购买的稳定连接点。

### 5. [P2] 接任候选人退镇后，失效转让请求仍阻止新的转让

位置：`tianjitown-storage/src/main/java/org/allivlisey/tianjitown/storage/governance/MayorTransferStore.java:41`；离镇：`TownMembershipStore.java:210`；唯一约束：初始 SQL `:171`。

请求表对每镇 PENDING 请求设唯一约束，但成员离镇没有取消相关转让。`requestMayorTransfer()` 只清理时间到期的请求，因此已经不在本镇的候选人仍占用转让名额。候选人不属于任何镇时，玩家处理界面又因 `dashboard()` 为空而无法打开。

**执行复现**：镇长向 A 发起 24 小时转让，A 主动退镇；A 的 dashboard 为空，镇长向仍在本镇的 B 发起新请求抛出冲突，数据库旧请求仍为 PENDING。见 `AuditExpansionTest.departedCandidateKeepsTransferSlotOccupied`。

修复方向：成员离镇/被移除以及镇长身份变化时，在同一事务内取消失效请求；查询也应核实双方仍满足转让条件。

### 6. [P2] 信标增强误认药水所有权，离开领地时会删除外部效果

位置：`tianjitown-paper/src/main/java/org/allivlisey/tianjitown/paper/bonus/TownBeaconEffects.java:205`、`:244`。

`applyManagedEffects()` 忽略 `addPotionEffect()` 的返回值，无条件写入 `managedEffects`。清理时只检查等级相同、剩余时间不超过阈值，没有核实效果实际由本插件施加，也没有在其他来源替换药水时释放所有权。

**静态确认的触发条件**：刷新尝试添加效果但被其他插件取消，玩家保留原本同等级且剩余时间较短的药水；随后离开领地或关闭信标增强，清理条件成立，原药水被移除。另一条路径是刷新后外部插件替换为同等级、短时长药水，也会被同样移除。默认刷新间隔 100 ticks 时，清理阈值为 280 ticks。

已核对本地 Paper API 源码对 `addPotionEffect()` 返回值的定义。本项为调用链与条件检查确认，未在 Registry/真实服务器环境运行药水复现；不能将前一轮公共 Buff 的修复视为信标模块也已修复。

修复方向：成功施加后再记录所有权，跟踪外部替换并释放所有权，清理前复核实际效果来源/签名。

## 旧实现与冗余模型残留

1. **旧信标恢复空方法仍在启动调用。** `TownBonusRuntime.java:111` 的 `recoverTaggedBeacons()` 仅剩“第七版不再修改或扫描信标方块”注释，`TownComponentRegistrar.java:94` 仍调用。方法与调用可一起清理。
2. **投影丢失自动归档的旧入口仍保留。** `TownDeletionStore.java:83` 的 `archiveTownForMissingProjection()` 和 `TownRepository.java:394` 转发入口，生产代码没有调用，只有仓储测试使用。当前 `AutomaticLandReconciler` 对投影缺失执行恢复。旧入口仍包含归档、删除成员等整段行为，应在确认不再提供该业务入口后连同对应旧测试清理；不要误删正常解散/管理员删除流程。
3. **旧小镇代码的放宽校验。** 玩家新申请要求 3～9 位，但 `TownResidenceName` 保留 1～12 位；`ApplicationText.requireValidExistingProfile():55` 过滤所有代码校验错误，`TownProfileStore` 使用该方法，另有 `legacyTownCodesStillLoadAndAllowProfileManagement` 测试明确保护旧代码。该行为在部署文档中有说明，因此不是意外绕过新建限制，但确属仍在运行的旧数据适配。按当前不保留旧开发数据库兼容逻辑的约定，应统一约束和测试；无需回填或改写已有数据库。
4. **旧简称字段仍贯穿存储模型。** 当前申请表单把同一个 `townCode` 同时传给 `shortName` 与 `residenceName`（`TownApplicationFormDialogs.java:116`），但 ApplicationText、草稿、申请、小镇表仍保留独立简称字段、规范化值、唯一约束和判重 SQL。当前玩家没有独立简称输入。这是冗余模型残留，不单独算作运行时 bug；清理应同步模型、初始 SQL、查询与测试，不能只删字段读写的一侧。

未再发现上一轮已清理的消息别名迁移、资源订单表、旧 Buff 时长枚举、allowed_worlds 或 OFFICER 角色。未发现项目版本偏离 1.0.0-SNAPSHOT、配置版本门禁或新增历史迁移链。Flyway 初始建表/校验、默认消息补全、配置修改后的属性清理，以及用于颜色文本的 LegacyComponentSerializer 不属于应删除的旧版本兼容代码。TownUiLegacyFacade 仍是活动调用入口，不能按名字直接删除。

## 验证与材料

- 初始 `mvn -B verify`：Core 51、Storage 58、Integrations 51、Paper 283，共 **443** 项测试，0 失败、0 错误、0 跳过。
- 6 项隔离故障探针全部通过，证明的是现存错误路径。探针使用临时 SQLite 或测试替身，没有访问用户数据库或实际账户。
- 探针源码已移出 `src/test`，保存在本目录的 `AuditExpansionTest.java.txt`、`AuditQuickShopTest.java.txt`、`AuditRefundTest.java.txt`；不应将断言错误行为的探针作为永久回归测试直接合入。
- 移出探针后 `mvn -B clean verify` 成功，443 项现有测试全部通过。日志：`verify.log`、`probes.log`、`final-verify.log`。

复跑探针：把三个副本恢复到其 package 对应的 storage/paper `src/test/java` 目录，去掉文件名末尾 `.txt`，执行：

```powershell
mvn -B '-Dtest=Audit*Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

本次交付为审查结果和证据，没有进行缺陷修复。
