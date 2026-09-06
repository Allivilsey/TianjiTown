# TianjiTown

天际服自用的单 Paper 服务器小镇系统。当前开发版本 `1.0.0-SNAPSHOT` 包含申请、治理、统一收入税、完整公共账本、付费领地扩张、公共 Buff、每周建筑返还、领地信标和统一运维诊断。

文档按职责分类，入口见 [`docs/README.md`](docs/README.md)；完整功能与玩法规则见 [`docs/FUNCTIONS_AND_GAMEPLAY.md`](docs/FUNCTIONS_AND_GAMEPLAY.md)。

## 构建

要求 Maven 3.9+ 与 JDK 25+：

```bash
mvn -B clean verify
```

唯一安装包输出为 `tianjitown-paper/target/TianjiTown-1.0.0-SNAPSHOT.jar`。Paper API、Residence 与 Vault API 使用 `provided` scope，不会打入插件 JAR；WorldBorder 通过运行时公开能力接入，同样不会被打入。最终 JAR 仅合并项目模块和 Lamp；Lamp 命令库会重定位到插件内部包，无需单独安装。HikariCP、Flyway、SQLite JDBC 及其传递依赖由 Paper 根据 `plugin.yml` 的 `libraries` 下载并加载，版本取自 Maven 构建属性。

首次启动（或升级到尚未缓存的依赖版本）需要服务器能够访问 Paper 配置的 Maven Central 镜像；依赖缓存在服务端 `libraries` 目录。离线部署需预先准备对应依赖缓存，不要把这些依赖 JAR 放入 `plugins`。构建测试仍使用完整存储依赖，Shade 打包白名单仅包含项目模块和 Lamp；新增需要内嵌的依赖时应同步更新白名单。

## 安装与 SQLite

1. 安装并启用 Residence、Vault、XConomy、WorldBorder、QuickShop-Hikari（版本不低于 `6.3.0.0`）、Jobs 和 GlobalMarketPlus，确保 Vault 已注册可用的 `Economy` 服务，并使用 `/wb` 为各世界配置边界。选址会要求初始 5×5 区块领地及其缓冲范围完整位于 WorldBorder 内；依赖未启用或目标世界未配置边界时会安全失败。
2. 将 JAR 放入 `plugins`，首次启动会自动创建 `plugins/TianjiTown/tianjitown.db`、`config.yml` 和 `messages.yml`，并执行 Flyway 迁移。
3. 如需更改位置，在 `config.yml` 中设置相对或绝对文件路径：

```yaml
database:
  file: 'tianjitown.db'
  connection-timeout-ms: 5000
  busy-timeout-ms: 5000
```

4. 启动后执行 `/tianjitown status`，确认状态为 `READY` 再开放服务台。

玩家界面统一使用 Paper Dialog；申请、捐款、资料编辑和审核原因等自由文本也直接在 Dialog 内填写。

SQLite 采用单连接串行写入、WAL、外键约束和 5 秒忙等待，无需额外数据库服务。数据库文件位置和超时参数只在插件重启后生效。

> 此 SQLite 版不会自动导入旧 MySQL 数据。已经在 MySQL 中运行的服务器应先保留完整备份，在隔离环境完成数据转换和验收后再切换；不要把旧 MySQL Flyway history 复制到 SQLite。

领地加成的升级、验收与回滚流程见 [`docs/deployment/TERRITORY_BONUSES.md`](docs/deployment/TERRITORY_BONUSES.md)，日常运维见 [`docs/operations/OPERATIONS.md`](docs/operations/OPERATIONS.md)。
测试清单、历史报告与隔离测试接口统一见 [`test/README.md`](test/README.md)；测试接口默认关闭且使用独立权限，不属于玩家或管理员正式功能。

## 管理员帮助

