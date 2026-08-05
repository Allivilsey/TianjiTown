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
- [x] MySQL 保存需要事务与索引的运行数据；YAML 保存可人工维护的小镇基本资料镜像。
- [x] Residence 区域是数据库领地的游戏内投影，不得被玩家手工管理。
- [x] 不注册或开放任何玩家命令；玩家通过箱子 GUI、书本和服务台完成全部操作。
- [x] 命令仅向管理员开放，且每个玩家操作都应有对应的管理员代办/恢复命令。
- [x] 跨服功能暂时搁置，不设计 `server-id`、Velocity 消息或跨服缓存同步。
- [x] 小镇不设最大成员数限制。
- [x] 项目范围不包含 Redis、Web 管理端、外交、战争、排行榜和地图插件集成。
- [x] 当前服务器为新周目；TianjiTown 首次安装使用独立空业务 schema，全量重新建立小镇数据。

### 阶段版本概览

> 第 0 阶段是开发/上线前置门禁，不单独部署。从第 1 阶段开始，每个阶段都必须是可独立部署到生产环境的完整版本。未实现的后续功能必须在 UI 和命令中完全隐藏，不展示占位按钮。

| 阶段 | 建议版本 | 可交付能力 | 生产状态 |
|---|---:|---|---|
| 第 0 阶段 | - | 版本锁定、依赖验证、空数据初始化与依赖备份 | 不单独上线 |
| 第 1 阶段 | `1.0.0` | 申请、选址、审批、基本资料、初始领地和最小成员体系 | 首个可生产版 |
| 第 2 阶段 | `1.1.0` | 完整成员治理、角色与投票 | 可生产升级 |
| 第 3 阶段 | `1.2.0` | QuickShop 动态税、公共账本、捐款和付费扩张 | 可生产升级 |
| 第 4 阶段 | `1.3.0` | 公共 Buff 与资源采购 | 可生产升级 |
| 第 5 阶段 | `1.4.0` | 领地加成、完整运维与最终验收 | 计划功能完整版 |

## 1. 部署基线与开发前确认

- [x] 经济提供者为 XConomy，QuickShop-Hikari 通过 Vault 使用默认货币。
- [x] XConomy 已使用 MySQL，金额支持小数，付款命令税率为 0。
- [x] QuickShop-Hikari 当前使用本地 H2，交易日志保存到数据库，且已启用 transaction metric。
- [x] QuickShop-Hikari 当前基础税率为 5%，税款账户为 `tax`，`apply-to` 为 `player`。
- [x] Residence 当前启用多世界、忽略 Y 轴选区，且仍允许玩家创建普通领地。
- [x] 现服存在 DailyTaxEconomy，上线前必须确认它与 TianjiTown 清算账户的关系。
- [x] 2025-11-18 的最近 QuickShop 诊断快照显示：Leaf/Paper 兼容服务端 1.21.8、Java 21、QuickShop-Hikari 6.2.0.10、Residence 6.0.1.1、Vault 1.7.3-b131、XConomy 2.26.3。
- [x] 采用当前生产开发锁：Minecraft `26.2`、Paper API `26.2.build.84-stable`、Java 25；实际运行版本由插件上线门禁复核。
- [x] 仅使用 Paper API 编译，在生产同版本 Paper 上做兼容测试，不依赖服务端内部 API。
  - [x] 已锁定 Paper API `26.2.build.84-stable` 并完成编译配置，代码不引用服务端内部 API。
  - [x] 已在当前生产同版本 Paper `26.2-84` 测试服完成联合验证。
- [x] 采用当前插件锁：Residence `6.0.2.4`、Vault `1.7.3-b131`、XConomy `2.26.3`、QuickShop-Hikari `6.2.0.10`。
- [x] 已建立运行版本锁定清单；实际 JAR 不一致时插件保持 `LOCKED`，升级前必须在测试服验证。
- [x] TianjiTown 使用 MySQL。
- [x] 确定 TianjiTown 独立数据库名、账户权限、连接池上限和备份策略。
  - 数据库 `tianjitown`，应用账户 `tianjitown_app`，连接池上限 6。
  - 应用账户使用 schema 级最小权限；每日备份保留 14 份、周备份保留 8 份，上线前额外备份。
- [ ] 确定服务区的表达方式：坐标矩形、多边形、区块列表或现有 Residence 管理区。
- [ ] 确定其他小镇与服务区边界的最小缓冲距离。
- [ ] 确定小镇名称/简称规则、申请条件、申请冷却和选址保留时间。
- [ ] 确定默认税率、最高税率和税率修改的生效时机。
- [ ] 确定领地扩张的基础价格、增长倍率和上限。
- [ ] 确定“活跃成员”的登录天数和最低入镇天数。
- [ ] 确定角色权限和最大领地单元数；成员数不设上限。
- [ ] 确定 Buff 清单、资源商店清单及定价。
- [ ] 上线时将 QuickShop-Hikari 现有 5% `player` 税迁移为 TianjiTown 动态税，不叠加两套 QuickShop 税。
- [ ] 确认 DailyTaxEconomy `taxer-vault` 的确切语义，并确保 TianjiTown 清算账户不会被再次征税或清理。

## 2. Maven 工程与模块

- [x] 创建 Maven 聚合工程，根 `pom.xml` 使用 `pom` packaging。
- [ ] 建立 `tianjitown-core` 模块：
  - 纯 Java 领域对象和规则。
  - 不依赖 Bukkit、Residence、Vault 或 QuickShop-Hikari。
- [ ] 建立 `tianjitown-storage` 模块：
  - JDBC 数据访问。
  - 连接池。
  - Flyway 数据库迁移。
  - 事务、乐观锁、YAML 基本资料镜像和原子写入。
- [ ] 建立 `tianjitown-paper` 模块：
  - Paper 启动入口。
  - 管理员命令。
  - 箱子 GUI、书本表单、服务台与游戏事件。
- [ ] 建立 `tianjitown-integrations` 模块：
  - Residence 适配器。
  - Vault 适配器。
  - QuickShop-Hikari 税收适配器。
