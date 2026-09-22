# 管理员运维手册

完整命令用途、参数、权限及后果见 [管理员命令手册](../ADMIN_COMMANDS.md)，开关和重启范围见 [配置说明](../setup/CONFIGURATION.md)。

## 日常检查

1. 执行 `/tianjitown status`：确认启动状态和 SQLite 状态为 `READY`，检查玩家入口、Buff/返还/信标开关以及最近诊断。
2. 查看公共账本、待核实资金操作及账户锁定状态。
3. 需要复核时执行 `/tianjitown diagnose`，保留报告并检查 `WRITE_LOCKED`、`COMPENSATION_REQUIRED` 和 `SEVERE`。

初始化阶段先校验必要依赖、玩家经济服务及 SQLite 完整性，再恢复中断状态并执行统一诊断。数据库损坏、配置或必要依赖不可用仍保持 `LOCKED`；待处理交易、领地差异和外部诊断故障以告警报告，允许业务运行时及恢复入口启动。未核实的资金操作冻结对应小镇账户；不再检查外部清算余额。启动失败应根据 `status` 和日志修复后重启插件或服务器，`/tianjitown reload` 不会重新初始化。运行期间统一诊断只在手动命令时执行，不会周期重复。

## 付款异常的核实与恢复

先通过 `/tianjitown money pending` 和 `/tianjitown application fee list` 查询持久化记录，核对玩家、小镇账本和经济插件交易历史。重启会取消确定尚未执行外部付款的 `PREPARED` 操作，将付款结果不明的记录保留给人工核实；不会为了启动而重复付款。

- 普通资金操作：`/tianjitown money resolve <操作UUID> <applied|cancelled> <核实依据>`。`applied` 表示完整外部资金流已完成，补记一次内部账；`cancelled` 表示未付款或已全额恢复原状。部分扣款不能直接视为任一种完整结果，应先查明并修复外部资金。
- 申请费：`/tianjitown application fee inspect <申请UUID>` 查询金额、状态和版本；`retry` 仅重新尝试确定未支付的退款。付款结果未知时使用 `resolve <申请UUID> <结论> <核实依据>`，结论含 `COLLECTED`（已扣玩家，申请费应入账）、`NO_PAYMENT`（双方无净变动）、`PLAYER_DEBIT_ONLY`（旧托管流程只扣玩家、需退款）、`REFUNDED`（从正确来源全额退款）、`REFUND_NOT_PAID`（未退玩家且应退资金完整保留）。已取消且不再关联小镇的申请仍可处理退款。
- 补贴：`/tianjitown money subsidy pending` 查询；`resolve <业务键> <paid|cancelled> <核实依据>` 仅用于处理升级前遗留的补贴预留：核实后补记或取消。新补贴与税款同事务入账，不产生外部补贴付款。
- Jobs/GMP 小镇税款：`/tianjitown money tax pending` 查询；`resolve <操作UUID> <paid|cancelled> <核实依据>` 分别确认完整收税或玩家未扣款/已全额退回。`refund <操作UUID>` 只对 `REFUND_REQUIRED` 明确欠退玩家的记录实际退款，结果未知时必须先核账。重启发现已收成功而未记账时只补税账，不重复扣玩家，也不重新发放补贴；补贴存在未知预留时转独立核实。

上述确认需要完整管理员权限和二次确认，记录审计。`resolve` 不调用 Vault 付款；申请费记录版本变化、运行时更换、权限撤销或对应操作仍在执行时拒绝旧确认。处理完成后自动解除对应操作锁；不要修改 SQL 状态或仅为解除锁而填写未经核实的结论。

## PlugMan 热重载

