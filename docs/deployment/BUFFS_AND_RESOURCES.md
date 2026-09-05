# 公共 Buff 与历史资源数据升级验收

当前 schema `7.0` 已下线公共资源采购。`resource_orders`、`RESOURCE_PURCHASE`
与 `RESOURCE_REFUND` 只为兼容旧数据库和历史账本保留，不再属于可执行功能。

## 上线前准备

1. 停服备份 TianjiTown SQLite、QuickShop H2、XConomy 数据及清算账户余额。
2. 在隔离预发服恢复生产副本，再替换当前版本 JAR。
3. 按实际经济规模调整 `config.yml` 的 `buffs.catalog`；修改目录后需要重启。
4. 启动后执行 `/townadmin status` 和 `/townadmin money reconcile`，确认迁移完成且清算账户无短款。

## Buff 验收

分别为 Potion Effect 和 Attribute Modifier 商品执行以下用例：

- 商品操作按钮不显示悬浮说明；Dialog 使用滑块选择 1～4 周和 I～V 级，并显示效果、精确价格和角色权限。
- 收费按周数和所选等级线性增长，每次只有一条扣款流水；再次购买同类 Buff 时覆盖旧生效项。
- 双击确认只产生一次购买；余额不足或账户锁定时不创建 `active_buffs`。
- 成员登录、重生、跨世界后效果正确恢复，公共 Buff 不区分世界。
- Attribute Modifier 使用稳定 key，重复刷新后只有一个 modifier。
- 在小镇服内，Buff 到期、玩家退镇、被踢或小镇归档后效果完整移除。
- 玩家和管理员均没有 Buff 退款入口；购买确认页明确提示不接受退款。

## HuskSync 跨服携带

采用“数据库到期时间不变、外服效果允许延后移除”的规则，无需额外子服或 Velocity 插件。

- 正常退出小镇服时不移除玩家的属性、药水效果或效果追踪 PDC，只取消本服到期任务并释放缓存。
- 玩家停留在小镇服时仍按原时间到期；离线或停留外服时不由本服执行该玩家的到期任务，返回后收尾到期记录。
- 检测到已启用的 HuskSync 3 时，等待 `BukkitSyncCompleteEvent` 后再按数据库校正；没有 HuskSync 时使用普通登录刷新。
- 回服会移除过期及已下架的 `tianjitown:buff_*` 属性修饰符，并将有效的同步属性重新创建为临时修饰符，不延长数据库有效期。
- 未安装 TianjiTown 的子服不会主动处理小镇 Buff 的到期或成员关系变化；接受玩家返回小镇服前的有限超期。
- 插件禁用时仍保留原有在线效果清理流程；本规则针对正常退出和跨服。

在参与同步的子服检查 HuskSync `config.yml`：`synchronization.features.attributes` 应为 `true`，
`synchronization.attributes.synced_attributes`应包含所需属性，
`synchronization.attributes.ignored_modifiers` 不应排除 `tianjitown:buff_*`。
请保留其他插件所需的现有配置。例如速度 Buff 需要明确包含 `minecraft:movement_speed`，
不能假设 HuskSync 默认同步所有属性。药水效果是否携带由 HuskSync 的药水同步规则决定；
当前小镇药水效果使用 ambient 标记，不能承诺与属性 Buff 相同的跨服行为。

跨服验收：

1. 携带未到期属性 Buff 切换到无 TianjiTown 的子服，确认属性效果存在，数据库到期时间未变。
2. 在该子服等待超过到期时间，再返回小镇服；确认 HuskSync 同步后旧修饰符移除，生命值不超过恢复后的上限。
3. 未到期时往返多次，确认数值正确、相同 key 只有一个修饰符、有效期没有重置。
4. 删除或修改 Buff 配置后回服，确认旧属性上的自有修饰符被清理，其他插件的修饰符不受影响。
5. 快速退出重连，确认上一会话的异步回调和到期任务不会影响新会话。
6. 未安装 HuskSync 时验证普通登录恢复、在线到期、退镇清理仍正常。

## 历史资源数据兼容

升级前后必须核对：

- `resource_orders` 的行数、业务键、状态、金额和时间字段保持不变；
- `RESOURCE_PURCHASE`、`RESOURCE_REFUND` 流水仍能在历史账本中正常显示；
- 当前配置不存在 `resources`，玩家界面、帮助、Tab 补全和命令均没有资源入口；
- 玩家登录、重启、定时任务和管理员操作不会领取、补发、退款或修改旧订单状态；
- 统一诊断只读统计未结束的历史订单，不尝试自动修复。

不要删除或改写已经发布的 Flyway 迁移。直接修改旧迁移会破坏现有数据库的
checksum 校验；直接删除历史表或流水类型会造成审计数据丢失。

## 功能开关与回滚

`buffs.shop-enabled` 可通过 `/townadmin reload` 热更新。关闭后只停止新购买，
已购买效果继续到期并正常清理。

回滚前停止新的经济和 Buff 写入，完成清算对账并备份。旧版本可能重新包含资源订单
入口，因此不得让旧 JAR 直接写入当前生产数据库；需要回退时应恢复同一时间点的整套数据。
