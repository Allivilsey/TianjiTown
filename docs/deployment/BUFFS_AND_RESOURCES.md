# 公共 Buff 与历史资源数据升级验收

当前 schema `7.0` 已下线公共资源采购。`resource_orders`、`RESOURCE_PURCHASE`
与 `RESOURCE_REFUND` 只为兼容旧数据库和历史账本保留，不再属于可执行功能。

## 上线前准备

1. 停服备份 TianjiTown SQLite、QuickShop H2、XConomy 数据及清算账户余额。
2. 在隔离预发服恢复生产副本，再替换当前版本 JAR。
3. 按实际经济规模调整 `config.yml` 的 `phase4.buffs.catalog`；修改目录后需要重启。
4. 启动后执行 `/townadmin status` 和 `/townadmin money reconcile`，确认迁移完成且清算账户无短款。

## Buff 验收

分别为 Potion Effect 和 Attribute Modifier 商品执行以下用例：

- GUI 显示 1 小时、1 天、1 周、1 月四档时长、折扣价格、下一等级和角色权限。
- 收费随小时数和等级线性增长，长期档按比例折扣，每次只有一条扣款流水。
- 双击确认只产生一次购买；余额不足或账户锁定时不创建 `active_buffs`。
- 成员登录、重生、跨世界后效果正确恢复，公共 Buff 不区分世界。
- Attribute Modifier 使用稳定 key，重复刷新后只有一个 modifier。
- Buff 到期、玩家退镇、被踢或小镇归档后效果完整移除。
- 玩家和管理员均没有 Buff 退款入口；购买确认页明确提示不接受退款。

## 历史资源数据兼容

升级前后必须核对：

- `resource_orders` 的行数、业务键、状态、金额和时间字段保持不变；
- `RESOURCE_PURCHASE`、`RESOURCE_REFUND` 流水仍能在历史账本中正常显示；
- 当前配置不存在 `phase4.resources`，GUI、帮助、Tab 补全和命令均没有资源入口；
- 玩家登录、重启、定时任务和管理员操作不会领取、补发、退款或修改旧订单状态；
- 统一诊断只读统计未结束的历史订单，不尝试自动修复。

不要删除或改写已经发布的 Flyway 迁移。直接修改旧迁移会破坏现有数据库的
checksum 校验；直接删除历史表或流水类型会造成审计数据丢失。

## 功能开关与回滚

`phase4.buffs.shop-enabled` 可通过 `/townadmin reload` 热更新。关闭后只停止新购买，
已购买效果继续到期并正常清理。

回滚前停止新的经济和 Buff 写入，完成清算对账并备份。旧版本可能重新包含资源订单
入口，因此不得让旧 JAR 直接写入当前生产数据库；需要回退时应恢复同一时间点的整套数据。
