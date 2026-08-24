# TianjiTown

天际服自用的单 Paper 服务器小镇系统。当前版本 `1.4.0` 包含申请、治理、统一收入税、完整公共账本、付费领地扩张、公共 Buff、每周建筑返还、领地信标和统一运维诊断。

文档按职责分类，入口见 [`docs/README.md`](docs/README.md)；完整功能与玩法规则见 [`docs/FUNCTIONS_AND_GAMEPLAY.md`](docs/FUNCTIONS_AND_GAMEPLAY.md)。

## 构建

要求 Maven 3.9+ 与 JDK 25+：

```bash
mvn -B clean verify
```

唯一安装包输出为 `tianjitown-paper/target/TianjiTown-1.4.0.jar`。Paper API、Residence 与 Vault API 使用 `provided` scope，不会打入插件 JAR；WorldBorder 通过运行时公开能力接入，同样不会被打入；HikariCP、Flyway 和 SQLite JDBC 会合并到最终 JAR。

## 安装与 SQLite

1. 安装并启用 Residence、Vault、XConomy、WorldBorder、QuickShop-Hikari（版本必须严格高于 `6.3.0.0`）、Jobs 和 GlobalMarketPlus，确保 Vault 已注册可用的 `Economy` 服务，并使用 `/wb` 为每个允许建镇的世界配置边界。选址会要求初始 3×3 区块领地及其缓冲范围完整位于 WorldBorder 内；依赖未启用或目标世界未配置边界时会安全失败。
2. 将 JAR 放入 `plugins`，首次启动会自动创建 `plugins/TianjiTown/tianjitown.db` 并执行 Flyway 迁移。
3. 如需更改位置，在 `config.yml` 中设置相对或绝对文件路径：

```yaml
database:
  file: 'tianjitown.db'
  connection-timeout-ms: 5000
  busy-timeout-ms: 5000
```

4. 按实际地图修改 `phase1.site.blacklist`，重启后执行 `/townadmin status`。状态为 `READY` 时才开放服务台。

玩家界面统一使用 Paper Dialog，可点击聊天表单用于需要自由文本输入的操作。

SQLite 采用单连接串行写入、WAL、外键约束和 5 秒忙等待，无需额外数据库服务。数据库文件位置和超时参数只在插件重启后生效。

> 此 SQLite 版不会自动导入旧 MySQL 数据。已经在 MySQL 中运行的服务器应先保留完整备份，在隔离环境完成数据转换和验收后再切换；不要把旧 MySQL Flyway history 复制到 SQLite。

领地加成的升级、验收与回滚流程见 [`docs/deployment/TERRITORY_BONUSES.md`](docs/deployment/TERRITORY_BONUSES.md)，日常运维见 [`docs/operations/OPERATIONS.md`](docs/operations/OPERATIONS.md)。
测试清单、历史报告与隔离测试接口统一见 [`test/README.md`](test/README.md)；测试接口默认关闭且使用独立权限，不属于玩家或管理员正式功能。

## 管理员帮助

`/townadmin` 或 `/townadmin help` 显示精简分类。使用 `/townadmin help <分类>` 查看完整语法，可用分类为 `system`、`station`、`application`、`town`、`member`、`vote`、`land`、`money`、`tax`、`ledger`、`expand` 和 `buff`。命令参数支持 Tab 自动补全；补全列表中的 `<原因>` 等尖括号内容只是当前位置的参数提示，必须替换为实际内容，不能原样提交。

### 系统与运维

```text
/townadmin status
/townadmin reload
/townadmin maintenance <on|off|status>
/townadmin audit [1~200]
/townadmin diagnose [1~180天]
/townadmin backup
```

`reload` 只重读可热更新的配置；SQLite 文件和超时参数需重启。维护模式会暂停服务台、手册、玩家界面和表单提交，不会移除现有 Residence 保护。`diagnose` 生成 SQLite、Residence、Vault 与 QuickShop 历史统一报告；`backup` 创建 SQLite 在线一致性备份、配置快照与 SHA-256。

### 服务台与手册

```text
/townadmin station create|remove|info
/townadmin station list
/townadmin handbook [player]
```

`station create`、`remove` 和 `info` 需由游戏内管理员看向讲台执行。游戏内执行 `station list` 后可点击每条记录旁的“传送”。`handbook` 不填玩家时会发给执行者，控制台必须指定在线玩家。

### 申请审批

```text
/townadmin application list
/townadmin application approve <小镇全名> <原因>
/townadmin application reject <小镇全名> <原因>
/townadmin application change <小镇全名> <原因>
```

### 小镇、成员与镇长

