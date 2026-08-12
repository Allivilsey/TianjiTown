# TianjiTown 小镇系统开发 TODO

> 用途：天际服自用的 Minecraft 小镇组织与服务区管理插件。  
> 部署环境：玩家主要活动的单个 Paper 服务器；网络前端仍可使用 Velocity，但 TianjiTown 不开发 Velocity 端或跨服功能。  
> 本文档只描述已确定范围内的开发计划，不包含战争、外交、排行榜、Web 后台等扩展系统。

## 0. 已确定的设计约束

- [x] 使用 Maven 作为构建系统。
- [x] 只开发并部署一个 Paper 版插件。
- [x] Residence 是领地保护的必需依赖。
- [x] Vault 及一个 Vault 兼容的经济插件是资金操作的必需依赖。
- [x] QuickShop-Hikari 是首版税收系统的必需依赖和唯一收入来源。
- [x] SQLite 是包括小镇基本资料在内的唯一业务数据源，不维护 YAML 数据镜像。
- [x] Residence 区域是数据库领地的游戏内投影，不得被玩家手工管理。
- [x] 不注册或开放任何玩家命令；玩家通过箱子 GUI、可点击聊天申请表、书本和服务台完成全部操作。
- [x] 命令仅向管理员开放，且每个玩家操作都应有对应的管理员代办/恢复命令。
- [x] 跨服功能暂时搁置，不设计 `server-id`、Velocity 消息或跨服缓存同步。
- [x] 小镇不设最大成员数限制。
- [x] 项目范围不包含 Redis、Web 管理端、外交、战争、排行榜和地图插件集成。
- [x] 当前服务器为新周目；TianjiTown 首次安装使用独立空业务 schema，全量重新建立小镇数据。

### 阶段版本概览

> 从第 1 阶段开始，每个阶段都必须是可独立部署到生产环境的完整版本。未实现的后续功能必须在 UI 和命令中完全隐藏，不展示占位按钮。

| 阶段 | 建议版本 | 可交付能力 | 生产状态 |
|---|---:|---|---|
| 第 1 阶段 | `1.0.0` | 申请、选址、审批、基本资料、初始领地和最小成员体系 | 首个可生产版 |
| 第 2 阶段 | `1.1.0` | 完整成员治理、角色与投票 | 可生产升级 |
| 第 3 阶段 | `1.2.0` | QuickShop 动态税、公共账本、捐款和付费扩张 | 可生产升级 |
| 第 4 阶段 | `1.3.0` | 公共 Buff 与资源采购 | 可生产升级 |
| 第 5 阶段 | `1.4.0` | 领地加成与完整运维 | 计划功能完整版 |

## 1. 部署基线与开发前确认

- [x] 经济提供者为 XConomy，QuickShop-Hikari 通过 Vault 使用默认货币。
- [x] XConomy 已使用 MySQL，金额支持小数，付款命令税率为 0。
- [x] QuickShop-Hikari 当前使用本地 H2，交易日志保存到数据库，且已启用 transaction metric。
- [x] QuickShop-Hikari 当前基础税率为 5%，税款账户为 `tax`，`apply-to` 为 `player`。
- [x] Residence 当前启用多世界、忽略 Y 轴选区，且仍允许玩家创建普通领地。
- [x] 现服存在 DailyTaxEconomy，上线前必须确认它与 TianjiTown 清算账户的关系。
- [x] 2025-11-18 的最近 QuickShop 诊断快照显示：Leaf/Paper 兼容服务端 1.21.8、Java 21、QuickShop-Hikari 6.2.0.10、Residence 6.0.1.1、Vault 1.7.3-b131、XConomy 2.26.3。
- [x] 使用 Minecraft `26.2`、Paper API `26.2.build.84-stable`、Java 25 作为开发基线，运行时不做固定版本匹配。
- [x] 仅使用 Paper API 编译，不依赖服务端内部 API。
- [x] 已移除运行时版本匹配；仅当必需依赖未启用或自检失败时保持 `LOCKED`。
- [x] TianjiTown 使用 SQLite。
- [x] 确定 TianjiTown 独立 SQLite 文件、目录权限、单连接池和备份策略。
  - 默认文件为 `plugins/TianjiTown/tianjitown.db`，连接池上限为 1。
  - 服务器进程只需插件目录读写权限；使用 SQLite 在线备份脚本，并在上线前完成隔离恢复演练。
