# TianjiTown

天际服自用的单 Paper 服务器小镇系统。当前版本为第 1 阶段生产候选版 `1.0.0`，提供申请、选址、审批、基本资料、初始 3×3 领地和最小成员体系。税收、公共账本、付费扩张、投票、Buff、资源采购和领地加成尚未开放，也不会出现在 UI 或命令帮助中。

## 构建

要求 Maven 3.9+ 与 JDK 25+：

```bash
mvn -B clean verify
```

唯一安装包输出为 `tianjitown-paper/target/TianjiTown-1.0.0.jar`。Paper API、Residence、Vault 与可选的 WorldGuard API 使用 `provided` scope，不会打入插件 JAR；HikariCP、Flyway 和 SQLite JDBC 会合并到最终 JAR。

## 安装与 SQLite

1. 安装并启用 Residence、Vault、XConomy 和 QuickShop-Hikari，确保 Vault 已注册可用的 `Economy` 服务。推荐同时安装 WorldGuard；安装后选址会检查 3×3 领地及其区块缓冲范围是否接触 WorldGuard 区域。插件不再校验固定的 Minecraft、Java 或依赖版本，但必需依赖未启用时仍会锁定写功能。
2. 将 JAR 放入 `plugins`，首次启动会自动创建 `plugins/TianjiTown/tianjitown.db` 并执行 Flyway 迁移。
3. 如需更改位置，在 `config.yml` 中设置相对或绝对文件路径：

```yaml
database:
  file: 'tianjitown.db'
  connection-timeout-ms: 5000
  busy-timeout-ms: 5000
```

4. 按实际地图修改 `phase1.site.service-areas` 和 `blacklist`，重启后执行 `/townadmin status`。状态为 `READY` 时才开放服务台。

SQLite 采用单连接串行写入、WAL、外键约束和 5 秒忙等待，无需额外数据库服务。数据库文件位置和超时参数只在插件重启后生效。

> 此 SQLite 版不会自动导入旧 MySQL 数据。已经在 MySQL 中运行的服务器应先保留完整备份，在隔离环境完成数据转换和验收后再切换；不要把旧 MySQL Flyway history 复制到 SQLite。

详细发布和验收流程见 [`docs/phase-1/README.md`](docs/phase-1/README.md)，备份与恢复见 [`docs/phase-0/SQLITE_AND_BACKUP.md`](docs/phase-0/SQLITE_AND_BACKUP.md)。

## 管理员帮助

`/townadmin` 或 `/townadmin help` 显示精简分类。使用 `/townadmin help <分类>` 查看完整语法，可用分类为 `system`、`station`、`application`、`town`、`member`、`land` 和 `phase0`。命令参数支持 Tab 自动补全；补全列表中的 `<原因>` 等尖括号内容只是当前位置的参数提示，必须替换为实际内容，不能原样提交。

### 系统与运维

```text
/townadmin status
/townadmin reload
/townadmin maintenance <on|off|status>
/townadmin audit [1~200]
```

`reload` 只重读可热更新的配置；SQLite 文件和超时参数需重启。维护模式会暂停服务台、手册、玩家 GUI 和表单提交，不会移除现有 Residence 保护。

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
/townadmin mayor transfer <小镇全名> <玩家> <原因>
```

发出删除命令后，聊天栏会显示“确认执行”和“取消”按钮；确认仅限发起者使用，60 秒后失效。删除操作会先安全归档；只有 Residence 确认移除后才释放名称、领地名称和区块占位，审计记录会保留。

### 领地

```text
/townadmin land preview <小镇全名>
/townadmin land reconcile <小镇全名|all> [repair]
/townadmin land rebuild <小镇全名|all>
```

`preview` 只能在游戏内执行。`reconcile` 默认只检查，在目标后填写 `repair` 才修复；`rebuild` 会移除后重建投影，因此发出命令后还需点击聊天栏确认按钮。确认前若目标小镇发生变化，操作会中止并要求重新发起。

### 第 0 阶段预发验证

```text
/townadmin phase0 status
/townadmin phase0 residence-smoke <world> <chunkX> <chunkZ> <memberUuid>
```

冒烟验证必须拥有 `tianjitown.admin.phase0` 权限，并且只能在配置中允许的专用预发世界执行。命令通过前置检查后会显示聊天栏确认按钮，点击确认才会执行写入测试。

## 玩家入口

插件不注册任何玩家命令。玩家通过讲台服务台、小镇手册、箱子 GUI 和可点击聊天申请表操作；镇长资料编辑使用书本 UI。入镇采用申请制：玩家同时最多申请 3 个小镇，申请 48 小时有效；被拒绝后 24 小时内不能再次申请同一小镇，主动退出后 24 小时内不能申请新镇。镇长可在主界面审批入镇申请或通过二次确认解散小镇。

领地预览按钮会传送至领地中心传送点，并显示持续刷新的火焰粒子边界。

管理命令使用小镇全名定位目标。申请人另行填写仅含 `1~12` 个英文字母的领地名称（建议三个字母），该名称转为小写后直接作为 Residence 名称，例如 `SKY` 生成 `sky`。