- [x] 外部插件 API 在 Maven 中使用 `provided` scope，不打入最终 JAR。
- [x] 在 `plugin.yml` 中声明硬依赖：`Vault`、`Residence`、`QuickShop-Hikari`。
- [x] 启动时检查 Vault `Economy` 服务是否真正注册，仅安装 Vault 本身不算通过。
- [x] 生产构建产出一个可安装的 Paper JAR。

## 3. 数据存储与单服一致性

- [ ] MySQL 是申请、成员关系、角色、领地、资金、税收、Buff、订单和投票的权威数据源。
- [ ] YAML 只保存可人工阅读/维护的小镇基本资料镜像，不参与资金和关系型数据事务。
- [ ] 领地使用 `world-uuid + chunk-x + chunk-z` 标识，同时保存世界名作为人类可读信息。
- [ ] 每个小镇保存自增版本号，高冲突写操作使用乐观锁。
- [ ] 扩张、资金扣除、镇长转让和投票结算在 MySQL 事务中完成。
- [ ] 所有资金与审批操作使用唯一幂等键。
- [ ] 插件在单个 Paper 进程内使用内存缓存，业务写入成功后立即失效/刷新对应缓存。
- [ ] 不开发数据库事件轮询、跨服 outbox、Redis 或服务器 ID 机制。
- [ ] 数据库 I/O 不得在 Paper 主线程上执行。
- [ ] 数据库暂时不可用时：
  - 继续使用最后的本地快照和 Residence 执行已有领地保护。
  - 禁止提交/审批申请、扩张、更改税率和消费。
  - QuickShop-Hikari 交易保持可用，税收按最后缓存税率执行，后续依据 QuickShop 交易历史对账。

## 4. 数据库表

- [ ] `towns`：小镇主体、状态、镇长、税率、规则版本和乐观锁版本。
- [ ] `town_members`：成员 UUID、角色、入镇时间、最后活跃时间和规则确认版本。
- [ ] `town_applications`：申请人、文本资料、状态、选址和提交时间。
- [ ] `application_reviews`：管理员审批、补充意见和原因。
- [ ] `site_reservations`：申请期的 3×3 区块临时保留。
- [ ] `territory_units`：按 3×3 网格管理的领地单元。
- [ ] `town_accounts`：公共资金当前余额快照。
- [ ] `ledger_entries`：不可变资金流水。
- [ ] `quickshop_tax_records`：QuickShop 成交、收款方、所属小镇、税率、税额和幂等标识。
- [ ] `active_buffs`：已购买且未过期的公共 Buff。
- [ ] `resource_orders`：资源采购订单和领取状态。
- [ ] `votes`：投票目标、门槛、选民快照和截止时间。
- [ ] `vote_ballots`：成员选票，对 `vote-id + voter-uuid` 建唯一约束。
- [ ] `audit_logs`：审批、管理员代办、资金调整和领地修复记录。
- [ ] `town_profile_sync`：YAML 镜像 revision、哈希、最后导出时间和同步状态。

### 4.1 YAML 小镇基本资料

- [ ] 每个小镇生成 `plugins/TianjiTown/towns/<town-uuid>.yml`。
- [ ] YAML 允许人工修改的字段限定为：
  - `name`：展示名称。
  - `short-name`：简称。
  - `description`：简介。
  - `rules`：规则文本。
  - `public-settings`：只影响展示/玩家告知的开关。
- [ ] YAML 包含但禁止人工改动的 `schema-version`、`town-id`、`revision`、`created-at` 和 `checksum`。
- [ ] 镇长、成员、角色、税率、余额、流水、领地、Buff、投票和订单不以 YAML 作为可编辑数据源。
- [ ] 正常 UI/管理命令修改基本资料时，先提交 MySQL，再通过临时文件 + 原子替换更新 YAML。
- [ ] YAML 写入失败不回滚已成功的业务事务，而是将同步状态标记为待重试并告警。
- [ ] 特殊情况下的人工修改流程固定为：进入维护模式（或停服编辑后以维护模式启动） → 备份 → 编辑 YAML → 校验 → 导入 MySQL → 重新生成 checksum。
- [ ] 不在启动时无条件自动覆盖 MySQL；发现 YAML 变动时进入待确认状态。
- [ ] 实现 `/townadmin data validate <town>`、`export <town|all>` 和 `import <town> --confirm`。
- [ ] 每次导入前检查 schema、revision、UUID、名称唯一性、文本长度和非法格式。
- [ ] 每次导入/导出写审计日志，保留修改前 YAML 备份。

## 5. 玩家 UI 系统

### 5.1 入口

- [ ] 不提供 `/town`、`/t`等任何玩家命令。
- [ ] 实现管理员可创建的“小镇服务台”：
  - 推荐使用讲台作为服务台方块。
  - 在讲台 TileState PDC 中记录服务台 ID。
  - 玩家右键后打开小镇主菜单，不执行玩家命令。
- [ ] 实现“小镇手册”特殊书本：
  - 通过 PDC 识别，不依赖物品名称。
  - 右键书本直接打开主菜单。
  - 丢失后可在服务台重新领取。
- [ ] 将服务台作为标准入口，手册作为便携入口。

### 5.2 箱子 GUI 规范

- [ ] 主菜单根据玩家状态展示：
  - 未入镇：申请小镇、查看邀请。
  - 普通成员：小镇信息、领地、资金、Buff、资源采购、投票、退出。
  - 官员：增加成员邀请和小镇管理界面。
  - 镇长：增加税率、规则、扩张、踢人和转让界面。
- [ ] 每个敏感操作使用独立确认界面，明示对象、价格和后果。
- [ ] GUI 按钮必须校验 PDC 和当前会话 ID，不依赖展示名称。
- [ ] 阻止玩家移动、复制或带走 GUI 中的按钮物品。
- [ ] 关闭 GUI、退出服务器或会话超时后使会话失效，重新打开时重读最新数据。

### 5.3 书本表单