- [x] 确定服务区的表达方式：使用配置驱动的区块坐标矩形。
- [x] 确定其他小镇与服务区边界的最小缓冲距离。
- [x] 确定小镇名称/简称规则、申请条件、申请冷却和选址保留时间。
- [x] 确定默认税率、最高税率和税率修改的生效时机。
- [x] 确定领地扩张的基础价格、增长倍率和上限。
- [x] 确定“活跃成员”的登录天数和最低入镇天数。
- [x] 确定角色权限和最大领地单元数；成员数不设上限。
- [x] 确定 Buff 清单、资源商店清单及定价。
- [x] 已在预发切换中将 QuickShop-Hikari 原有 5% `player` 固定税调整为 0%，避免与 TianjiTown 动态税叠加。
- [ ] 确认 DailyTaxEconomy `taxer-vault` 的确切语义，并确保 TianjiTown 清算账户不会被再次征税或清理。

## 2. Maven 工程与模块

- [x] 创建 Maven 聚合工程，根 `pom.xml` 使用 `pom` packaging。
- [x] 建立 `tianjitown-core` 模块：
  - 纯 Java 领域对象和规则。
  - 不依赖 Bukkit、Residence、Vault 或 QuickShop-Hikari。
- [x] 建立 `tianjitown-storage` 模块：
  - JDBC 数据访问。
  - 连接池。
  - Flyway 数据库迁移。
  - 事务、乐观锁和数据库迁移。
- [x] 建立 `tianjitown-paper` 模块：
  - Paper 启动入口。
  - 管理员命令。
  - 箱子 GUI、可点击聊天申请表、书本资料编辑、服务台与游戏事件。
- [x] 建立 `tianjitown-integrations` 模块：
  - Residence 适配器。
  - Vault 适配器。
  - QuickShop-Hikari 税收适配器。
- [x] 外部插件 API 在 Maven 中使用 `provided` scope，不打入最终 JAR。
- [x] 在 `plugin.yml` 中将运行时集成声明为软依赖，并由启动门禁强制检查 `Vault`、`Residence`、`XConomy` 和 `QuickShop-Hikari` 的可用性。
- [x] 启动时检查 Vault `Economy` 服务是否真正注册，仅安装 Vault 本身不算通过。
- [x] 生产构建产出一个可安装的 Paper JAR。

## 3. 数据存储与单服一致性

- [x] SQLite 是基本资料、申请、成员关系、角色、领地、资金、税收、Buff、订单和投票的唯一权威数据源。
- [x] 领地使用 `world-uuid + chunk-x + chunk-z` 标识，同时保存世界名作为人类可读信息。
- [x] 每个小镇保存自增版本号，高冲突写操作使用乐观锁。
- [x] 扩张、资金扣除、镇长转让和投票结算在 SQLite 事务中完成。
- [ ] 所有资金与审批操作使用唯一幂等键。
- [ ] 插件在单个 Paper 进程内使用内存缓存，业务写入成功后立即失效/刷新对应缓存。
- [x] 不开发数据库事件轮询、跨服 outbox、Redis 或服务器 ID 机制。
- [x] 数据库 I/O 不得在 Paper 主线程上执行。
- [ ] 数据库暂时不可用时：
  - 继续使用最后的本地快照和 Residence 执行已有领地保护。
  - 禁止提交/审批申请、扩张、更改税率和消费。
  - QuickShop-Hikari 交易保持可用，税收按最后缓存税率执行，后续依据 QuickShop 交易历史对账。

## 4. 数据库表

- [x] `towns`：小镇主体、状态、镇长、税率、规则版本和乐观锁版本。
- [x] `town_members`：成员 UUID、角色、入镇时间、最后活跃时间和规则确认版本。
- [x] `town_applications`：申请人、文本资料、状态、选址和提交时间。
- [x] `application_reviews`：管理员审批、补充意见和原因。
- [x] `site_reservations`：申请期的 3×3 区块临时保留。
- [x] `territory_units`：按 3×3 网格管理的领地单元。
- [x] `town_accounts`：公共资金当前余额快照。
- [x] `ledger_entries`：不可变资金流水。
- [x] `quickshop_tax_records`：QuickShop 成交、收款方、所属小镇、税率、税额和幂等标识。
- [x] `active_buffs`：已购买且未过期的公共 Buff。
- [x] `resource_orders`：资源采购订单和领取状态。
- [x] `governance_votes`：投票目标、门槛、选民快照和截止时间。
- [x] `governance_vote_ballots`：成员选票，对 `vote-id + voter-uuid` 建唯一约束。
- [x] `audit_logs`：审批、管理员代办、资金调整和领地修复记录。
- [x] `town_profile_sync` 仅为已发布 Flyway `1.0` 中的停用遗留表；为保持迁移校验和不改写旧迁移，运行代码不再读写该表。

### 4.1 小镇基本资料

- [x] `name`、`short-name`、`description` 和 `rules` 直接保存于 `towns` 表。
- [x] 镇长通过书本 UI 修改简介和规则，名称和简称的变更由管理员流程代办。
- [x] 所有资料写入使用事务、乐观锁、字段校验和审计，不提供文件导入/导出入口。
- [x] 维护模式只负责暂停玩家入口，不承担数据镜像导入职责。

