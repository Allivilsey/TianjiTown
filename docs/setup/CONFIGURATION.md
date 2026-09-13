# 配置说明

实际配置位于 `plugins/TianjiTown/config.yml`，消息位于同目录 `messages.yml`。默认模板见 [config.yml](../../tianjitown-paper/src/main/resources/config.yml)。配置不设版本号，不执行配置升级或旧配置兼容流程；启动保留必要的类型、范围和内容校验。

## 怎样使修改生效

本页“重启”对 TianjiTown 自身配置也可以通过 `/plugman restart TianjiTown` 或 `/plugman reload TianjiTown` 完成：它们会重建连接池、运行时和定时任务，重新执行启动诊断。依赖插件本身的变更仍按依赖要求重启服务器，详见 [PlugMan 热重载](../operations/OPERATIONS.md#plugman-热重载)。

`/tianjitown reload` 只重读文件，不重新创建运行时、连接池或定时任务。以下开关在运行时动态读取，重载后用于后续操作：

| 配置键 | 默认值 | 作用 |
|---|---|---|
| `town.maintenance-mode` | `false` | 暂停玩家入口和表单；管理员命令、税收、后台任务仍运行。命令 `maintenance on/off` 会直接写回该值 |
| `economy.tax.enabled` | `true` | 控制新的小镇收入税，不删除已入账记录 |
| `economy.consumption.enabled` | `true` | 控制捐款、扩张、Buff 等新资金操作；不停止税款入账、已提交补偿或查询 |
| `buffs.shop-enabled` | `true` | 控制新 Buff 购买；已有 Buff 继续生效到期 |
| `territory.building-refund.enabled` | `true` | 控制新的建筑返还 |
| `territory.beacon.enabled` | `true` | 控制领地信标效果；关闭后清理托管效果，数据库记录保留 |

手册冷却、申请预留时间、选址缓冲和粒子预览参数也会在后续相应操作中读取。其余业务参数建议统一重启生效；不要把重载成功提示当成所有设置已经重建。修改必需依赖或修复启动 `LOCKED` 后应重启。

## 数据库

| 配置键 | 默认值 | 含义 |
|---|---|---|
| `database.file` | `tianjitown.db` | 相对插件数据目录或绝对路径，修改后重启；不会自动搬运原文件 |
| `database.connection-timeout-ms` | `5000` | 等待数据库连接的毫秒数，重启生效 |
| `database.busy-timeout-ms` | `5000` | SQLite 繁忙时的等待毫秒数，重启生效 |

不在配置中填写 MySQL 连接串。已有数据库结构不匹配时按 [备份与恢复](../operations/SQLITE_AND_BACKUP.md) 排查，不删除数据或伪造 Flyway 校验记录。

## 申请、成员和选址

以下键均位于 `town` 下。

| 配置键后缀 | 默认值 | 含义 |
|---|---|---|
| `handbook-cooldown-minutes` | `60` | 玩家自行领取手册的冷却；管理员发放跳过冷却，也不更新自行领取的冷却时间 |
| `application.fee` | `'5000.00'` | 批准时向申请人收取，建镇成功后转为公共初始余额；必须为正且符合货币精度 |
| `application.cooldown-hours` | `24` | 建镇申请撤回或拒绝后的再申请冷却 |
| `application.reservation-minutes` | `60` | 选址临时预留时间 |
| `membership.maximum-pending-applications` | `3` | 同一玩家同时待处理入镇申请上限 |
| `membership.application-lifetime-hours` | `48` | 入镇申请有效期 |
| `membership.rejection-cooldown-hours` | `24` | 被拒绝后对同一小镇的申请冷却 |
| `membership.leave-cooldown-hours` | `24` | 主动离镇后对新入镇申请的冷却 |
| `site.minimum-buffer-chunks` | `1` | 选址安全缓冲，单位为区块，参与 WorldBorder 范围检查 |
| `site.preview-duration-seconds` | `15` | 粒子边界预览持续秒数 |
| `site.preview-interval-ticks` | `20` | 预览刷新间隔 |
| `site.preview-vertical-range-blocks` | `24` | 粒子预览的上下垂直范围，不改变真实领地高度 |

初始激活单元固定 5×5 区块，整镇地图固定 5×5 单元，即 25×25 区块（625 区块）。选址时检查整镇范围及缓冲，批准后初始单元自动激活，其余 24 个单元保持预留。治理窗口也不是可配置项：接任 24 小时、活跃窗口 30 天、投票 72 小时、最多 3 名副镇长。不要增加没有代码读取的旧治理配置键。

## 经济

| 配置键 | 默认值 | 含义 |
|---|---|---|
| `economy.settlement-account` | `tax` | Vault 离线玩家清算账户，按 UUID 和实际名称访问；变更需先核对资金并重启 |
| `economy.money-scale` | `2` | 经济提供者未声明小数位时的后备精度；有效精度按 provider 优先 |
| `economy.tax.subsidy.weekly-limit` | `'10000.00'` | 每镇三个收入来源共用的每周补贴上限 |
| `economy.tax.subsidy.twelve-hour-limit` | `'2000.00'` | 每镇共用的 12 小时补贴上限 |
| `economy.reconciliation-interval-minutes` | `5` | 清算对账周期，重启才重新调度 |
| `economy.expansion.base-cost` | `'5000.00'` | 首次扩张价格，此后每次增加 5%，批量逐块累计 |

补贴固定上海时间周一 04:00、每天 04:00/16:00 分窗。两种额度同时约束，剩余额度不足时部分补贴；税款仍正常入账。税率范围固定 5%～25%，按 1% 步进，不在配置中定义。

## Buff 商品

`buffs.catalog.<buffKey>` 定义商品，修改后重启。名称来自 `messages.yml` 的 `dialog.buff.labels.<buffKey>`，新增商品必须提供非空名称。

| 字段 | 含义 |
|---|---|
| `effect-kind` | 仅支持 `ATTRIBUTE` 属性 |
| `effect-key` | 服务端支持的 namespaced key，如 `minecraft:movement_speed` |
| `operation` | 属性运算，如 `ADD_SCALAR`、`ADD_NUMBER`；需为有效的属性运算 |
| `base-price` | I 级一周基础价，使用带引号的金额文本 |
| `maximum-level` | 商品等级上限，玩家选择最多显示到 V |
| `amount-per-level` | 每级增加的效果数值 |

玩家商店按自选周数和等级覆盖同类效果；管理员免费设置默认一周、一级，可选 1～4 周及合法等级，覆盖同类效果且余额不变。玩家购买角色固定为镇长和副镇长，当前没有每商品角色配置键。商品价目、默认等级见 [定价说明](../deployment/PRICING.md)。

## 领地加成和诊断

| 配置键 | 默认值 | 含义 |
|---|---|---|
| `territory.building-refund.chance` | `0.25` | 合格放置的返还概率 |
| `territory.building-refund.weekly-limit` | `3000` | 按小镇、玩家分别计算的周返还上限；数据库原子预留 |
| `territory.building-refund.counter-retention-weeks` | `12` | 旧周计数保留周数，不是公共账本保留时间 |
| `territory.building-refund.reset-zone` | `Asia/Shanghai` | 建筑返还周窗口时区，周一 00:00 切换 |
| `territory.building-refund.blacklist` | 见模板 | 禁止返还的材料；`REDSTONE_CATEGORY` 展开红石类别，容器/特殊方块等仍受代码校验 |
| `territory.beacon.refresh-interval-ticks` | `100` | 已记录信标效果的玩家刷新间隔，不是信标区块扫描间隔 |
| `operations.quickshop-diagnostic-days` | `7` | 启动及不带参数手动诊断的 QuickShop 回看天数，允许 1～180 |

上表数值在启动加载，修改后重启。信标只在关闭有效信标界面时记录新增或更强效果，拆掉信标不会撤销数据库记录。

## 消息自定义

修改 `messages.yml` 后执行 `reload`。保留占位符名称，例如 `{town}`、`{player}`、`{reason}`；它们由调用处填入，改名会造成信息缺失。已有自定义值不会被内置文案覆盖，缺少的消息使用内置默认值。配置开关和提示文字是两回事，改提示不能改变业务限制。
