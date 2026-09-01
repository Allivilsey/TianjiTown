# 管理员运维手册

## 每日检查

1. `/townadmin status`：必须为 `READY`，SQLite 为 `READY`，查看最近诊断与备份时间/路径。
2. `/townadmin money reconcile`：清算账户不得短款；出现锁定时先停止消费并核对外部账户。
3. 检查控制台中的 `SEVERE`、`WRITE_LOCKED`、`DIFFERENCE`、`INCOMPLETE` 和 `COMPENSATION_REQUIRED`。

## 常用运维命令

```text
/townadmin status
/townadmin diagnose [1~180]
/townadmin backup
/townadmin maintenance on|off|status
/townadmin money reconcile
/townadmin land reconcile all
/townadmin land reconcile all repair
```

`diagnose` 是只读检查，不会自动修改 QuickShop 历史或 Residence。系统定时领地对账发现差异时，会依照 SQLite 中的小镇、成员和已生效领地单元自动修复；`land reconcile ... repair` 可用于立即手动触发同类修复。`backup` 只覆盖 TianjiTown SQLite 和配置，依赖插件仍按统一备份流程处理。

统一诊断会在插件每次成功启动后延迟约 30 秒执行一次；运行期间不会自动重复。需要立即复核时执行 `/townadmin diagnose [1~180]`。

## 定时任务

| 任务 | 默认周期 | 失败行为 |
|---|---:|---|
| 领地加成缓存刷新 | 30 秒 | 保留最后快照，拒绝无法再次验证的返还 |
| 信标扫描/清理 | 5 秒 | 下次扫描重试；附加效果短时到期 |
| 建筑返还旧计数清理 | 1 小时 | 不影响当日原子上限 |
| SQLite 在线备份 | 6 小时 | 保留旧备份并报警，不覆盖已有文件 |

## 告警处置

- SQLite 不可用：立即维护模式，禁止审批/消费/返还；不要删除 WAL/SHM。确认磁盘、权限和空间后等待连接恢复，再执行统一诊断。
- Residence 异常：系统会依照 SQLite 记录自动修复缺失投影、区域和成员权限；若自动修复失败，先执行只读对账并排除世界未加载、同名外部领地或 Residence API 故障，不要手工覆盖同名领地。
- Vault 短款：公共消费自动锁定。记录账户余额，核对 QuickShop、内部账本和管理员调账，补正外部状态后重新对账。
- 捐款退款失败：系统会在当前运行期内有界重试玩家补偿，并在成功后自动复核清算余额。看到“正在自动补偿”时不要手工重复入账；达到重试上限、插件中途停止或仍显示 `COMPENSATION_REQUIRED` 时，保持消费锁并按操作 ID 人工核对玩家、清算、内部账户和流水。
- QuickShop 差异：保留 H2 只读副本和诊断报告，按时间、收款方、税额核对；不要直接修改 QuickShop 数据库。
- 备份失败：检查目录空间/权限；保留失败日志，手动执行 `/townadmin backup`。依赖备份仍必须完成。

详细故障注入步骤见 [ALERTS_AND_FAULT_INJECTION.md](ALERTS_AND_FAULT_INJECTION.md)。