## 5. 玩家 UI 系统

### 5.1 入口

- [x] 不提供 `/town`、`/t`等任何玩家命令。
- [x] 实现管理员可创建的“小镇服务台”：
  - 推荐使用讲台作为服务台方块。
  - 在讲台 TileState PDC 中记录服务台 ID。
  - 玩家右键后打开小镇主菜单，不执行玩家命令。
- [x] 实现“小镇手册”特殊书本：
  - 通过 PDC 识别，不依赖物品名称。
  - 右键书本直接打开主菜单。
  - 丢失后可在服务台重新领取。
- [x] 将服务台作为标准入口，手册作为便携入口。

### 5.2 箱子 GUI 规范

- [x] 主菜单根据玩家状态展示：
  - 未入镇：申请建立小镇、申请加入小镇、查看待处理申请。
  - 普通成员：小镇信息、领地、资金、Buff、资源采购、投票、退出。
  - 官员：增加成员申请审核和小镇管理界面。
  - 镇长：增加税率、规则、扩张、踢人和转让界面。
- [x] 每个敏感操作使用独立确认界面，明示对象、价格和后果。
- [x] GUI 按钮必须校验 PDC 和当前会话 ID，不依赖展示名称。
- [x] 阻止玩家移动、复制或带走 GUI 中的按钮物品。
- [x] 关闭 GUI、退出服务器或会话超时后使会话失效，重新打开时重读最新数据。

### 5.3 聊天申请表与书本资料编辑

- [x] 申请表在聊天栏显示全部项目，玩家点击项目后输入内容，并可在提交前预览当前填写结果。
- [x] 每个申请项目提供悬浮要求和建议；领地名称只允许 `1~12` 个英文字母，建议使用三个字母。
- [x] 聊天输入使用一次性回调与玩家会话绑定，不广播给其他玩家；退出服务器后会话失效。
- [x] 过滤 MiniMessage/格式代码、超长文本和不允许的字符，并在保存时再次完整校验。
- [x] 镇长修改简介和规则继续使用书本 UI，名称、简称和领地名称保持锁定。

## 6. 小镇申请、选址与审批

### 6.1 状态机

- [ ] 实现正常状态流：
  - `DRAFT` 草稿。
  - `SITE_SELECTED` 已选址。
  - `SUBMITTED` 已提交。
  - `UNDER_REVIEW` 审核中。
  - `NEED_CHANGES` 待补充。
  - `APPROVED_PROVISIONING` 已批准，自动创建中。
  - `ACTIVE` 小镇正常运行。
- [ ] 实现终止/异常状态：
  - `REJECTED` 已拒绝。
  - `CANCELLED` 申请人撤回。
  - `PROVISION_FAILED` 创建失败，等待管理员重试。
  - `ARCHIVED` 已解散/归档。
- [ ] 为每个状态变更定义允许执行者、前置条件和审计内容。

### 6.2 玩家申请 UI

- [x] 玩家在主菜单中创建草稿。
- [x] 校验申请人未加入小镇、没有其他未结束申请且满足配置条件。
- [x] 通过可点击聊天申请表填写名称、简称、专用领地名称、简介和规则。
- [x] 玩家站在候选中心区块时，在 GUI 中点击“选择当前区块”。
- [x] 在玩家眼前用粒子/临时边界预览 3×3 区块。
- [x] 选址通过后创建有效期预留，防止并发申请占用同一区域。
- [x] 提交前展示完整摘要，玩家二次确认后进入 `SUBMITTED`。
- [x] `NEED_CHANGES` 状态向玩家显示管理员意见并允许重新编辑。

### 6.3 选址校验

- [x] 不设世界白名单；已配置服务区的世界必须位于服务区内，其他已加载世界不额外限制。
- [x] 3×3 区块不得超过服务区或世界边界。
- [x] 不得与已有小镇领地、未过期选址预留重叠。
- [x] 满足小镇间的最小缓冲距离。
- [x] 不得覆盖出生点、活动区、管理区或其他黑名单区域。

### 6.4 批准建镇

- [x] 批准时再次校验申请人、名称和领地是否仍然有效。
- [x] 在同一数据库事务中创建小镇、镇长成员、公共账户和初始领地。
- [x] 事务成功后创建 Residence 区域投影。
- [x] Residence 创建失败时进入 `PROVISION_FAILED`，不将小镇对玩家标记为可用。
- [x] 管理员可幂等重试，不得重复创建账户、成员或区域。

## 7. Residence 领地集成