- [ ] 小镇名称、简介和规则等长文本使用临时书与笔编辑。
- [ ] 通过 `PlayerEditBookEvent` 接收内容，不通过聊天或命令收集。
- [ ] 书本表单带有一次性会话 ID、表单类型和申请 ID。
- [ ] 过滤 MiniMessage/格式代码、超长文本和不允许的字符。
- [ ] 编辑完成后返回申请摘要 GUI，由玩家再次确认。

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

- [ ] 玩家在主菜单中创建草稿。
- [ ] 校验申请人未加入小镇、没有其他未结束申请且满足配置条件。
- [ ] 通过书本表单填写名称、简称、简介和规则。
- [ ] 玩家站在候选中心区块时，在 GUI 中点击“选择当前区块”。
- [ ] 在玩家眼前用粒子/临时边界预览 3×3 区块。
- [ ] 选址通过后创建有效期预留，防止并发申请占用同一区域。
- [ ] 提交前展示完整摘要，玩家二次确认后进入 `SUBMITTED`。
- [ ] `NEED_CHANGES` 状态向玩家显示管理员意见并允许重新编辑。

### 6.3 选址校验

- [ ] 必须位于允许的世界和服务区内。
- [ ] 3×3 区块不得超过服务区或世界边界。
- [ ] 不得与已有小镇领地、未过期选址预留重叠。
- [ ] 满足小镇间的最小缓冲距离。
- [ ] 不得覆盖出生点、活动区、管理区或其他黑名单区域。

### 6.4 批准建镇

- [ ] 批准时再次校验申请人、名称和领地是否仍然有效。
- [ ] 在同一数据库事务中创建小镇、镇长成员、公共账户和初始领地。
- [ ] 事务成功后创建 Residence 区域投影。
- [ ] Residence 创建失败时进入 `PROVISION_FAILED`，不将小镇对玩家标记为可用。
- [ ] 管理员可幂等重试，不得重复创建账户、成员或区域。

## 7. Residence 领地集成

- [ ] 定义 `LandProtectionService` 接口，业务层不直接调用 Residence API。
- [ ] 每个 3×3 领地单元映射为一个系统 Residence，名称使用 `tt_<townId>_<gridX>_<gridZ>`。
- [ ] Residence 范围严格对齐区块边界，竖直范围按当前世界最低/最高高度生成。
- [ ] Residence 所有者使用受控系统账户，不直接设置为镇长。
- [ ] 系统 Residence 只使用 `tt_` 保留命名空间，不修改现有玩家 Residence 或 Residence 全局限额。
- [ ] 利用现服 `Selection.IgnoreY: true` 的设定创建全高度区域，同时以 Paper 当前世界最小/最大高度做边界验证。
- [ ] 成员、官员和镇长的 Residence 权限由插件统一同步。
- [ ] 阻止玩家通过 Residence 命令改名、转让、删除或改变边界。
- [ ] 配置周期性对账：
  - 数据库有单元、Residence 缺失时重建。
  - Residence 多出、越界或权限错误时报警并修复。
  - 修复全部记入审计日志。

## 8. 领地扩张

- [ ] 初始领地固定为一个 3×3 区块单元。
- [ ] 以初始中心建立固定 3×3 网格。
- [ ] 只能在现有单元的东、西、南、北添加相邻单元。
- [ ] 新单元必须与旧领地连通，不允许飞地。
- [ ] 扩张价格使用可配置指数公式：`ceil(baseCost * growthFactor^(unitCount - 1))`。
- [ ] 金额计算使用 `BigDecimal`，并按 Vault 经济精度统一舍入。
- [ ] 玩家在 GUI 中选择方向，查看边界预览、价格和扩张后总面积。
- [ ] 扣款和占用数据在同一事务中完成，Residence 创建失败时执行可追踪补偿。

## 9. QuickShop-Hikari 小镇税收

### 9.1 税收语义

- [ ] 只对 QuickShop-Hikari 成交产生的玩家收入征税。
- [ ] 不监听普通 Vault 余额变化，不对任务、转账、管理员调整或其他插件收入征税。
- [ ] 征税对象始终是此次 QuickShop 交易的“实际收款方”：
  - 出售商店向玩家卖出物品：商店所有者是收款方。
  - 收购商店从玩家买入物品：与商店交易的玩家是收款方。
- [ ] 只有收款方属于活跃小镇时才征收该小镇的税率。
- [ ] 税额从收款方本次收入中扣除，不额外向付款方加价。
- [ ] 税率使用基点或定点小数存储，禁止使用二进制浮点数作为账本依据。

### 9.2 QuickShop 事件接入

- [ ] 解决 QuickShop 版本门槛：现服历史快照为 6.2.0.10，而新 `ShopEnhancedTaxEvent` 税务 API 从 6.2.0.11 开始提供。
- [ ] 推荐在测试服将 QuickShop-Hikari 升级到经验证的 6.2.0.11 或更高兼容版，完成 H2 备份、数据库升级、商店交易和插件附加组件回归。
- [ ] 如生产必须保持 QuickShop 6.2.0.10，则建立独立的 6.2.0.10 税务适配器，验证 `ShopTaxEvent` 能否正确表达“实际收款方”税率；无法精确表达时不上线动态税。
- [ ] 针对最终生产锁定版本验证对应 tax event、`EconomyTransactionEvent` 和 `ShopSuccessPurchaseEvent` API。
- [ ] 在测试环境中复制现服 QuickShop 基线：Vault economy type、默认货币、本地 H2、交易日志入库和 transaction metric。
- [ ] TianjiTown 不要求将 QuickShop 从 H2 迁移到 MySQL；优先通过官方事件/API 对账。
- [ ] 在 QuickShop 计算税率时：
  - 识别交易方向和实际收款方。
  - 从本地成员/税率快照读取小镇税率，事件中不访问数据库。
  - 显式覆盖收款方对应的 tax rate，并将另一方的 TianjiTown 税设为零，不继承现服固定 5% `player` 语义。
