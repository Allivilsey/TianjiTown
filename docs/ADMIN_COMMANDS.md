# 管理员命令手册

适用于当前 `1.0.0-SNAPSHOT`。下文按当前工作区的命令注册、参数解析和业务实现核对。玩家日常操作见 [玩家指南](PLAYER_GUIDE.md)，配置生效方式见 [配置说明](setup/CONFIGURATION.md)。

## 参数、权限与执行环境

- 所有命令以 `/tianjitown` 开头；控制台输入时省略 `/`。
- `<参数>` 必填，`[参数]` 可选，`a|b` 表示二选一。这些符号和 Tab 中的 `[原因]` 提示不能原样输入。
- `<小镇代码>` 使用建镇时填写的 3～9 个英文字母代码，不区分大小写；可用 `/tianjitown town list [active|provisioning|archived]` 查询。
- 成员、镇长、投票命令的 `<玩家>` 接受玩家名或 UUID；`handbook`、`open` 只接受在线玩家。离线身份优先使用已核对的 UUID，避免名称解析到错误身份。
- `[原因]` 可省略，省略或纯空白统一使用“管理员调整”；填写时保留完整多词原因，用于通知或审计。`member role` 的最后一项是角色，不另收原因。
- 除标为“仅游戏内”的命令外，均可由控制台执行。需要业务运行时的命令在初始化未完成或启动 `LOCKED` 时不可用；`status` 可用于查明原因。

所有管理命令、帮助、补全及管理界面统一使用 `tianjitown.admin`，默认授予 OP。旧子节点不能单独授权。下表“全部”均指此权限；普通玩家的手册和邀请回复继续按玩家身份与会话校验。

## 帮助与系统维护

| 命令 | 具体用途与参数 | 权限 |
|---|---|---|
| `/tianjitown` | 打开按权限过滤的帮助分类；等同不带分类的 `help` | 全部 |
| `/tianjitown help [分类]` | 查看分类语法。分类为 `system`、`station`、`application`、`town`、`member`、`vote`、`land`、`money`、`tax`、`ledger`、`buff`；镇长转让归入 `member` | 全部 |
| `/tianjitown status` | 查看插件版本、启动检查、SQLite 写状态、功能开关、最近诊断和玩家入口状态；排障时先执行此命令 | 全部 |
| `/tianjitown reload` | 重读 `config.yml` 和 `messages.yml`。仅运行时动态读取的设置立即生效，不能重新初始化数据库或解除启动失败 | 全部 |
| `/tianjitown maintenance` | 查看维护状态，等同下方 `status` 子命令 | 全部 |
| `/tianjitown open <在线玩家>` | 为指定玩家打开小镇主界面，需要 `tianjitown.admin` 权限；必须指定目标，沿用玩家界面的维护模式限制 | 全部 |
| `/tianjitown maintenance status` | 查看玩家入口是否处于维护模式 | 全部 |
| `/tianjitown maintenance on` | 暂停服务台、手册、玩家菜单和表单；写回配置并跨重启保留 | 全部 |
| `/tianjitown maintenance off` | 关闭维护模式，恢复玩家入口；写回配置 | 全部 |
| `/tianjitown audit [条数]` | 查看最近管理审计，包含操作者、动作、对象、时间和原因；默认 20 条，允许 1～200 条 | 全部 |
| `/tianjitown diagnose [天数]` | 提交统一诊断，检查 SQLite、Residence、Vault 清算余额和指定天数内的 QuickShop 历史；允许 1～180，省略时使用启动加载的 `operations.quickshop-diagnostic-days`，默认 7 | 全部 |

维护模式只暂停玩家入口；税收、后台任务、管理员命令和已有 Residence 保护仍运行。停经济写入还需使用 [运维手册](operations/OPERATIONS.md) 的功能开关；一致性备份需要停服。

诊断本身只读，不修复领地或补账。报告写入 `plugins/TianjiTown/diagnostics`，保留最近 30 份。QuickShop 核对只覆盖本地已有税记录；查询异常或扫描达到 1000 条时为 `INCOMPLETE`。它不能证明所有外部交易均未漏记，也不能证明历史税款最终收款账户。

## 服务台与手册

