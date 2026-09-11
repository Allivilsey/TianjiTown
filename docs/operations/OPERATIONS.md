# 管理员运维手册

完整命令用途、参数、权限及后果见 [管理员命令手册](../ADMIN_COMMANDS.md)，开关和重启范围见 [配置说明](../setup/CONFIGURATION.md)。

## 日常检查

1. 执行 `/tianjitown status`：确认启动状态和 SQLite 状态为 `READY`，检查玩家入口、Buff/返还/信标开关以及最近诊断。
2. 查看后台清算对账日志和账户锁定状态；对账由后台定时执行。
3. 需要复核时执行 `/tianjitown diagnose 7`，保留报告并检查 `DIFFERENCE`、`INCOMPLETE`、`WRITE_LOCKED`、`COMPENSATION_REQUIRED` 和 `SEVERE`。

初始化阶段统一诊断通过后才注册业务运行时；失败保持 `LOCKED`。启动失败应根据 `status` 和日志修复后重启插件或服务器，`/tianjitown reload` 不会重新初始化。运行期间统一诊断只在手动命令时执行，不会周期重复。

## PlugMan 热重载

TianjiTown 使用标准 Bukkit 插件启停流程，无需安装 PlugMan API 依赖。使用与当前服务端版本兼容的 PlugManX；[上游命令说明](https://github.com/Test-Account666/PlugManX)中 `reload` 表示卸载并重新加载 JAR，`restart` 表示停用并重新启用现有实例。

1. 选择没有正在进行的捐款、退款、购买和第三方交易的时段。需要暂停玩家入口时先执行 `/tianjitown maintenance on`。
2. 重新初始化配置或恢复启动检查时执行 `/plugman restart TianjiTown`；重新加载插件 JAR 时执行 `/plugman reload TianjiTown`。替换 JAR 可先 `/plugman unload TianjiTown`，替换完成后 `/plugman load TianjiTown`。
3. 等待 `/tianjitown status` 从 `CHECKING` 变为 `READY`；若为 `LOCKED`，按日志修复后重新启用。PlugMan 的加载成功提示不代表异步启动诊断已经通过。
4. 检查服务台、手册、命令补全和在线玩家 Buff；旧界面和聊天按钮在停用时失效，需要重新打开。确认正常后执行 `/tianjitown maintenance off`（如果之前开启了维护模式）。

停用时注销事件和命令、关闭小镇界面、取消定时任务、等待正在执行的后台任务、清理托管效果并关闭 SQLite 连接池（包括尚未完成启动的连接池）。每次启用都会重读配置与消息、重新校验依赖和数据库，并恢复持久业务状态及在线玩家效果。热重载不会删除数据库、重建已有结构或搬运数据。

支持范围是单独重载 TianjiTown，依赖插件保持启用；`reload all` 或热换 Residence、Vault、经济提供者等依赖不属于此范围。停用最多等待后台任务 30 秒，第三方 API 阻塞导致超时应检查日志并重启服务器；内存中的待重试退款和未保存表单不会跨重载保留，需要按操作 ID 核对未完成交易。插件停用期间小镇税收监听和入口不可用，维护模式本身不会阻止第三方交易。

预发验收：连续执行三次 `reload`，再执行 `disable` / `enable` 和 `restart`；每次确认 `READY`、命令仅执行一次、一次交易仅入账一次、Buff 不叠加、服务台与原有数据仍可使用。另在 `CHECKING` 阶段立即停用再启用，确认没有遗留连接池、旧回调或 SQLite 锁定。自动化测试覆盖生命周期和数据库回收，不能替代当前 Paper/PlugMan/依赖组合的真实服务器验收。

## 暂停功能

| 目标 | 操作 | 仍会继续的事项 |
|---|---|---|
| 暂停玩家入口 | `/tianjitown maintenance on`，恢复用 `off`，查询用 `status` | 管理员命令、税收、后台任务和 Residence 保护 |
| 停止新小镇税 | 配置 `economy.tax.enabled: false` 后 `reload` | 已提交税款处理、查询和对账 |
| 停止新捐款/扩张/Buff 资金入口 | `economy.consumption.enabled: false` 后 `reload` | 税收、已有补偿和查询 |
| 仅关 Buff 商店 | `buffs.shop-enabled: false` 后 `reload` | 已有 Buff 生效与到期清理 |
| 暂停领地福利 | 分别关闭 `territory.building-refund.enabled`、`territory.beacon.enabled` 后 `reload` | Residence 保护及持久记录；托管信标效果会清理 |

一致性备份必须停服，维护模式不是数据库静止保证。恢复业务前核对开关、数据库和清算状态。

## 后台任务

下表是正常运行后的周期，tick 按 20 TPS 换算。

| 任务 | 默认周期 | 作用 |
|---|---|---|
| 身份变更通知 | 1 秒 | 投递持久待通知记录 |
| SQLite 恢复探测 | 30 秒 | 运行期数据库恢复后尝试恢复写入 |
| Residence 自动对账 | 1 小时，启动约 10 秒后首次 | 按 SQLite 修复领地及访问权限差异 |
| 到期投票结算 | 1 分钟 | 结算到期开放投票 |
| 清算对账 | 5 分钟，启动约 20 秒后首次 | 判断公共资金消费是否应锁定 |
| 领地加成索引刷新 | 30 秒 | 刷新成员、领地及已记录信标效果快照 |
| 信标效果刷新 | 100 tick | 按玩家位置应用/清理已记录效果，不扫描信标 |
| 旧建筑返还计数清理 | 1 小时 | 清理超过保留周数的计数，不改变当前周上限 |

## 故障处置

| 现象 | 处理 |
|---|---|
| SQLite 不可用 | 暂停玩家入口，检查磁盘、空间和权限；不删除 WAL/SHM。运行期恢复后等待探测并诊断；启动 LOCKED 修复后重启 |
| Residence 缺失或权限不符 | 先 `land reconcile <小镇代码>` 只读检查，排除世界未加载、同名外部领地和 API 故障，再显式加 `repair`；`rebuild` 会先删除投影，需要确认 |
| 清算短款 | 公共消费自动锁定，记录余额并核对内部账本和外部交易，补正实际差异后等待后台清算对账；对账本身不会补款 |
| 捐款退款失败 | 当前进程有界自动重试，成功后再做清算复核；不要重复手工入账。重试耗尽或中途停服时，按操作 ID 核对玩家、清算、内部账户与流水 |
| QuickShop 诊断差异 | 保留只读历史副本及报告，按交易方向、玩家、金额、税额和时间核对，不能直接改外部历史以消除差异 |
| 诊断 INCOMPLETE | 检查查询失败和 1000 条上限，可缩短回看窗口复核；不能把扫描不完整当成一致 |

`diagnose` 只读，不会修改 QuickShop 或 Residence。后台自动对账会修复领地，手动 `land reconcile ... repair` 可立即触发；两者与诊断是不同操作。

备份和恢复见 [SQLite 手册](SQLITE_AND_BACKUP.md)，故障演练见 [告警与验收](ALERTS_AND_FAULT_INJECTION.md)。