- [ ] 在 `EconomyTransactionEvent` 中将税款收取账户指向 TianjiTown 专用 Vault 清算账户。
- [ ] 在 `ShopSuccessPurchaseEvent` 之后才创建小镇税收流水，交易失败或回滚时不入账。
- [ ] 对 QuickShop-Hikari 事件中税额字段做两种交易方向的自动化验证，避免收购/出售方向反转。
- [ ] 建立可重复计算的税收幂等标识；若 QuickShop API 不提供稳定交易 ID，需在版本验证阶段确定事件关联策略。
- [ ] 以 QuickShop 交易历史作为异常对账依据，不直接修改 QuickShop 数据库。
- [ ] 上线切换税务策略时单独记录 QuickShop 当前 `tax` 账户余额，不自动归入任何小镇。

### 9.3 清算账户与对账

- [ ] 创建一个受管的 Vault 清算账户，保管所有小镇实际税款。
- [ ] 在 XConomy 上验证清算账户的离线存款、扣款、重启持久化和 Vault 可见性。
- [ ] 核对 XConomy `non-player-account` 与 DailyTaxEconomy 配置，再最终确定清算账户名；不仅依赖名称带 `tax` 的默认行为。
- [ ] 数据库中按小镇分账，清算账户余额作为所有小镇账本的外部备付金。
- [ ] 定时校验：`Vault 清算账户余额 >= 各小镇可用余额合计 + 待处理金额`。
- [ ] 不一致时禁止新的小镇消费，记录高优先级告警并由管理员对账。
- [ ] 管理员资金调整必须填写原因，同时留下 Vault 操作结果和内部账本记录。

## 10. 公共资金与流水

- [ ] 账本至少支持：QuickShop 税收、成员捐款、领地扩张、Buff 购买、资源采购、退款和管理员调整。
- [ ] 每条流水包含小镇、金额、变更后余额、类型、操作者、世界/来源、业务 ID、时间和备注。
- [ ] 普通成员可在 GUI 中查看至少最近 180 天的完整流水。
- [ ] 原始流水原则上永久保留，180 天只是玩家默认查询范围。
- [ ] 所有扣款先在事务内检查可用余额，禁止产生负余额。
- [ ] 成员捐款使用 GUI 选择预设金额或通过书本表单填写数值。

## 11. 公共 Buff

- [ ] 配置可购买的 Potion Effect 和 Attribute Modifier。
- [ ] 每个 Buff 定义基础价格、持续时间、最大等级、叠加规则和允许世界。
- [ ] 价格根据当前叠加层数使用可配置倍率增长。
- [ ] 成员在 GUI 中预览价格、强度、持续时间和当前效果。
- [ ] 完成公共资金扣款后创建 `active_buffs`。
- [ ] 成员登录、重生和世界变更时重新校验 Buff。
- [ ] Attribute Modifier 使用稳定 namespaced key，避免重登后重复叠加。
- [ ] Buff 过期、玩家退镇或进入不允许世界时移除效果。

## 12. 资源采购

- [ ] 使用配置文件定义商品、单价、每次上限和每日上限。
- [ ] 成员在箱子 GUI 中选择商品和数量。
- [ ] 付款成功后创建资源订单，不直接将物品塞入可能已满的背包。
- [ ] 订单进入个人待领取箱 GUI，领取时再检查背包空间。
- [ ] 结算、生成订单和修改公共资金在同一数据库事务中完成。
- [ ] 重连后可继续领取，不因断线丢失订单。

## 13. 成员和规则告知

- [ ] 角色固定为 `MAYOR`、`OFFICER`、`MEMBER`。
- [ ] 所有身份判定使用 UUID，玩家名只用于显示和搜索。
- [ ] 邀请从小镇 GUI 发起，受邀玩家在自己的邀请箱中查看。
- [ ] 接受前必须展示：
  - 小镇名称、镇长和人数。
  - 当前税率。
  - 税收仅来自 QuickShop-Hikari 收入。
  - 领地规模。
  - 有效 Buff。
  - 小镇规则和退出规则。
- [ ] 玩家二次确认后加入，记录确认的规则版本。
- [ ] 规则或税率变更后在成员下次打开主菜单/登录时显示变更摘要。
- [ ] 成员可在 GUI 中主动退出。
- [ ] 镇长可在 GUI 中强制移除普通成员/官员。
- [ ] 镇长不得通过普通移除流程被踢出。
- [ ] 镇长转让需候选成员在 GUI 中接受。
- [ ] 镇长是最后一名成员时，进入受控的解散/归档确认流程。

## 14. 成员投票

- [ ] 默认将最近配置天数内登录且入镇达到最低天数的成员定义为活跃成员。
- [ ] 创建投票时冻结选民快照，期间的加入/退出不改变本次门槛。
- [ ] 被投票对象不计入有效选民。
- [ ] 投票踢人需要赞成票严格超过有效选民的 50%。
- [ ] 强制更换镇长需要赞成票达到有效选民的 2/3。
- [ ] 每个 UUID 对每次投票只能投一票。
- [ ] 默认投票持续时间可配置，建议 72 小时。
- [ ] 发起、投票、结算和执行通过 GUI 完成，并全部记录审计日志。
- [ ] 结算任务可幂等重试，不得重复踢人或转让。

## 15. 领地玩法加成

### 15.1 建筑方块返还

- [ ] 配置可触发的方块白名单、概率和每日上限。
- [ ] 只有小镇成员在自己小镇 Residence 内放置时触发。
- [ ] 保留正常方块放置，概率命中时向原物品栈返还一个物品。
- [ ] 处理多方块、水桶、容器、床、门和其他特殊方块，防止刷物品。
- [ ] 不对自动机械放置、粘液块推动或非玩家放置触发。

### 15.2 信标增强

- [ ] 只增强位于小镇 Residence 内的有效信标。
- [ ] 配置允许的范围倍率、等级上限和世界白名单。
- [ ] 不超过 Minecraft/Paper 当前版本可安全表达的效果强度。
- [ ] 信标被移除、失效或领地变更时清理附加效果。

## 16. 管理员命令