| 命令 | 具体用途与执行限制 | 权限 |
|---|---|---|
| `/tianjitown station` | 显示服务台帮助 | 全部 |
| `/tianjitown station create` | 将视线内 6 格范围的讲台登记为服务台；仅游戏内 | 全部 |
| `/tianjitown station remove` | 移除所看讲台的服务台登记；仅游戏内 | 全部 |
| `/tianjitown station info` | 查询所看讲台的服务台信息；仅游戏内 | 全部 |
| `/tianjitown station list` | 列出已登记服务台；游戏内可点击记录旁的传送按钮 | 全部 |
| `/tianjitown handbook [在线玩家]` | 发放小镇手册；游戏内省略目标时发给自己，控制台必须指定目标。管理员发放跳过冷却，也不刷新玩家自行领取的冷却时间 | 全部 |

服务台登记保存在 SQLite，讲台保存对应标识。管理员还可潜行左键拆除服务台。管理员或镇长将真正的小镇手册放上空讲台也可建立服务台；镇长建立的服务台绑定本镇，每镇限一个，手册保留在讲台上。

## 建镇审核与小镇管理

| 命令 | 具体用途与执行后果 | 权限 |
|---|---|---|
| `/tianjitown application list` | 显示建镇审核队列，最多 100 项；不是成员入镇申请列表 | 全部 |
| `/tianjitown town list [active|provisioning|archived]` | 列出小镇全名、代码和状态，默认仅活动镇；可显式选择 active、provisioning、archived，忽略大小写 | 全部 |
| `/tianjitown town view <小镇代码>` | 查看状态、镇长 UUID、记录版本、领地中心、Residence 名称及投影状态 | 全部 |
| `/tianjitown town delete <小镇代码> [原因]` | 发起管理员删除确认。确认后先归档，Residence 确认移除后才释放名称、代码和区块；保留历史和审计 | 全部 |

建镇批准、拒绝、退回修改和失败重试统一通过 GUI 审核。批准时默认收取 `5000.00`，建镇成功后作为该镇初始公共余额。

## 成员、角色和镇长

这些是管理员直接代办入口，不要求目标通过普通入镇申请或接任确认；仍受单一镇籍、角色数量和小镇状态等业务校验，并同步 Residence 权限。

| 命令 | 具体用途与执行后果 | 权限 |
|---|---|---|
| `/tianjitown member add <小镇代码> <玩家> [原因]` | 直接将玩家加入目标镇为普通成员；不发送普通入镇申请 | 全部 |
| `/tianjitown member remove <小镇代码> <玩家> [原因]` | 直接移除成员并刷新在线玩家公共 Buff；不能用此入口移除镇长 | 全部 |
| `/tianjitown member role <小镇代码> <玩家> <DEPUTY_MAYOR\|MEMBER>` | 任命副镇长或降为普通成员；每镇最多 3 名副镇长。审计使用系统固定原因，不接受额外原因参数 | 全部 |
| `/tianjitown mayor transfer <小镇代码> <玩家> [原因]` | 紧急把镇长转给本镇成员，原镇长变为普通成员；无需候选人接受，与玩家双方确认流程不同 | 全部 |

例如 `/tianjitown member role sky Steve DEPUTY_MAYOR` 为任命副镇长。没有 `member invite` 管理命令，也没有 `OFFICER` 角色。

## 治理投票

| 命令 | 具体用途与执行后果 | 权限 |
|---|---|---|
| `/tianjitown vote cancel <小镇代码> [原因]` | 按小镇代码取消该镇当前唯一的开放投票并记录原因，不执行提案的人事变更 | 全部 |

同镇只能有一个开放投票；创建时冻结最近 30 天活跃选民，持续 72 小时。踢人须严格超过选民数的 50%，更换镇长须达到 2/3（向上取整）。踢人排除目标，换镇长排除现任镇长。到期后台每分钟尝试结算，全员投完可提前结算。

发起投票通过玩家 Dialog 完成，管理员身份不绕过镇籍、角色或选民限制；现任镇长可发起换届但不参与该次投票。发起人也可在详情页二次确认终止自己的开放投票，无需管理员权限；已结束投票不能取消。

## 领地检查与修复