TianjiTown 使用标准 Bukkit 插件启停流程，无需安装 PlugMan API 依赖。使用与当前服务端版本兼容的 PlugManX；[上游命令说明](https://github.com/Test-Account666/PlugManX)中 `reload` 表示卸载并重新加载 JAR，`restart` 表示停用并重新启用现有实例。

1. 选择没有正在进行的捐款、退款、购买和第三方交易的时段。需要暂停玩家入口时先执行 `/tianjitown maintenance on`。
2. 重新初始化配置或恢复启动检查时执行 `/plugman restart TianjiTown`；重新加载插件 JAR 时执行 `/plugman reload TianjiTown`。替换 JAR 可先 `/plugman unload TianjiTown`，替换完成后 `/plugman load TianjiTown`。
3. 等待 `/tianjitown status` 从 `CHECKING` 变为 `READY`；若为 `LOCKED`，按日志修复后重新启用。PlugMan 的加载成功提示不代表异步启动诊断已经通过。
4. 检查服务台、手册、命令补全和在线玩家 Buff；旧界面和聊天按钮在停用时失效，需要重新打开。确认正常后执行 `/tianjitown maintenance off`（如果之前开启了维护模式）。

停用时注销事件和命令、关闭小镇界面、取消定时任务、等待正在执行的后台任务、清理公共 Buff 的属性修饰符、释放信标来源缓存并关闭 SQLite 连接池（包括尚未完成启动的连接池）；已施加的信标药水效果自然到期。每次启用都会重读配置与消息、重新校验依赖和数据库，并恢复持久业务状态及重新发现有效信标。热重载不会删除数据库、重建已有结构或搬运数据。

支持范围是单独重载 TianjiTown，依赖插件保持启用；`reload all` 或热换 Residence、Vault、经济提供者等依赖不属于此范围。停用最多等待后台任务 30 秒，第三方 API 阻塞导致超时应检查日志并重启服务器；内存中的待重试退款和未保存表单不会跨重载保留，需要按操作 ID 核对未完成交易。插件停用期间小镇税收监听和入口不可用，维护模式本身不会阻止第三方交易。

预发验收：连续执行三次 `reload`，再执行 `disable` / `enable` 和 `restart`；每次确认 `READY`、命令仅执行一次、一次交易仅入账一次、Buff 不叠加、服务台与原有数据仍可使用。另在 `CHECKING` 阶段立即停用再启用，确认没有遗留连接池、旧回调或 SQLite 锁定。自动化测试覆盖生命周期和数据库回收，不能替代当前 Paper/PlugMan/依赖组合的真实服务器验收。

## 暂停功能

| 目标 | 操作 | 仍会继续的事项 |
|---|---|---|
| 暂停玩家入口 | `/tianjitown maintenance on`，恢复用 `off`，查询用 `status` | 管理员命令、税收、后台任务和 Residence 保护 |
| 停止新小镇税 | 配置 `economy.tax.enabled: false` 后 `reload` | 已提交税款处理、查询和对账 |
| 停止新捐款/扩张/Buff 资金入口 | `economy.consumption.enabled: false` 后 `reload` | 税收、已有补偿和查询 |
| 仅关 Buff 商店 | `buffs.shop-enabled: false` 后 `reload` | 已有 Buff 生效与到期清理 |
| 暂停领地福利 | 分别关闭 `territory.building-refund.enabled`、`territory.beacon.enabled` 后 `reload` | Residence 保护及持久记录；信标停止续期，已有药水效果自然到期 |

一致性备份必须停服，维护模式不是数据库静止保证。恢复业务前核对开关、数据库和未决付款状态。

## 后台任务

下表是正常运行后的周期，tick 按 20 TPS 换算。

| 任务 | 默认周期 | 作用 |
|---|---|---|
| 身份变更通知 | 1 秒 | 投递持久待通知记录 |
| SQLite 恢复探测 | 30 秒 | 运行期数据库恢复后尝试恢复写入 |
| Residence 自动对账 | 1 小时，启动约 10 秒后首次 | 按 SQLite 修复领地及访问权限差异 |
| 到期投票结算 | 1 分钟 | 结算到期开放投票 |
| 领地加成索引刷新 | 30 秒 | 刷新成员和领地快照，触发已加载来源重新核对 |
| 信标效果刷新 | 100 tick | 校验当前信标来源并按玩家位置续期原版时长效果，不主动清除药水效果 |
| 旧建筑返还计数清理 | 1 小时 | 清理超过保留周数的计数，不改变当前周上限 |

## 故障处置

| 现象 | 处理 |
|---|---|
| SQLite 不可用 | 暂停玩家入口，检查磁盘、空间和权限；不删除 WAL/SHM。运行期恢复后等待探测并诊断；启动 LOCKED 修复后重启 |
| Residence 缺失或权限不符 | 先 `land reconcile <小镇代码>` 只读检查，排除世界未加载、同名外部领地和 API 故障，再显式加 `repair`；`rebuild` 会先删除投影，需要确认 |
| 捐款退款失败 | 当前进程有界自动重试，成功后完成数据库收尾并解除对应操作锁；不要重复手工入账。重试耗尽或中途停服时，按操作 ID 核对玩家、小镇账户与流水 |
| QuickShop 收税异常 | 查看税务适配和入账错误日志，核实交易方向、收款人、税额及本地账本；需要纠正时使用管理员调账 |

`diagnose` 只读，检查 SQLite 和 Residence，不读取 QuickShop 历史，也不接受回看天数参数。诊断告警应按报告中的数据库异常计数、世界 UUID 和 Residence 差异逐项处理。后台自动对账会修复领地，手动 `land reconcile ... repair` 可立即触发；两者与诊断是不同操作。

领地权限核对直接读取玩家 UUID 对应的权限条目，按 Residence 当前 `padd` 权限组及小镇额外授权检查，不依赖玩家是否在线或已进入 Residence 玩家缓存。后台自动修复写入成功后会再次检查领地；复查通过才记录“已修复”，仍有差异则报告具体失败原因，API 异常也不会记作修复成功。

备份和恢复见 [SQLite 手册](SQLITE_AND_BACKUP.md)，故障演练见 [告警与验收](ALERTS_AND_FAULT_INJECTION.md)。