> 所有命令默认只允许控制台或拥有明确管理权限的玩家执行。管理命令不代替玩家 UI，用于审批、代办和故障恢复。

### 16.1 服务台和 UI

- [ ] `/townadmin terminal create`：将目标讲台绑定为服务台。
- [ ] `/townadmin terminal remove`：移除目标服务台。
- [ ] `/townadmin terminal list`：列出当前服务器的小镇服务台。
- [ ] `/townadmin handbook give <player>`：发放小镇手册。
- [ ] `/townadmin ui open <player>`：为指定玩家打开小镇主菜单。

### 16.2 申请与审批

- [ ] `/townadmin application list [state]`。
- [ ] `/townadmin application view <applicationId>`。
- [ ] `/townadmin application create <player>`。
- [ ] `/townadmin application set-site <applicationId>`。
- [ ] `/townadmin application submit <applicationId>`。
- [ ] `/townadmin application request-changes <applicationId> <reason>`。
- [ ] `/townadmin application approve <applicationId>`。
- [ ] `/townadmin application reject <applicationId> <reason>`。
- [ ] `/townadmin application cancel <applicationId> <reason>`。
- [ ] `/townadmin application retry-provision <applicationId>`。

### 16.3 小镇、成员和领地

- [ ] `/townadmin town info <town>`。
- [ ] `/townadmin town create <owner> <name>`：受控应急创建。
- [ ] `/townadmin town archive <town> <reason>`。
- [ ] `/townadmin member invite <town> <player>`。
- [ ] `/townadmin member add <town> <player> [role]`。
- [ ] `/townadmin member remove <town> <player> <reason>`。
- [ ] `/townadmin member role <town> <player> <role>`。
- [ ] `/townadmin mayor transfer <town> <player>`。
- [ ] `/townadmin territory preview <town> <direction>`。
- [ ] `/townadmin territory expand <town> <direction> [--free]`。
- [ ] `/townadmin territory reconcile <town>`。
- [ ] `/townadmin territory rebuild <town>`。

### 16.4 规则、资金、Buff 和投票

- [ ] `/townadmin tax set <town> <rate>`。
- [ ] `/townadmin rules edit <town> <player>`：向指定玩家发放管理员编辑书。
- [ ] `/townadmin money balance <town>`。
- [ ] `/townadmin money ledger <town> [page]`。
- [ ] `/townadmin money adjust <town> <amount> <reason>`。
- [ ] `/townadmin money reconcile`。
- [ ] `/townadmin buff grant <town> <buff> <duration> [level]`。
- [ ] `/townadmin buff remove <town> <buff>`。
- [ ] `/townadmin order list <town>`。
- [ ] `/townadmin order deliver <orderId>`。
- [ ] `/townadmin order cancel <orderId> <reason>`。
- [ ] `/townadmin vote create-kick <town> <target>`。
- [ ] `/townadmin vote create-mayor <town> <candidate>`。
- [ ] `/townadmin vote settle <voteId>`。
- [ ] `/townadmin vote cancel <voteId> <reason>`。

### 16.5 运维

- [ ] `/townadmin reload`：只重载明确支持热更新的配置。
- [ ] `/townadmin status`：显示数据库、Vault、Residence、QuickShop 和后台任务状态。
- [ ] `/townadmin audit <town|player> [page]`。
- [ ] `/townadmin cache refresh [town]`。
- [ ] `/townadmin quickshop reconcile [from] [to]`。

### 16.6 权限

- [ ] `tianjitown.admin`：全部管理权限。
- [ ] `tianjitown.admin.terminal`：服务台和手册。
- [ ] `tianjitown.admin.application`：申请代办。
- [ ] `tianjitown.admin.review`：审批。
- [ ] `tianjitown.admin.member`：成员与镇长管理。
- [ ] `tianjitown.admin.territory`：领地与 Residence 修复。
- [ ] `tianjitown.admin.money`：资金查询。
- [ ] `tianjitown.admin.money.adjust`：资金调整。
- [ ] `tianjitown.admin.tax`：税率与 QuickShop 对账。
- [ ] `tianjitown.admin.buff`：Buff 代办。
- [ ] `tianjitown.admin.vote`：投票代办。
- [ ] `tianjitown.admin.audit`：审计日志。
- [ ] `tianjitown.admin.data`：YAML 校验、导入和导出。

## 17. 新周目初始化边界

- [x] TianjiTown 使用独立数据库 `tianjitown`，首次生产安装从空业务 schema 开始。
- [ ] 首次上线前确认 `towns`、`town_members`、`territory_units` 等业务表为空，仅允许 Flyway schema history 和阶段门禁记录存在。
- [ ] 小镇、成员、角色、名称和领地归属仅以 TianjiTown MySQL 为权威数据源。
- [ ] TianjiTown 只创建和管理 `tt_` 命名空间的 Residence；任何其他 Residence 一律视为外部领地，只参与碰撞避让。
- [ ] 业务数据只能由 TianjiTown 的申请、审批和管理流程创建，不提供外部数据导入入口。
- [ ] 上线前只备份当前依赖状态：Residence、QuickShop H2、XConomy/清算账户和 TianjiTown MySQL，供故障回滚使用。

## 18. 配置文件

- [ ] `config.yml`：MySQL、线程池、时区、YAML 镜像和运维选项；不包含 `server-id`。
- [ ] `application.yml`：申请条件、冷却、预留时间和名称规则。
- [ ] `service-areas.yml`：世界、服务区、黑名单区和缓冲距离。
- [ ] `territory.yml`：扩张价格、单元上限和 Residence 标志模板。
- [ ] `economy.yml`：税率上限、清算账户、金额精度和对账策略。
- [ ] `buffs.yml`：Buff 类型、价格、层数、强度和持续时间。
- [ ] `resources.yml`：资源商品、数量限制和价格。
- [ ] `governance.yml`：活跃成员定义、投票时间和门槛。
- [ ] `gui/*.yml`：菜单布局、图标、文案 key 和槽位。
- [ ] `messages_zh_CN.yml`：简体中文文案。
- [ ] `towns/<town-uuid>.yml`：小镇基本资料镜像，遵守第 4.1 节的导入/导出约束。
- [ ] 为配置增加 schema version，启动时拒绝不可安全识别的旧配置。