| 命令 | 具体用途与执行后果 | 权限 |
|---|---|---|
| `/tianjitown land preview <小镇代码>` | 传送到目标镇传送点并显示已生效领地粒子边界；仅游戏内 | 全部 |
| `/tianjitown land reconcile <小镇代码\|all> [repair]` | 默认仅比较 SQLite 与 Residence 的边界、区域和访问权限；加 `repair` 才立即按数据库修复。`all` 选择未归档小镇 | 全部 |
| `/tianjitown land rebuild <小镇代码\|all>` | 发起重建确认；确认后先移除 Residence，再按数据库重建。会短暂影响保护，不是扩张操作 | 全部 |

例如 `/tianjitown land reconcile sky` 只检查；`/tianjitown land reconcile sky repair` 会修改 Residence。后台也会定时自动修复差异，因此只读检查不代表系统暂停了自动修复。

当前没有管理员命令直接购买扩张地块。镇长从玩家界面的公共资产进入扩张地图，选择相邻格子并确认付款。

## 公共资金、税率与账本

| 命令 | 具体用途与执行后果 | 权限 |
|---|---|---|
| `/tianjitown money view <小镇代码>` | 查看公共余额、账户锁定状态和锁定原因 | 全部 |
| `/tianjitown money adjust <小镇代码> <带符号金额> [原因]` | 调整该镇公共余额，同时调整 Vault 清算账户并写入账本与审计；正数增加、负数减少，不能为零或导致余额不足 | 全部 |
| `/tianjitown tax set <小镇代码> <百分比> [原因]` | 强制设置该镇 QuickShop、Jobs、GlobalMarketPlus 统一收入税；允许 5～25 的整数百分比，如 `10` 或 `10%` | 全部 |
| `/tianjitown ledger view <小镇代码>` | 在聊天中显示最新 45 条展示项目；税收按北京时间每日 04:00 分日汇总。命令无分页参数，成员 Dialog 可翻页查看全部历史 | 全部 |

例如 `/tianjitown money adjust sky -100 活动费用更正` 会减少该镇公共余额和清算余额。它不是仅修改内部数字的“修账”指令；外部清算短款应先核对原因，不能用一次正调账假定历史差异已经消失。

Jobs 汇总为“职业收入”，QuickShop 与 GlobalMarketPlus 合并为“商店收入”，均显示税款及实际服务器补贴。汇总不删除底层逐笔账本。

## 公共 Buff

| 命令 | 具体用途与执行后果 | 权限 |
|---|---|---|
| `/tianjitown buff list <小镇代码>` | 查看当前有效 Buff 的 UUID、商品键、等级、层数和到期时间；不是商店商品目录 | 全部 |
| `/tianjitown buff set <小镇代码> <buffKey> [周数] [等级]` | 免费设置并覆盖同类效果；默认 1 周、1 级，周数 1～4，等级不超过 min(5, 商品上限)，余额不变 | 全部 |

默认商品键：`speed`、`health`、`diving`、`safe_fall`、`mining`。完整价格见 [定价说明](deployment/PRICING.md)。

`set` 是免费设置，直接生效；商店或公共消费开关关闭不影响设置。玩家重购按旧订单剩余时间折算退款，免费旧记录退款为 0；购买后无主动退货入口。

## 确认按钮与内部命令

`town delete`、`land rebuild` 会先发确认按钮，只有发起者能使用，60 秒后失效；取消后不会执行。删除和重建会再次检查目标记录版本，变化后须重新发起。其他管理写命令通常直接提交，不要假定都会二次确认。

按钮实际使用 `/tianjitown confirm <确认码>` 与 `/tianjitown cancel <确认码>`；确认码由系统生成，不应猜测或复用。控制台无法点击时需使用实际按钮事件中的命令；没有可用确认码时由游戏内管理员重新发起操作。`/tianjitown-callback` 是聊天按钮内部回调入口，不是玩家日常命令。隔离验收流程见 [测试文档](../test/README.md)。

管理员免费设置不受 Buff 商店或公共消费开关限制。玩家重购的退款按旧订单实际付款和剩余有效时间折算；不提供主动退货命令。统一诊断成功时，启动检查静默，手动命令只回复一行通过提示，完整报告仍写入 diagnostics。
