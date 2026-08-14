# 第 4 阶段升级与验收手册

> 当前 schema `7.0` 已移除公共资源采购入口，并将公共 Buff 改为跨世界生效、四档时长折扣且不接受退款；旧订单和退款流水只作为历史数据保留。

本阶段交付版本为 `1.3.0`，数据库目标版本为 `4.0`。本次迁移新增 `active_buffs`、`resource_orders`，并扩展公共账本类型；不会删除第 1～3 阶段业务数据。

## 上线前准备

1. 停服备份 TianjiTown SQLite（含 `-wal`、`-shm`）、QuickShop H2、XConomy 数据及清算账户余额。
2. 在隔离预发服恢复生产副本，先用 `1.2.0` 确认原系统正常，再替换为 `1.3.0`。
3. 按实际经济规模调整 `config.yml` 的 `phase4.buffs.catalog` 与 `phase4.resources.catalog`。商品 key 发布后应保持稳定；价格使用十进制字符串。
4. 启动后执行 `/townadmin status`，确认 Flyway 到达 `4.0`，阶段 3 税收、账本和扩张仍可使用。
5. 执行 `/townadmin money reconcile`，确认新增消费前清算账户无短款锁定。

Buff 配置在启动门禁中校验 Potion Effect、Attribute、运算类型和商品角色；资源配置校验 Material、单价、每次上限、每日上限及数量选项。修改商品定义后需要重启，两个 `shop-enabled` 开关支持热重载。

## Buff 验收

分别为 Potion Effect 和 Attribute Modifier 商品执行以下用例：

- 购买前 GUI 显示 1 小时、1 天、1 周、1 月四档时长、折扣价格、下一等级和角色权限。
- 收费随小时数和等级线性增长，长期档按比例折扣，每次只有一条扣款流水。
- 双击确认只产生一次购买；余额不足或账户锁定时不创建 `active_buffs`，也不写扣款流水。
- 成员登录、重生、跨世界后效果正确恢复，公共 Buff 不区分世界。
- Attribute Modifier 的稳定 key 为 `tianjitown:buff_<buffKey>`，重复刷新后只有一个 modifier。
- Buff 到期、玩家退镇、被踢或小镇归档后效果完整移除。
- 玩家和管理员均没有 Buff 退款入口；购买确认页必须明确提示不接受退款。

## 资源订单验收

资源采购采用 `PENDING → CLAIMING → CLAIMED` 状态机。购买事务先创建订单、扣除公共资金并写入账本，随后才允许向玩家背包交付。

| 场景 | 必须确认 |
|---|---|
| 正常购买与领取 | 订单、扣款流水、物品数量和每日额度一致 |
| 满背包 | 订单保持 `PENDING`，不重复扣款，清理空间后可领取 |
| 双击购买/领取 | 只生成一个订单，物品只发放一次 |
| 领取中断线 | 临时物品保持锁定，重连后完成同一领取，不重新发放 |
| 领取中重启 | PDC 领取标记与 `claim_token` 对应，启动后由本人登录恢复 |
| 无效材料或无法交付 | 订单进入 `REFUND_REQUIRED`，管理员可审计后退款 |
| 重复退款 | `RESOURCE_REFUND` 业务键幂等，不重复增加公共余额 |
| 每日限额 | 同镇同商品累计计算，已退款订单不占额度 |

领取确认前，临时物品不能移动、丢弃、交换副手、交互或食用。若服务器在数据库确认前停止，物品保留订单 ID 与领取 token；重连只会完成原订单。数量或 token 不匹配时系统停止解锁并要求管理员检查，避免静默复制或丢失。

管理员用 `/townadmin order list` 查看未完成订单，用 `order create` 代办采购，用 `order refund` 退回尚未领取的订单。`CLAIMING` 或 `CLAIMED` 订单不能直接退款，必须先核对玩家背包和审计记录。

## 功能开关与回滚

```yaml
phase4:
  buffs:
    shop-enabled: false
  resources:
    shop-enabled: false
```

修改后执行 `/townadmin reload`。关闭 Buff 商店只停止新购买，已购买效果继续到期；关闭资源商店只停止新订单，已有订单仍可领取或退款。第 1～3 阶段申请、治理、税收、账本和领地功能不受影响。

回滚前先关闭两个商店并完成未领取订单检查、Buff 退款决定及清算对账，然后停服备份。`1.2.0` 不应直接写入已经迁移到 `4.0` 的数据库；需要回退 JAR 时，同时恢复升级前的 SQLite 备份。QuickShop、XConomy 和 Residence 没有由第 4 阶段改变结构，但仍应保留同一时间点备份以便整组恢复。