## 19. 安全、审计与故障处理

- [ ] 不接受客户端或未验证插件消息直接改变小镇数据。
- [ ] 所有管理员代办操作记录执行者、参数、原因、结果和执行世界。
- [ ] 删除 Residence、归档小镇、调整资金和强制转让需要二次确认。
- [ ] 后台任务在单 Paper 进程内使用单实例锁，并以 MySQL 中的业务状态保证重启后幂等结算。
- [ ] 定时处理过期选址、过期 Buff、投票结算、未完成订单和资金补偿。
- [ ] 插件启动时检查：
  - 数据库 schema 版本。
  - MySQL 连接、schema 和必需表约束是否正常。
  - Residence、Vault Economy 和 QuickShop-Hikari 是否可用。
  - QuickShop-Hikari API 是否与编译时锁定版本兼容。
- [ ] 启动时检查 YAML 镜像 checksum/revision，发现人工变更只报告并锁定导入，不自动覆盖 MySQL。
- [ ] 必需依赖或数据库不可用时，以明确错误停止启用写功能，不静默降级为不保护领地。

## 20. 测试计划

### 20.1 单元测试

- [ ] 状态机允许/拒绝转移。
- [ ] 3×3 区块计算、方向扩张、连通性和重叠判断。
- [ ] 扩张指数价格、金额精度和边界值。
- [ ] QuickShop 收购/出售两种方向下的收款方判定。
- [ ] 不同税率、最小金额和舍入规则。
- [ ] 踢人超过 50% 与换镇长 2/3 的门槛边界。
- [ ] GUI 会话过期、伪造物品和双击竞态。
- [ ] YAML schema、checksum、revision、非法字段和名称冲突校验。

### 20.2 集成测试

- [ ] 使用 Testcontainers 启动与生产同主版本的 MySQL。
- [ ] 并发批准同一申请时只创建一个小镇。
- [ ] 并发申请/扩张时同一区块只被一个小镇占用。
- [ ] Residence 创建失败、重试和对账修复。
- [ ] Vault 扣款成功/失败与内部流水一致。
- [ ] QuickShop 交易成功、失败和回滚时的税收结果。
- [ ] 重放 QuickShop 事件不得重复计税。
- [ ] 后台结算任务重入、插件重载/重启时只执行一次。
- [ ] YAML 导出失败、人工修改、过期 revision、导入回滚和备份恢复。

### 20.3 测试服验收

- [ ] 搭建 1 个 Paper 预发服务器，不搭建 TianjiTown Velocity 端或第二子服。
- [ ] 安装与生产相同版本的 Residence、Vault、XConomy、QuickShop-Hikari、DailyTaxEconomy 及相关菜单/权限插件。
- [ ] 每个发布阶段只验收当前已开放的玩家功能；未交付的后续功能在 UI 和命令帮助中不得可见。
- [ ] 第 1 阶段玩家不使用任何命令完成申请、选址、查看小镇、邀请/加入和主动退出。
- [ ] 后续阶段按顺序追加验收：治理/投票 → 税收/账本/扩张 → Buff/资源采购 → 领地加成。
- [ ] 管理员命令只代办当前阶段已交付的功能，并通过权限、审计和二次确认验收。
- [ ] 玩家登录/重连后，当前阶段已启用的成员身份、税率、账本和 Buff 状态正确恢复。
- [ ] 人为停止数据库后领地仍保护，新写操作被拒绝，恢复后可对账。
- [ ] 验证全新数据库首次启动只生成本阶段 Flyway 表，不读取任何外部小镇数据。

## 21. 分阶段交付

### 逐阶段上线原则

- [ ] 每个生产阶段使用独立版本号、Flyway 迁移和发布记录。
- [ ] 数据库迁移优先使用向前兼容的增量变更；不在普通发布中删表、改已有字段语义或破坏旧版本读取。
- [ ] 后续模块使用功能开关，但关闭的功能不在玩家 UI 中出现。
- [ ] 每个阶段都提供：发布前检查、备份、安装/升级步骤、验收用例和回滚步骤。
- [ ] 回滚默认只回退 JAR/配置并关闭新功能，不自动删除 MySQL 数据、YAML 资料或 Residence 领地。
- [ ] 在预发环境通过本阶段验收后才允许生产部署，不以“后续阶段会修复”作为带病上线理由。

### 第 0 阶段：首版上线门禁（不单独发布）

#### 目标

解决第 1 阶段无法在生产环境安全运行的前置风险。QuickShop 动态税的版本取舍不阻塞第 1 阶段，延后到第 3 阶段入口门禁处理。

#### TODO

- [x] 按默认方案锁定 Paper/Leaf、Java、Residence、Vault、XConomy 和 MySQL 参数，并实现运行时复核。
- [x] 建立 Maven 聚合工程骨架、CI 构建、单元测试和可重现的生产 JAR。
- [x] 实现最小验证程序，覆盖 Residence API 的创建、成员权限、删除、重建和区块碰撞判断。
  - [x] 已实现带预发世界白名单、空区块确认和自动清理的冒烟程序。
  - [x] 已于 2026-08-05 在 Paper 26.2-84 / Java 25 同版本测试服执行并保存结果。
- [x] 确定 MySQL 数据库、最小权限账户、连接池、Flyway 和备份/恢复方案。
- [x] 完成 YAML 的 schema、revision、checksum、原子替换和可编辑字段白名单设计。
- [x] 确认新周目采用独立空业务 schema，全量重建小镇、成员、名称和领地关系。
- [x] 提供当前运行依赖的最小范围备份方案：Residence、QuickShop H2、XConomy/清算账户和 TianjiTown MySQL。
  - [x] 已提供插件文件和 MySQL 备份脚本。
  - [ ] 待生产上线前执行逻辑备份及恢复演练。

#### 完成门槛

