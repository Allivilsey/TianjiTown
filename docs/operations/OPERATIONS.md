# 管理员运维手册

完整命令用途、参数、权限及后果见 [管理员命令手册](../ADMIN_COMMANDS.md)，开关和重启范围见 [配置说明](../setup/CONFIGURATION.md)。

## 日常检查

1. 执行 `/tianjitown status`：确认启动状态和 SQLite 状态为 `READY`，检查玩家入口、Buff/返还/信标开关以及最近诊断。
2. 查看后台清算对账日志和账户锁定状态；对账由后台定时执行。
3. 需要复核时执行 `/tianjitown diagnose 7`，保留报告并检查 `DIFFERENCE`、`INCOMPLETE`、`WRITE_LOCKED`、`COMPENSATION_REQUIRED` 和 `SEVERE`。

初始化阶段统一诊断通过后才注册业务运行时；失败保持 `LOCKED`。启动失败应根据 `status` 和日志修复后重启，`reload` 不会重新初始化。运行期间统一诊断只在手动命令时执行，不会周期重复。

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
