# 第 1 阶段发布与验收

版本：`1.0.0`

本阶段开放申请、3×3 选址、管理员审批、SQLite 基本资料、初始 Residence 投影、邀请加入/拒绝和普通成员主动退出。后续阶段功能没有入口。

## 发布前检查

1. 使用 JDK 25 和 Maven 3.9+ 执行 `mvn -B clean verify`，确认生成 `tianjitown-paper/target/TianjiTown-1.0.0.jar`。
2. 在生产所用 Leaf/Paper 与 Java 组合上验证 Paper API 编译产物；插件不再执行固定版本匹配。
3. 确认 Vault 已注册可用的 XConomy `Economy` 服务，Residence、Vault、XConomy 和 QuickShop-Hikari 均已启用。
4. 确认 `database.file` 指向 TianjiTown 专用的空 SQLite 文件，其父目录对服务器进程可写。
5. 按实际地图修改 `phase1.site.blacklist` 和 `minimum-buffer-chunks`。所有已加载世界均可选址，WorldGuard 区域、地图黑名单、世界边界和领地缓冲仍会被严格检查。
6. 使用现有阶段 0 脚本备份 Residence、QuickShop H2、XConomy/清算账户，并用 `scripts/backup_sqlite.sh` 备份 TianjiTown SQLite，完成一次隔离恢复演练。

## 安装或升级

1. 关闭新建镇入口并停止服务器。
2. 备份插件目录和数据库；不要删除已有 Flyway history。
3. 替换插件 JAR，保留并人工合并现有 `config.yml`，然后启动服务器。
4. Flyway 会先保留阶段 0 门禁表，再创建阶段 1 业务表。禁止手工修改 Flyway history。
5. 执行 `/townadmin status`。只有状态为 `READY` 时才开放服务台。
6. 在专用空区块执行 `/townadmin phase0 residence-smoke <world> <chunkX> <chunkZ> <memberUuid>`，再点击聊天栏确认按钮，完成第 0 阶段 Residence 闭环。
7. 用 `/townadmin station create|info|list|remove` 创建、核对并移除服务台；用 `/townadmin handbook <player>` 做手册发放测试。

从早期含 YAML 镜像的版本升级时，原 `plugins/TianjiTown/towns` 文件不会再被读取或改写。确认 SQLite 资料完整并保留一次备份后，可由管理员另行归档这些旧文件。`town_profile_sync` 表为停用遗留表，运行代码不再访问；不要手工改写 Flyway history。旧 MySQL 数据不会自动导入，必须在隔离环境另行转换。

## 玩家验收

使用两个无管理员权限的测试玩家和一个管理员，在专用预发区域完成：

1. 玩家从服务台领取手册，分别通过服务台和手册打开相同主菜单。
2. 玩家在聊天栏申请表中依次点击并填写小镇名称、简称、领地名称、简介和规则；检查每个项目的悬浮要求，选择当前区块，看到 3×3 三维粒子边界并确认提交。
3. 管理员依次验证 `application list/approve/reject/change <小镇全名>`，并验证管理员主菜单中的审核按钮会随待办状态改变外观。
4. 批准后同时核对 SQLite、镇长成员记录和 Residence；领地名称只允许 `1..12` 个英文字母并直接按小写生成，例如 `SKY` 生成 `sky`。建议使用三个字母，不添加任何前后缀，也不接受小镇 UUID 或旧格式。
5. 镇长从 GUI 邀请两名玩家；验证被邀请者会收到带音效的可点击聊天提示，再分别完成接受与拒绝，接受者最后主动退出。
6. 检查成员列表分页入口、镇长修改简介/规则，以及 `/townadmin audit` 审计记录。
7. 确认玩家没有 `/town` 等命令，菜单和 `/townadmin help` 中也没有 money、tax、buff、order、vote 或 expand。

## 并发与故障验收

1. 两名管理员同时批准同一申请，最终只能有一个小镇、一个镇长记录、一个领地单元和九个区块占位。
2. 两名玩家同时预留重叠选址，只能有一人成功。
3. 快速重复点击提交、接受邀请和批准按钮，不能产生重复数据。
4. 在预发副本中注入 SQLite 磁盘 I/O 或权限故障：新申请、审批和成员变更必须被锁定，已有 Residence 继续保护。恢复文件可写后最多等待 30 秒，写操作应自动恢复。
5. 临时移除测试镇 Residence 后执行 `/townadmin land reconcile <小镇全名> repair`；投影应按数据库边界和成员权限重建。
6. 重启服务器，核对申请、预留、小镇、成员和 Residence 恢复，并确认启动后的自动对账没有触碰 SQLite 未登记的外部领地。

## 维护模式

- 开启：`/townadmin maintenance on`
- 查询：`/townadmin maintenance status` 或 `/townadmin status`
- 关闭：`/townadmin maintenance off`

开启维护模式会暂停服务台、手册、玩家 GUI 和表单提交，但不会停用管理员命令或现有 Residence 保护。状态会写回 `config.yml`，重启后保持不变。

## 管理员常用操作

- 审批：`/townadmin application list|approve|reject|change <小镇全名> <原因>`
- 查看：`/townadmin town view <小镇全名>`
- 删除：`/townadmin town delete <小镇全名> <原因>`，随后点击聊天栏确认按钮
- 成员：`/townadmin member invite|add|remove <小镇全名> <玩家> <原因>`
- 镇长：`/townadmin mayor transfer <小镇全名> <玩家> <原因>`
- 领地检查/修复：`/townadmin land reconcile <小镇全名|all> [repair]`
- 领地重建：`/townadmin land rebuild <小镇全名|all>`，随后点击聊天栏确认按钮

Tab 补全中的 `<原因>` 是位置提示，必须替换为实际内容。危险操作的聊天确认仅限发起者使用，60 秒后失效；确认前若目标版本变化，操作会安全中止。删除操作先安全归档并保持名称、领地名称和区块锁定；只有对应 Residence 确认移除后才释放这些占位，同时保留小镇历史记录与审计记录。升级前已经归档的小镇默认继续锁定，可重新执行删除命令并点击确认完成安全释放。若 `ACTIVE` 小镇的 Residence 被外部删除，系统会阻止删除、通知删除来源，并立即触发投影对账恢复。

## 回滚

1. 开启维护模式或停止服务器，备份当前 SQLite、配置和 Residence。
2. 回退 JAR 与配置；不要删除阶段 1 表或 Residence。
3. 若旧版本无法读取向前迁移后的 schema，保持插件停用并恢复整套预发备份，不得只回滚部分表。
4. 生产回滚前必须先在隔离环境验证。阶段 1 已创建的小镇继续依赖 Residence 保护，禁止手工批量删除 SQLite 中登记的小镇领地。