- [x] 定义 `LandProtectionService` 接口，业务层不直接调用 Residence API。
- [x] 初始 3×3 领地单元映射为一个系统 Residence，直接使用申请中的专用英文领地名称，不添加前缀、后缀、网格坐标或小镇 UUID。
- [x] Residence 范围严格对齐区块边界，竖直范围按当前世界最低/最高高度生成。
- [x] Residence 所有者使用受控系统账户，不直接设置为镇长。
- [x] 系统 Residence 通过 SQLite 登记的专用领地名称清单识别；同名外部领地只参与碰撞检查，不得删除或重建，也不修改 Residence 全局限额。
- [x] 利用现服 `Selection.IgnoreY: true` 的设定创建全高度区域，同时以 Paper 当前世界最小/最大高度做边界验证。
- [x] 成员、官员和镇长的 Residence 权限由插件统一同步。
- [x] 阻止玩家通过 Residence 命令改名、转让、删除或改变边界。
- [x] 配置周期性对账：
  - `ACTIVE` 数据库单元的 Residence 缺失时安全归档并保留复用锁，不自动重建。
  - Residence 越界或权限错误时报警，不自动删除或重建；管理员可显式确认修复。
  - 修复全部记入审计日志。

## 8. 领地扩张

- [x] 初始领地固定为一个 3×3 区块单元。
- [x] 以初始中心建立固定 3×3 网格。
- [x] 只能在现有单元的东、西、南、北添加相邻单元。
- [x] 新单元必须与旧领地连通，不允许飞地。
- [x] 扩张价格使用可配置指数公式：`ceil(baseCost * growthFactor^(unitCount - 1))`。
- [x] 金额计算使用 `BigDecimal`，并按 Vault 经济精度统一舍入。
- [x] 玩家在 GUI 中选择方向，查看边界预览、价格和扩张后总面积。
- [x] 扣款和占用数据在同一事务中完成，Residence 创建失败时执行可追踪补偿。

## 9. QuickShop-Hikari 小镇税收

### 9.1 税收语义

- [x] 只对 QuickShop-Hikari 成交产生的玩家收入征税。
- [x] 不监听普通 Vault 余额变化，不对任务、转账、管理员调整或其他插件收入征税。
- [x] 征税对象始终是此次 QuickShop 交易的“实际收款方”：
  - 出售商店向玩家卖出物品：商店所有者是收款方。
  - 收购商店从玩家买入物品：与商店交易的玩家是收款方。
- [x] 只有收款方属于活跃小镇时才征收该小镇的税率。
- [x] 税额从收款方本次收入中扣除，不额外向付款方加价。
- [x] 税率使用基点或定点小数存储，禁止使用二进制浮点数作为账本依据。

### 9.2 QuickShop 事件接入

- [x] 生产适配器使用 QuickShop `6.2.0.10`，其他版本保持动态税关闭。
- [x] TianjiTown 不要求将 QuickShop 从 H2 迁移到 MySQL；优先通过官方事件/API 对账。
- [ ] 在 QuickShop 计算税率时：
  - 识别交易方向和实际收款方。
  - 从本地成员/税率快照读取小镇税率，事件中不访问数据库。
  - 显式覆盖收款方对应的 tax rate，并将另一方的 TianjiTown 税设为零，不继承现服固定 5% `player` 语义。
- [x] 在 `EconomyTransactionEvent` 中将税款收取账户指向 TianjiTown 专用 Vault 清算账户。
- [x] 在 `ShopSuccessPurchaseEvent` 之后才创建小镇税收流水，交易失败或回滚时不入账。
- [x] 建立运行期税收幂等标识，并确认 QuickShop `6.2.0.10` 不提供可跨重启复用的稳定交易 ID；跨重启异常以三方对账处理。
- [ ] 以 QuickShop 交易历史作为异常对账依据，不直接修改 QuickShop 数据库。
- [x] 上线切换税务策略时单独记录 QuickShop 当前 `tax` 账户余额，不自动归入任何小镇。

### 9.3 清算账户与对账

- [x] 创建一个受管的 Vault 清算账户，保管所有小镇实际税款。
- [ ] 核对 XConomy `non-player-account` 与 DailyTaxEconomy 配置，再最终确定清算账户名；不仅依赖名称带 `tax` 的默认行为。
- [x] 数据库中按小镇分账，清算账户余额作为所有小镇账本的外部备付金。
- [x] 定时校验：`Vault 清算账户余额 >= 各小镇可用余额合计 + 待处理金额`。
- [x] 不一致时禁止新的小镇消费，记录高优先级告警并由管理员对账。
- [x] 管理员资金调整必须填写原因，同时留下 Vault 操作结果和内部账本记录。

## 10. 公共资金与流水

- [x] 账本至少支持：QuickShop 税收、成员捐款、领地扩张、Buff 购买、资源采购、退款和管理员调整。
- [x] 每条流水包含小镇、金额、变更后余额、类型、操作者、世界/来源、业务 ID、时间和备注。
- [x] 普通成员可在 GUI 中查看至少最近 180 天的完整流水。
- [x] 原始流水原则上永久保留，180 天只是玩家默认查询范围。
- [x] 所有扣款先在事务内检查可用余额，禁止产生负余额。
- [x] 成员捐款使用 GUI 选择预设金额或通过书本表单填写数值。