- [x] 能在生产同版本的预发服上启动空插件，通过依赖、MySQL、YAML 和 Residence 自检。
- [x] 确认首次启动时业务表为空，只有阶段门禁和 Flyway 元数据。

### 第 1 阶段：申请、批准与可运行小镇（`1.0.0`）

#### 阶段边界

本阶段是第一个生产版。它不实现税收、公共账本、付费扩张、投票、Buff、资源采购和领地加成；这些入口必须完全隐藏。

#### 1. 基础运行能力

- [x] 完成 `tianjitown-core`、`tianjitown-storage`、`tianjitown-paper` 和 Residence/Vault 集成的最小可生产实现。
- [x] 实现首批 MySQL 表：`towns`、`town_members`、`town_applications`、`application_reviews`、`site_reservations`、`territory_units`、`audit_logs`、`town_profile_sync`。
- [x] 实现 MySQL 异步访问、事务、幂等键、乐观锁、连接失效降级和主线程保护。
- [x] 实现 YAML 基本资料镜像、自动导出、校验、手工导入、备份和审计。
- [x] 提供 `/townadmin status`、`reload`、`audit`、`data validate/export/import` 和必需权限。
- [x] 启动失败和部分依赖不可用时给出可操作的中文错误，不静默创建半成品小镇。

#### 2. 玩家申请与基本资料

- [x] 实现讲台小镇服务台、小镇手册和第 1 版主 GUI，不开放玩家命令。
- [x] 实现书本申请表单：名称、简称、简介、规则和确认页。
- [x] 实现 `DRAFT → SITE_SELECTED → SUBMITTED → UNDER_REVIEW/NEED_CHANGES → APPROVED_PROVISIONING → ACTIVE` 完整状态机。
- [x] 实现申请人资格、名称唯一性、文本安全、未完成申请和冷却校验。
- [x] 实现申请摘要、管理员意见、补充后重新提交和申请人撤回。
- [x] 批准后生成 MySQL 小镇记录与 `towns/<uuid>.yml`，并向玩家提供只读小镇详情 GUI。
- [x] 镇长可通过书本 UI 修改简介和规则；名称/简称修改需要管理员审核或代办。

#### 3. 选址与初始领地

- [x] 玩家通过 GUI 记录当前区块为中心，预览 3×3 区块并生成有效期选址预留。
- [x] 校验服务区、世界边界、黑名单区、已有 Residence、已有 TianjiTown 领地、其他申请预留和小镇缓冲距离。
- [x] 批准时在 MySQL 事务内占用初始领地，再生成 `tt_` 命名空间的 Residence 投影。
- [x] 实现 `PROVISION_FAILED`、管理员幂等重试、Residence 对账/重建和完整审计。
- [x] 第 1 阶段领地固定为初始 3×3，不提供扩张 UI。

#### 4. 最小成员体系

- [x] 申请人在批准后自动成为 `MAYOR`。
- [x] 支持镇长通过 GUI 邀请玩家，被邀者查看完整小镇资料/规则并确认后加入为 `MEMBER`。
- [x] 支持普通成员主动退出；第 1 阶段不提供官员、投票踢人或强制换镇长。
- [x] 提供管理员应急移除成员、转移镇长和归档小镇命令，全部需要原因和审计。
- [x] 成员数不设上限，成员列表从第 1 阶段开始使用分页查询。

#### 5. 新周目数据边界

- [x] 申请、成员、角色和名称唯一性只查询 TianjiTown MySQL。
- [x] 所有名称均按本系统规则申请并建立唯一约束。
- [x] 任何新小镇选址必须避开当前世界内的所有外部 Residence，以免覆盖非 TianjiTown 领地。
- [x] 成员关系、角色和权限均由 TianjiTown 领域模型管理。
- [x] 启动与运行过程中只加载 TianjiTown 自有数据库、配置和 YAML 镜像。

#### 6. 第 1 阶段管理命令

- [x] 完成服务台/手册命令、申请列表/详情/补件/批准/拒绝/重试命令。
- [x] 完成小镇详情、成员邀请/添加/移除、镇长紧急转移和小镇归档命令。
- [x] 完成领地预览、Residence 对账/重建和 YAML 数据维护命令。
- [x] 未实现的 money/tax/buff/order/vote/expand 管理命令不在第 1 阶段注册。

#### 第 1 阶段生产验收门槛

- [ ] 玩家全程不使用命令，完成“领取手册/打开服务台 → 填写申请 → 选址 → 提交 → 收到批准 → 查看小镇”。
- [ ] 管理员能审核、要求补件、拒绝、批准和重试失败创建。
- [ ] 批准后 MySQL、YAML、初始成员和 Residence 3×3 领地一致，不存在“已批准但无保护领地”的 ACTIVE 小镇。
- [ ] 两名管理员同时批准、两名玩家抢占同一选址和重复点击 GUI 均不会重复建镇或越界占地。
- [ ] MySQL 中断时禁止新写入，但已有 Residence 仍继续保护；MySQL 恢复后插件可正常继续。
- [ ] Paper 重启后申请、预留、小镇、成员、YAML 和 Residence 状态可恢复/对账。
- [ ] 空数据库上线后，所有小镇、成员和领地均由 TianjiTown 流程新建。
- [ ] 完成生产备份、上线、下线新建镇入口和回退 JAR 的演练。

### 第 2 阶段：完整成员治理（`1.1.0`）

#### 功能 TODO

- [ ] 启用 `OFFICER` 角色，完成角色权限、邀请权和 Residence 权限同步。
- [ ] 实现镇长踢出成员、镇长转让双方确认和无主小镇保护。
- [ ] 实现活跃成员快照、投票踢人、2/3 强制更换镇长、投票过期和幂等结算。
- [ ] 实现小镇解散/归档流程，保留审计与可恢复数据。
- [ ] 实现规则版本变更告知和成员重新确认。

#### 生产升级门槛

- [ ] 从 `1.0.0` 升级后，已有申请、小镇、YAML 和 Residence 无需人工重建。
- [ ] 角色变更、踢人、镇长转让和归档不会产生重复成员、无主 ACTIVE 小镇或 Residence 权限残留。
- [ ] 投票门槛、选民快照、并发投票和重启后结算通过集成测试。