`/tianjitown` 的各级子命令通过 [Lamp](https://github.com/Revxrsal/Lamp) 注解注册。Lamp 负责命令路由、数字与 UUID 参数解析、默认值、范围校验、权限过滤和静态补全；业务运行时通过上下文注入。框架参数缺失或包含多余内容时会显示中文提示，不会因前缀匹配误执行其他命令。

不加引号的多词小镇名与其后金额、玩家、原因的组合，仍在异步数据库读取中按实际名称匹配。动态参数补全复用缓存并在服务器主线程执行，确认令牌与异步写入流程保持原有约束。

`/tianjitown` 或 `/tianjitown help` 显示精简分类。使用 `/tianjitown help <分类>` 查看完整语法，可用分类为 `system`、`station`、`application`、`town`、`member`、`vote`、`land`、`money`、`tax`、`ledger`、`expand` 和 `buff`。命令参数支持 Tab 自动补全；补全列表中的 `<原因>` 等尖括号内容只是当前位置的参数提示，必须替换为实际内容，不能原样提交。

### 系统与运维

```text
/tianjitown status
/tianjitown reload
/tianjitown maintenance <on|off|status>
/tianjitown audit [1~200]
/tianjitown diagnose [1~180天]
```

`reload` 会重读可热更新的配置和 `messages.yml`；SQLite 文件和超时参数需重启。维护模式会暂停服务台、手册、玩家界面和表单提交，不会移除现有 Residence 保护。`diagnose` 生成 SQLite、Residence、Vault 与 QuickShop 历史统一报告；SQLite 和依赖插件的备份需按停服运维流程执行。

### 服务台与手册

服务台 ID、世界、坐标和归属小镇保存在 SQLite 的 `service_stations` 表中，启动时加载；讲台 PDC 保留对应 ID 用于校验。创建和移除操作在数据库写入成功后才更新讲台并提示成功。

```text
/tianjitown station create|remove|info
/tianjitown station list
/tianjitown handbook [player]
```

`station create`、`remove` 和 `info` 需由游戏内管理员看向讲台执行。管理员也可潜行左键直接拆除已登记的服务台；管理员或镇长将小镇手册摆上空讲台后，摆放完成即可建立服务台，手册会保留在讲台上，镇长建立的服务台绑定本镇且每镇限一个。游戏内执行 `station list` 后可点击每条记录旁的“传送”。`handbook` 不填玩家时会发给执行者，控制台必须指定在线玩家；玩家从服务页领取手册默认有 1 小时冷却。

### 申请审批

```text
/tianjitown application list
/tianjitown application approve <小镇全名> <原因>
/tianjitown application reject <小镇全名> <原因>
/tianjitown application change <小镇全名> <原因>
```

### 小镇、成员与镇长

```text
/tianjitown town view <小镇全名>
/tianjitown town delete <小镇全名> <原因>
/tianjitown member add|remove <小镇全名> <玩家> <原因>
/tianjitown member role <小镇全名> <玩家> <DEPUTY_MAYOR|MEMBER>
/tianjitown mayor transfer <小镇全名> <玩家> <原因>
```

发出删除命令后，聊天栏会显示“确认执行”和“取消”按钮；确认仅限发起者使用，60 秒后失效。删除操作会先安全归档；只有 Residence 确认移除后才释放名称、小镇代码和区块占位，审计记录会保留。

### 治理投票

```text
/tianjitown vote create-kick <小镇全名> <目标玩家>
/tianjitown vote create-mayor <小镇全名> <候选玩家>
/tianjitown vote settle <voteId>
/tianjitown vote cancel <voteId> <原因>
```

普通治理通过玩家 Dialog 完成。投票创建时冻结活跃选民快照；踢人要求赞成票严格超过 50%，强制更换镇长要求达到 2/3。到期投票由后台任务幂等结算。

### 领地

```text
/tianjitown land preview <小镇全名>
/tianjitown land reconcile <小镇全名|all> [repair]
/tianjitown land rebuild <小镇全名|all>
```

`preview` 只能在游戏内执行。管理员手动执行 `reconcile` 时默认只检查，在目标后填写 `repair` 才立即修复；后台定时对账发现异常后会读取最新 SQLite 记录自动修复。`rebuild` 会移除后重建投影，因此发出命令后还需点击聊天栏确认按钮。确认前若目标小镇发生变化，操作会中止并要求重新发起。

### 公共资金、税率与扩张

```text
/tianjitown money view <小镇全名>
/tianjitown money adjust <小镇全名> <带符号金额> <原因>
/tianjitown money reconcile
/tianjitown tax set <小镇全名> <百分比> <原因>
/tianjitown ledger view <小镇全名>
/tianjitown expand view|preview <小镇全名> [方向]
```

税率以基点保存，同一税率用于 QuickShop、Jobs 和 GlobalMarketPlus 玩家收入，金额按 Vault 经济实现支持的精度处理。公共账本不限制历史时间。账本按北京时间每日 04:00 分日汇总：Jobs 税收显示为“职业收入”，QuickShop 与 GlobalMarketPlus 税收合并为“商店收入”，均包含实际到账的服务器补贴；当前周期显示累计，公共资金仍实时入账。三种税收共用每个小镇的每周／12 小时补贴额度；不足时部分补贴，耗尽后只入账税款。捐款和消费等记录继续逐笔展示，底层税收明细与账本完整保留。`money reconcile` 比较清算账户和内部总账；出现短款时会锁定新的公共资金消费，但账本查询、捐款和再次对账仍可使用。管理员调账必须填写原因。

### 公共 Buff

```text
/tianjitown buff list <小镇全名>
/tianjitown buff grant <小镇全名> <buffKey> <原因>
```

Buff 目录位于 `config.yml` 的 `buffs` 配置节。公共 Buff 不受世界限制，购买时可用滑块选择 1～4 周和 I～V 级；再次购买会按新选择覆盖同类生效项，且购买后不接受退款。默认“速度”每级提升 20%，“生命”每级增加 4 点生命值。效果、每周基础价格和角色权限在启动时校验，修改后需要重启；`shop-enabled` 开关可通过 `reload` 热更新。

周价、七种默认商品和递增扩张规则见 [定价说明](docs/deployment/PRICING.md)。

### 领地加成

建筑返还与领地信标位于 `config.yml` 的 `territory` 配置节，诊断位于 `operations` 配置节。建筑返还以 25% 概率处理生存模式成员在本镇有效 Residence 内放置的安全单方块，成功时只播放拾取音效，不展示内部额度。镇长或副镇长切换信标效果时，系统记录数据库中尚未存在或等级更高的效果，不再扫描区块中的信标。

## 玩家入口

插件不注册任何玩家命令。玩家通过讲台服务台、小镇手册和 Paper Dialog 操作；捐款、申请和资料修改都在 Dialog 原生输入框内完成。入镇采用申请制：玩家同时最多申请 3 个小镇，申请 48 小时有效；被拒绝后 24 小时内不能再次申请同一小镇，主动退出后 24 小时内不能申请新镇。每镇最多 1 名镇长、3 名副镇长，镇员不限；镇长和副镇长可在成员管理中维护访客名单，访客拥有与普通成员相同的 Residence 领地权限，但不获得成员身份、投票权、税务归属或公共 Buff。镇长和副镇长拥有相同管理权限，但解散小镇、领地扩张及副镇长任免仅限镇长。所有成员可查看完整公共资金流水和公共 Buff。规则或税率变更后，成员会在下次登录或打开主菜单时收到说明。

Buff 在登录、重生、跨世界、成员关系变化和到期时重新计算，Attribute Modifier 使用稳定 namespaced key。退出时保留玩家效果，仅清理本服任务和缓存，允许 HuskSync 携带属性 Buff；数据库到期时间不变，其他子服允许暂时超期。安装 HuskSync 时，回服校正在其同步完成后执行；未安装时在登录后执行。跨服同步范围及验收见 [Buff 部署文档](docs/deployment/BUFFS_AND_RESOURCES.md)。

申请选址和小镇领地预览按钮会传送至领地中心传送点，并显示持续刷新的火焰粒子边界；扩张预览只显示目标 5×5 区块边界，不会移动当前玩家。扩张页使用 25 个原版 Sprite 按钮展示完整 5×5 单元网格，玩家可直接选择与现有领地四方向相邻的格子；首次扩张支付 `5000.00` 公共资金，此后每次价格增长 5%，批量扩张逐块累计。

成员在自己小镇领地内放置未列入黑名单的普通建筑方块时，可能收到一个同种方块返还，操作栏会显示本周用量。有效小镇信标的原版效果会覆盖整个小镇领地；玩家无需额外领取。

管理命令使用小镇全名定位目标。申请人另行填写仅含 `1~12` 个英文字母的小镇代码（建议三个字母），代码转为小写后直接作为 Residence 名称，例如 `SKY` 生成 `sky`。
