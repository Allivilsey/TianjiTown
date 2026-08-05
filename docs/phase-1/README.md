# 第 1 阶段发布与验收

版本：`1.0.0`

本阶段开放申请、3×3 选址、管理员审批、YAML 基本资料镜像、初始 Residence 投影、邀请加入和普通成员主动退出。后续阶段功能没有入口。

## 发布前检查

1. 使用 JDK 25 和 Maven 3.9+ 执行 `mvn -B clean verify`，确认生成 `tianjitown-paper/target/TianjiTown-1.0.0.jar`。
2. 服务器版本必须与 `config.yml` 的版本锁完全一致；Paper API 编译产物需要在生产所用 Leaf/Paper 版本上验证。
3. 确认 Vault 已注册可用的 XConomy `Economy` 服务，Residence、Vault、XConomy 和 QuickShop-Hikari 均已启用。
4. 为 TianjiTown 使用独立空 MySQL schema，应用账户只授予该 schema 所需权限；连接池上限保持 6。
5. 按实际地图修改 `phase1.site.allowed-worlds`、`service-areas`、`blacklist` 和 `minimum-buffer-chunks`。默认坐标只是示例，不可直接用于生产。
6. 使用现有阶段 0 脚本备份 Residence、QuickShop H2、XConomy/清算账户和 TianjiTown MySQL，并完成一次隔离恢复演练。

## 安装或升级

1. 关闭新建镇入口并停止服务器。
2. 备份插件目录和数据库；不要删除已有 Flyway history。
3. 替换插件 JAR，保留并人工合并现有 `config.yml`，然后启动服务器。
4. Flyway 会先保留阶段 0 门禁表，再创建阶段 1 业务表。禁止手工修改 Flyway history。
5. 执行 `/townadmin status`。只有状态为 `READY` 时才开放服务台。
6. 在专用空区块执行 `/townadmin phase0 residence-smoke <world> <chunkX> <chunkZ> <memberUuid> --confirm-empty-chunk --confirm-preproduction`，完成第 0 阶段 Residence 闭环。
7. 用 `/townadmin station create` 将看向的讲台注册为服务台；用 `/townadmin handbook <player>` 做手册发放测试。

## 玩家验收

使用两个无管理员权限的测试玩家和一个管理员，在专用预发区域完成：

1. 玩家从服务台领取手册，分别通过服务台和手册打开相同主菜单。
2. 玩家用书本填写名称、简称、简介和规则，选择当前区块，看到 3×3 粒子边界并确认提交。
3. 管理员依次验证 `application list/view/review/changes/reject`；重新申请后验证 `approve`。
4. 批准后同时核对 MySQL、`plugins/TianjiTown/towns/<uuid>.yml`、镇长成员记录和 `tt_<townId>_0_0` Residence。
5. 镇长从 GUI 邀请第二名玩家；第二名玩家阅读完整资料和规则后加入，再主动退出。
6. 检查成员列表分页入口、镇长修改简介/规则、YAML 自动重新导出，以及 `/townadmin audit` 审计记录。
7. 确认玩家没有 `/town` 等命令，菜单和 `/townadmin help` 中也没有 money、tax、buff、order、vote 或 expand。

## 并发与故障验收

1. 两名管理员同时批准同一申请，最终只能有一个小镇、一个镇长记录、一个领地单元和九个区块占位。
2. 两名玩家同时预留重叠选址，只能有一人成功。
3. 快速重复点击提交、接受邀请和批准按钮，不能产生重复数据。
4. 临时停止 MySQL：新申请、审批、成员变更和 YAML 导入必须被锁定，已有 Residence 继续保护。恢复 MySQL 后最多等待 30 秒，写操作应自动恢复。
5. 临时移除测试镇 Residence 后执行 `/townadmin land reconcile <townUuid> --repair`；投影应按数据库边界和成员权限重建。
6. 重启服务器，核对申请、预留、小镇、成员、YAML 和 Residence 恢复，并确认启动后的自动对账没有触碰非 `tt_` 领地。

## YAML 维护

- 校验：`/townadmin data validate <townUuid>`
- 导出：`/townadmin data export <townUuid|all>`
- 导入：停用新建镇入口后编辑允许字段，再执行 `/townadmin data import <townUuid> --confirm`

导入会检查 schema、town UUID、revision、创建时间、名称唯一性和文本限制，先备份原 YAML，再更新 MySQL 并生成新 checksum。启动发现 YAML 变化时只告警和标记待确认，不会覆盖 MySQL。

## 回滚

1. 关闭服务台或停止服务器，备份当前 MySQL、YAML 和 Residence。
2. 回退 JAR 与配置；不要删除阶段 1 表、YAML 文件或 Residence。
3. 若旧版本无法读取向前迁移后的 schema，保持插件停用并恢复整套预发备份，不得只回滚部分表。
4. 生产回滚前必须先在隔离环境验证。阶段 1 已创建的小镇继续依赖 Residence 保护，禁止手工批量删除 `tt_` 领地。