```text
/townadmin town view <小镇全名>
/townadmin town delete <小镇全名> <原因>
/townadmin member add|remove <小镇全名> <玩家> <原因>
/townadmin member role <小镇全名> <玩家> <DEPUTY_MAYOR|MEMBER>
/townadmin mayor transfer <小镇全名> <玩家> <原因>
```

发出删除命令后，聊天栏会显示“确认执行”和“取消”按钮；确认仅限发起者使用，60 秒后失效。删除操作会先安全归档；只有 Residence 确认移除后才释放名称、领地名称和区块占位，审计记录会保留。

### 治理投票

```text
/townadmin vote create-kick <小镇全名> <目标玩家>
/townadmin vote create-mayor <小镇全名> <候选玩家>
/townadmin vote settle <voteId>
/townadmin vote cancel <voteId> <原因>
```

普通治理通过玩家 Dialog 完成。投票创建时冻结活跃选民快照；踢人要求赞成票严格超过 50%，强制更换镇长要求达到 2/3。到期投票由后台任务幂等结算。

### 领地

```text
/townadmin land preview <小镇全名>
/townadmin land reconcile <小镇全名|all> [repair]
/townadmin land rebuild <小镇全名|all>
```

`preview` 只能在游戏内执行。`reconcile` 默认只检查，在目标后填写 `repair` 才修复；`rebuild` 会移除后重建投影，因此发出命令后还需点击聊天栏确认按钮。确认前若目标小镇发生变化，操作会中止并要求重新发起。

### 公共资金、税率与扩张

```text
/townadmin money view <小镇全名>
/townadmin money adjust <小镇全名> <带符号金额> <原因>
/townadmin money reconcile
/townadmin tax set <小镇全名> <百分比> <原因>
/townadmin ledger view <小镇全名>
/townadmin expand view|preview <小镇全名> [方向]
```

税率以基点保存，同一税率用于 QuickShop、Jobs 和 GlobalMarketPlus 玩家收入，金额按 Vault 经济实现支持的精度处理。公共账本不限制历史时间。`money reconcile` 比较清算账户和内部总账；出现短款时会锁定新的公共资金消费，但账本查询、捐款和再次对账仍可使用。管理员调账必须填写原因。

### 公共 Buff

```text
/townadmin buff list <小镇全名>
/townadmin buff grant <小镇全名> <buffKey> <原因>
```

Buff 目录位于 `config.yml` 的 `phase4` 配置节。公共 Buff 不受世界限制，购买时可选 1 小时、1 天、1 周或 1 月，长期档按比例折扣且购买后不接受退款。效果、每小时价格和角色权限在启动时校验，修改后需要重启；`shop-enabled` 开关可通过 `reload` 热更新。

### 领地加成

建筑返还与领地信标位于 `config.yml` 的 `phase5` 配置节。建筑返还以 25% 概率处理生存模式成员在本镇有效 Residence 内放置的安全单方块，成功时只播放拾取音效，不展示内部额度。镇长或副镇长切换信标效果时，系统记录数据库中尚未存在或等级更高的效果，不再扫描区块中的信标。

## 玩家入口

插件不注册任何玩家命令。玩家通过讲台服务台、小镇手册、Dialog 和可点击聊天表单操作。点击捐款按钮后可直接在聊天栏输入自定义金额。入镇采用申请制：玩家同时最多申请 3 个小镇，申请 48 小时有效；被拒绝后 24 小时内不能再次申请同一小镇，主动退出后 24 小时内不能申请新镇。每镇最多 1 名镇长、3 名副镇长，镇员不限；镇长和副镇长拥有相同管理权限，但解散小镇、领地扩张及副镇长任免仅限镇长。所有成员可查看完整公共资金流水和公共 Buff。规则或税率变更后，成员会在下次登录或打开主菜单时收到说明。

Buff 在登录、重生、跨世界、成员关系变化和到期清理时重新计算，Attribute Modifier 使用稳定 namespaced key。

领地预览按钮会传送至领地中心传送点，并显示持续刷新的火焰粒子边界。扩张以初始 3×3 区块为一个固定单元，只能向相邻方向扩张，最多占用原点周围的 3×3 单元网格；价格按配置中的指数规则向上取整并从公共资金扣除。

成员在自己小镇领地内放置未列入黑名单的普通建筑方块时，可能收到一个同种方块返还，操作栏会显示本周用量。有效小镇信标的原版效果会覆盖整个小镇领地；玩家无需额外领取。

管理命令使用小镇全名定位目标。申请人另行填写仅含 `1~12` 个英文字母的领地名称（建议三个字母），该名称转为小写后直接作为 Residence 名称，例如 `SKY` 生成 `sky`。