## 11. 公共 Buff

- [x] 配置可购买的 Potion Effect 和 Attribute Modifier。
- [x] 每个 Buff 定义基础价格、持续时间、最大等级、叠加规则和允许世界。
- [x] 价格根据当前叠加层数使用可配置倍率增长。
- [x] 成员在 GUI 中预览价格、强度、持续时间和当前效果。
- [x] 完成公共资金扣款后创建 `active_buffs`。
- [x] 成员登录、重生和世界变更时重新校验 Buff。
- [x] Attribute Modifier 使用稳定 namespaced key，避免重登后重复叠加。
- [x] Buff 过期、玩家退镇或进入不允许世界时移除效果。

## 12. 资源采购

- [x] 使用配置文件定义商品、单价、每次上限和每日上限。
- [x] 成员在箱子 GUI 中选择商品和数量。
- [x] 付款成功后创建资源订单，不直接将物品塞入可能已满的背包。
- [x] 订单进入个人待领取箱 GUI，领取时再检查背包空间。
- [x] 结算、生成订单和修改公共资金在同一数据库事务中完成。
- [x] 重连后可继续领取，不因断线丢失订单。

## 13. 成员和规则告知

- [x] 角色固定为 `MAYOR`、`OFFICER`、`MEMBER`。
- [x] 所有身份判定使用 UUID，玩家名只用于显示和搜索。
- [x] 入镇申请从小镇 GUI 发起，镇长在申请列表中批准或拒绝。
- [ ] 接受前必须展示：
  - 小镇名称、镇长和人数。
  - 当前税率。
  - 税收仅来自 QuickShop-Hikari 收入。
  - 领地规模。
  - 有效 Buff。
  - 小镇规则和退出规则。
- [x] 玩家二次确认后加入，记录确认的规则版本。
- [x] 规则变更后在成员下次打开主菜单或登录时提示重新确认；税率变更后在登录或资金界面显示版本告知。
- [x] 成员可在 GUI 中主动退出。
- [x] 镇长可在 GUI 中强制移除普通成员/官员。
- [x] 镇长不得通过普通移除流程被踢出。
- [x] 镇长转让需候选成员在 GUI 中接受。
- [x] 镇长是最后一名成员时，进入受控的解散/归档确认流程。

## 14. 成员投票

- [x] 默认将最近配置天数内登录且入镇达到最低天数的成员定义为活跃成员。
- [x] 创建投票时冻结选民快照，期间的加入/退出不改变本次门槛。
- [x] 被投票对象不计入有效选民。
- [x] 投票踢人需要赞成票严格超过有效选民的 50%。
- [x] 强制更换镇长需要赞成票达到有效选民的 2/3。
- [x] 每个 UUID 对每次投票只能投一票。
- [x] 默认投票持续时间可配置，建议 72 小时。
- [x] 发起、投票、结算和执行通过 GUI 完成，并全部记录审计日志。
- [x] 结算任务可幂等重试，不得重复踢人或转让。

## 15. 领地玩法加成

### 15.1 建筑方块返还

- [x] 配置可触发的方块白名单、概率和每日上限。
- [x] 只有小镇成员在自己小镇 Residence 内放置时触发。
- [x] 保留正常方块放置，概率命中时向原物品栈返还一个物品。
- [x] 处理多方块、水桶、容器、床、门和其他特殊方块，防止刷物品。
- [x] 不对自动机械放置、粘液块推动或非玩家放置触发。

### 15.2 信标增强

- [x] 只增强位于小镇 Residence 内的有效信标。
- [x] 配置允许的范围倍率、等级上限和世界白名单。
- [x] 不超过 Minecraft/Paper 当前版本可安全表达的效果强度。
- [x] 信标被移除、失效或领地变更时清理附加效果。

## 16. 管理员命令

> 所有命令默认只允许控制台或拥有明确管理权限的玩家执行。管理命令不代替玩家 UI，用于审批、代办和故障恢复。

### 16.1 服务台和 UI

- [x] `/townadmin station create`：将目标讲台绑定为服务台。
- [x] `/townadmin station remove`：移除目标服务台。
- [x] `/townadmin station list`：列出当前服务器的小镇服务台。
- [x] `/townadmin handbook <player>`：发放小镇手册。
- [ ] `/townadmin ui open <player>`：为指定玩家打开小镇主菜单。

### 16.2 申请与审批

- [x] `/townadmin application list`。
- [x] `/townadmin application approve <小镇全名> <原因>`。
- [x] `/townadmin application reject <小镇全名> <原因>`。
- [x] `/townadmin application change <小镇全名> <原因>`。