### 第 3 阶段：QuickShop 税收、公共账本与领地扩张（`1.2.0`）

#### 入口门禁

- [ ] 在编写税收适配器前，完成 QuickShop 6.2.0.10 税务适配器与升级至 6.2.0.11+ 的取舍。
- [ ] 在预发环境验证 QuickShop 收购/出售方向、税率修改、税款账户、成功/回滚事件和幂等标识。
- [ ] 完成 XConomy 清算账户、DailyTaxEconomy 兼容性、QuickShop 当前固定 5% 税和 `tax` 账户切换方案。

#### 功能 TODO

- [ ] 启用 QuickShop 实际收款方动态小镇税，不对普通 Vault 变动或其他收入征税。
- [ ] 实现 Vault/XConomy 清算账户、小镇分账、`town_accounts`、`ledger_entries`、`quickshop_tax_records` 和差异锁定。
- [ ] 实现镇长税率设置、成员税率变更告知、税收来源说明和最近 180 天流水 GUI。
- [ ] 实现成员捐款、Vault 失败补偿、管理员资金调整和清算账户对账。
- [ ] 启用 3×3 网格扩张、方向预览、指数价格、公共资金扣款和 Residence 补偿/重建。
- [ ] 注册 money/tax/ledger/expand 管理命令和权限。

#### 生产升级门槛

- [ ] 上线前备份 QuickShop H2、XConomy、TianjiTown MySQL 和清算账户余额。
- [ ] 收购/出售、在线/离线商店所有者、交易失败/回滚、数据库中断和事件重放均不会重复征税或丢失税款。
- [ ] QuickShop 历史、Vault 清算账户和各小镇账本能完整对账。
- [ ] 扩张并发、余额不足、Residence 失败和重试均不会重复扣款或占地。
- [ ] 可通过功能开关立即停止新税收/新消费，但保留账本查询和对账能力。

### 第 4 阶段：公共 Buff 与资源采购（`1.3.0`）

#### 功能 TODO

- [ ] 实现配置驱动的 Potion Effect/Attribute Modifier Buff 商店、叠加倍率、期限和等级上限。
- [ ] 实现 `active_buffs`、购买扣款、登录/重生/世界变更刷新和过期清理。
- [ ] 实现资源商店、`resource_orders`、公共资金结算、个人待领取箱和断线恢复。
- [ ] 实现 Buff/资源商店 GUI、二次确认、购买权限和 buff/order 管理命令。
- [ ] 实现购买失败补偿、未领取订单检查和公共资金对账。

#### 生产升级门槛

- [ ] 满背包、双击购买、断线、重启、重复领取和账本写入失败均不造成物品/资金复制或丢失。
- [ ] Buff 不会在重登/重生后重复叠加，过期/退镇后能完整清理。
- [ ] 将 Buff 或资源商店功能开关关闭后，不影响税收、账本、领地和成员系统。

### 第 5 阶段：领地加成与计划功能完整版（`1.4.0`）

#### 功能 TODO

- [ ] 实现建筑方块白名单、概率返还、每日上限和特殊方块/自动化防刷。
- [ ] 实现小镇 Residence 内的信标范围/等级增强与移除清理。
- [ ] 完成 MySQL、YAML、Residence、Vault 清算账户和 QuickShop 交易历史的统一自检/对账报告。
- [ ] 完成配置 schema 升级、数据库增量升级、定时备份、一键诊断信息和告警文档。
- [ ] 完成管理员运维手册、玩家使用说明、上线/回滚手册和依赖升级检查清单。

#### `1.4.0` 门槛

- [ ] 完成第 22 节全部最终验收标准。
- [ ] 完成 Paper 重启、MySQL 中断、YAML 损坏、Residence 丢失、Vault 失败、QuickShop 回滚和依赖插件不可用的故障注入。
- [ ] 在生产数据副本上完成增量 schema 升级、对账、备份恢复与 JAR 回滚演练。

## 22. 最终验收标准

- [ ] 玩家不需要且无法使用小镇命令。
- [ ] 玩家能通过服务台/手册完成申请、选址、查询、成员、资金、扩张、投票和消费全流程。
- [ ] 管理员可通过 `/townadmin` 审批并代办/修复每类操作。
- [ ] 同一申请不能重复批准，同一区块不能被并发占用。
- [ ] 数据库领地与 Residence 区域能自动对账修复。
- [ ] QuickShop-Hikari 仅对小镇成员的实际成交收入征税，失败/回滚交易不产生税收流水。
- [ ] QuickShop 收购商店和出售商店都将税计入正确收款方所属小镇。
- [ ] Vault 清算账户与小镇内部账本可对账，所有差异可定位到交易或管理员操作。
- [ ] 所有高风险操作都可审计、可重试且不会重复扣款/入账。
- [ ] Paper 服务器或 TianjiTown 重启后不会损坏小镇状态或重复结算任务。
- [ ] YAML 基本资料可在维护流程中安全人工修改，不能借此绕过成员、资金、税率或领地事务。
- [ ] 小镇成员数没有插件层上限，且大成员列表使用分页/索引查询而不阻塞主线程。
- [ ] 成员可自由查看最近至少 180 天的公共资金流水。

## 参考接口文档

- [QuickShop-Hikari 自定义事件](https://quickshop-community.github.io/QuickShop-Hikari-Documents/zh-HK/docs/development/events)
- [QuickShop-Hikari 交易系统](https://quickshop-community.github.io/QuickShop-Hikari-Documents/de-DE/docs/modules/transaction-system)
- [QuickShop-Hikari 交易历史](https://quickshop-community.github.io/QuickShop-Hikari-Documents/docs/modules/shops/shop-history)
- [Residence 项目与 API](https://github.com/Zrips/Residence)
- [VaultAPI](https://github.com/MilkBowl/VaultAPI)
- [Paper 插件开发文档](https://docs.papermc.io/paper/dev/)