### 16.3 小镇、成员和领地

- [x] `/townadmin town view <小镇全名>`。
- [ ] `/townadmin town create <owner> <name>`：受控应急创建。
- [x] `/townadmin town delete <小镇全名> <原因>`，通过聊天按钮确认。
- [x] `/townadmin member add|remove <小镇全名> <玩家> <原因>`；普通玩家加入使用申请制。
- [x] `/townadmin member role <town> <player> <role>`。
- [x] `/townadmin mayor transfer <小镇全名> <玩家> <原因>`。
- [x] `/townadmin expand preview <town> <direction>`。
- [ ] `/townadmin territory expand <town> <direction> [--free]`。
- [x] `/townadmin land reconcile <小镇全名|all> [repair]`。
- [x] `/townadmin land rebuild <小镇全名|all>`，通过聊天按钮确认。

### 16.4 规则、资金、Buff 和投票

- [x] `/townadmin tax set <town> <rate> <reason>`。
- [ ] `/townadmin rules edit <town> <player>`：向指定玩家发放管理员编辑书。
- [x] `/townadmin money view <town>`。
- [x] `/townadmin ledger view <town>`。
- [x] `/townadmin money adjust <town> <amount> <reason>`。
- [x] `/townadmin money reconcile`。
- [x] `/townadmin buff grant <town> <buff> <reason>`：按配置价格、期限和升级规则代购。
- [x] `/townadmin buff refund <buffId> <reason>`：取消并退款指定公共 Buff。
- [x] `/townadmin order list [limit]`：列出未完成资源订单。
- [ ] `/townadmin order deliver <orderId>`。
- [x] `/townadmin order refund <orderId> <reason>`。
- [x] `/townadmin vote create-kick <town> <target>`。
- [x] `/townadmin vote create-mayor <town> <candidate>`。
- [x] `/townadmin vote settle <voteId>`。
- [x] `/townadmin vote cancel <voteId> <reason>`。

### 16.5 运维

- [x] `/townadmin reload`：只重载明确支持热更新的配置。
- [x] `/townadmin status`：显示数据库、Vault、Residence、QuickShop 和后台任务状态。
- [ ] `/townadmin audit <town|player> [page]`。
- [ ] `/townadmin cache refresh [town]`。
- [ ] `/townadmin quickshop reconcile [from] [to]`。

### 16.6 权限

- [x] `tianjitown.admin`：全部管理权限。
- [ ] `tianjitown.admin.terminal`：服务台和手册。
- [ ] `tianjitown.admin.application`：申请代办。
- [ ] `tianjitown.admin.review`：审批。
- [ ] `tianjitown.admin.member`：成员与镇长管理。
- [ ] `tianjitown.admin.territory`：领地与 Residence 修复。
- [x] `tianjitown.admin.money`：资金查询、调整与清算对账。
- [ ] `tianjitown.admin.money.adjust`：资金调整。
- [ ] `tianjitown.admin.tax`：税率与 QuickShop 对账。
- [x] `tianjitown.admin.buff`：Buff 代办。
- [ ] `tianjitown.admin.vote`：投票代办。
- [ ] `tianjitown.admin.audit`：审计日志。

## 17. 新周目初始化边界

- [x] TianjiTown 使用独立数据库 `tianjitown`，首次生产安装从空业务 schema 开始。
- [x] 首次上线前确认 `towns`、`town_members`、`territory_units` 等业务表为空，仅允许 Flyway schema history 和阶段门禁记录存在。
- [x] 小镇、成员、角色、名称和领地归属仅以 TianjiTown SQLite 为权威数据源。
- [x] TianjiTown 只创建和管理 SQLite 已登记的专用英文名称；未登记 Residence 一律视为外部领地，只参与碰撞避让。
- [x] 业务数据只能由 TianjiTown 的申请、审批和管理流程创建，不提供外部数据导入入口。
- [x] 上线前只备份当前依赖状态：Residence、QuickShop H2、XConomy/清算账户和 TianjiTown SQLite，供故障回滚使用。

## 18. 配置文件

- [x] `config.yml`：SQLite 文件、超时和运维选项；不包含 `server-id`。
- [x] `config.yml` 的 `phase1.application`：申请条件、冷却、预留时间和名称规则。
- [x] `config.yml` 的 `phase1.site`：世界、服务区、黑名单区和缓冲距离。
- [x] `config.yml` 的 `phase3.expansion`：扩张价格和单元上限；Residence 投影规则由代码统一维护。
- [x] `config.yml` 的 `phase3`：税率上限、清算账户、金额精度和对账策略。
- [x] `config.yml` 的 `phase4.buffs`：Buff 类型、价格、层数、强度和持续时间。
- [x] `config.yml` 的 `phase4.resources`：资源商品、数量限制和价格。
- [x] `config.yml` 的 `phase2`：活跃成员定义、投票时间和门槛。
- [x] `config.yml` 的 `phase5`：建筑返还、信标增强、统一诊断和定时备份。
- [ ] `gui/*.yml`：菜单布局、图标、文案 key 和槽位。
- [ ] `messages_zh_CN.yml`：简体中文文案。
- [x] 为配置增加 schema version，安全升级上一阶段配置并拒绝不可识别的新旧配置。

## 19. 安全、审计与故障处理

- [x] 不接受客户端或未验证插件消息直接改变小镇数据。
- [ ] 所有管理员代办操作记录执行者、参数、原因、结果和执行世界。
- [ ] 删除 Residence、归档小镇、调整资金和强制转让需要二次确认。
- [ ] 后台任务在单 Paper 进程内使用单实例锁，并以 SQLite 中的业务状态保证重启后幂等结算。
- [ ] 定时处理过期选址、过期 Buff、投票结算、未完成订单和资金补偿。
- [x] 插件启动时检查：
  - 数据库 schema 版本。
  - SQLite 文件、Flyway schema history 和必需表约束是否正常。
  - Residence、Vault Economy 和 QuickShop-Hikari 是否可用。
  - QuickShop-Hikari API 是否与编译时选用版本兼容。
- [x] 必需依赖或数据库不可用时，以明确错误停止启用写功能，不静默降级为不保护领地。

## 20. 分阶段交付

### 逐阶段上线原则

- [ ] 每个生产阶段使用独立版本号、Flyway 迁移和发布记录。
- [ ] 数据库迁移优先使用向前兼容的增量变更；不在普通发布中删表、改已有字段语义或破坏旧版本读取。
- [ ] 后续模块使用功能开关，但关闭的功能不在玩家 UI 中出现。
- [ ] 每个阶段都提供：发布前检查、备份、安装/升级步骤和回滚步骤。
- [ ] 回滚默认只回退 JAR/配置并关闭新功能，不自动删除 SQLite 数据或 Residence 领地。

### 第 1 阶段：申请、批准与可运行小镇（`1.0.0`）

#### 阶段边界

本阶段是第一个生产版。它不实现税收、公共账本、付费扩张、投票、Buff、资源采购和领地加成；这些入口必须完全隐藏。

#### 1. 基础运行能力

- [x] 完成 `tianjitown-core`、`tianjitown-storage`、`tianjitown-paper` 和 Residence/Vault 集成的最小可生产实现。
- [x] 实现首批 SQLite 表：`towns`、`town_members`、`town_applications`、`application_reviews`、`site_reservations`、`territory_units` 和 `audit_logs`；`town_profile_sync` 保留为停用遗留表。
- [x] 实现 SQLite 异步访问、事务、幂等键、乐观锁、连接失效降级和主线程保护。
- [x] 基本资料只保存于 SQLite，修改使用校验、乐观锁和审计。
- [x] 提供 `/townadmin status`、`reload`、`maintenance`、`audit` 和必需权限。
- [x] 启动失败和部分依赖不可用时给出可操作的中文错误，不静默创建半成品小镇。

#### 2. 玩家申请与基本资料

- [x] 实现讲台小镇服务台、小镇手册和第 1 版主 GUI，不开放玩家命令。
- [x] 实现可点击聊天申请表：名称、简称、专用领地名称、简介、规则、悬浮提示和保存/取消操作。
- [ ] 补充管理员领取审核时写入 `UNDER_REVIEW` 的操作入口；其余 `DRAFT → SITE_SELECTED → SUBMITTED → NEED_CHANGES/APPROVED_PROVISIONING → ACTIVE` 状态流已实现。
- [x] 实现申请人资格、名称唯一性、文本安全、未完成申请和冷却校验。
- [x] 实现申请摘要、管理员意见、补充后重新提交和申请人撤回。
- [x] 批准后生成 SQLite 小镇记录，并向玩家提供只读小镇详情 GUI。
- [x] 镇长可通过书本 UI 修改简介和规则；名称/简称修改需要管理员审核或代办。

#### 3. 选址与初始领地

- [x] 玩家通过 GUI 记录当前区块为中心，预览 3×3 区块并生成有效期选址预留。
- [x] 不设世界白名单；校验已配置服务区、世界边界、黑名单区、已有 Residence、已有 TianjiTown 领地、其他申请预留和小镇缓冲距离。
- [x] 批准时在 SQLite 事务内占用初始领地，再以申请中的专用英文名称直接生成 Residence 投影。
- [x] 实现 `PROVISION_FAILED`、管理员幂等重试、Residence 对账/重建和完整审计。
- [x] 第 1 阶段领地固定为初始 3×3，不提供扩张 UI。

#### 4. 最小成员体系

- [x] 申请人在批准后自动成为 `MAYOR`。
- [x] 支持玩家通过 GUI 查看完整小镇资料/规则并提交入镇申请；镇长批准后加入为 `MEMBER`，拒绝后触发冷却。
- [x] 支持普通成员主动退出；第 1 阶段不提供官员、投票踢人或强制换镇长。
- [x] 提供管理员应急移除成员、转移镇长和归档小镇命令，全部需要原因和审计。
- [x] 成员数不设上限，成员列表从第 1 阶段开始使用分页查询。

#### 5. 新周目数据边界

- [x] 申请、成员、角色和名称唯一性只查询 TianjiTown SQLite。
- [x] 所有名称均按本系统规则申请并建立唯一约束。
- [x] 任何新小镇选址必须避开当前世界内的所有外部 Residence，以免覆盖非 TianjiTown 领地。
- [x] 成员关系、角色和权限均由 TianjiTown 领域模型管理。
- [x] 启动与运行过程中只加载 TianjiTown 自有数据库和配置。

#### 6. 第 1 阶段管理命令

- [x] 完成服务台/手册命令、申请列表/详情/补件/批准/拒绝/重试命令。
- [x] 完成小镇详情、成员申请/添加/移除、镇长紧急转移、镇长解散和小镇归档命令。
- [x] 完成领地预览、Residence 对账/重建和维护模式启停命令。
- [x] 未实现的 money/tax/buff/order/vote/expand 管理命令不在第 1 阶段注册。

### 第 2 阶段：完整成员治理（`1.1.0`）

#### 功能 TODO

- [x] 启用 `OFFICER` 角色，完成角色权限、入镇申请审核权和 Residence 权限同步。
- [x] 实现镇长踢出成员、镇长转让双方确认和无主小镇保护。
- [x] 实现活跃成员快照、投票踢人、2/3 强制更换镇长、投票过期和幂等结算。
- [x] 实现小镇解散/归档流程，保留审计与可恢复数据。
- [x] 实现规则版本变更告知和成员重新确认。

### 第 3 阶段：QuickShop 税收、公共账本与领地扩张（`1.2.0`）

#### 功能 TODO

- [x] 启用 QuickShop 实际收款方动态小镇税，不对普通 Vault 变动或其他收入征税。
- [x] 实现 Vault/XConomy 清算账户、小镇分账、`town_accounts`、`ledger_entries`、`quickshop_tax_records` 和差异锁定。
- [x] 实现镇长税率设置、成员税率变更告知、税收来源说明和最近 180 天流水 GUI。
- [x] 实现成员捐款、Vault 失败补偿、管理员资金调整和清算账户对账。
- [x] 启用 3×3 网格扩张、方向预览、指数价格、公共资金扣款和 Residence 补偿/重建。
- [x] 注册 money/tax/ledger/expand 管理命令和权限。

### 第 4 阶段：公共 Buff 与资源采购（`1.3.0`）

#### 功能 TODO

- [x] 实现配置驱动的 Potion Effect/Attribute Modifier Buff 商店、叠加倍率、期限和等级上限。
- [x] 实现 `active_buffs`、购买扣款、登录/重生/世界变更刷新和过期清理。
- [x] 实现资源商店、`resource_orders`、公共资金结算、个人待领取箱和断线恢复。
- [x] 实现 Buff/资源商店 GUI、二次确认、购买权限和 buff/order 管理命令。
- [x] 实现购买失败补偿、未领取订单检查和公共资金对账。

### 第 5 阶段：领地加成与计划功能完整版（`1.4.0`）

#### 功能 TODO

- [x] 实现建筑方块白名单、概率返还、每日上限和特殊方块/自动化防刷。
- [x] 实现小镇 Residence 内的信标范围/等级增强与移除清理。
- [x] 完成 SQLite、Residence、Vault 清算账户和 QuickShop 交易历史的统一自检/对账报告。
- [x] 完成配置 schema 升级、数据库增量升级、定时备份、一键诊断信息和告警文档。
- [x] 完成管理员运维手册、玩家使用说明、上线/回滚手册和依赖升级检查清单。

## 21. 参考接口文档

- [QuickShop-Hikari 自定义事件](https://quickshop-community.github.io/QuickShop-Hikari-Documents/zh-HK/docs/development/events)
- [QuickShop-Hikari 交易系统](https://quickshop-community.github.io/QuickShop-Hikari-Documents/de-DE/docs/modules/transaction-system)
- [QuickShop-Hikari 交易历史](https://quickshop-community.github.io/QuickShop-Hikari-Documents/docs/modules/shops/shop-history)
- [Residence 项目与 API](https://github.com/Zrips/Residence)
- [VaultAPI](https://github.com/MilkBowl/VaultAPI)
- [Paper 插件开发文档](https://docs.papermc.io/paper/dev/)
