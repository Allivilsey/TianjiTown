# 第 0 阶段门禁

## 当前结论

阶段 0 门禁已经在生产同版本测试服闭环：四模块 Maven 工程、可重现单 JAR、单元测试、依赖/版本/Vault Economy 自检、异步 MySQL/Flyway 自检、YAML 镜像自检，以及带预发双重开关的 Residence API 冒烟测试均已通过。

本项目按新周目空数据启动设计：首次安装使用独立空业务 schema，所有小镇、成员、名称和领地关系均由 TianjiTown 重新建立。

## 已知生产基线（历史证据）

证据来自 QuickShop-Hikari 诊断文件，生成时间为 `2025-11-18 20:10:57`，不是当前运行版本证明：

| 组件 | 历史版本 |
|---|---|
| Minecraft / Leaf | `1.21.8` / `1.21.8-141-e11accb` |
| Java | `21.0.6` Oracle GraalVM |
| Residence | `6.0.1.1` |
| Vault | `1.7.3-b131` |
| XConomy | `2.26.3` |
| QuickShop-Hikari | `6.2.0.10` |

历史证据中的服务端基线已经过期。当前生产基线为 Minecraft/Paper `26.2-84`、Java 25；TianjiTown 针对 Paper API `26.2.build.84-stable` 编译，不引用服务端内部 API。

26.2 预发启动确认 Residence `6.0.1.1` 无法与当前 CMILib 配合启用，因此运行锁已更新为 Residence `6.0.2.4`。Vault、XConomy 和 QuickShop-Hikari 暂保持既有锁定版本；QuickShop-Hikari `6.2.0.10` 可以启动，但会提示 26.2 未经其开发者完整验证。

## 运行门禁

插件硬依赖 `Vault`、`Residence`、`QuickShop-Hikari`，并额外确认：

- Minecraft、Java 和插件实际版本等于锁定值；
- Vault 已注册且启用了 `Economy` provider；
- XConomy 实际启用并与锁定版本一致；
- MySQL 可连接、`SELECT 1` 正常、Flyway 校验与迁移成功；
- YAML schema、checksum、revision 和原子替换往返验证成功。

数据库自检在线程池异步执行。任一门禁失败时状态为 `LOCKED`，不开放业务写入。

## Residence 预发冒烟

先在插件配置中设置：

```yaml
phase0:
  allow-residence-smoke: true
  residence-smoke-world: '专用预发世界名'
```

确认目标区块为空后执行：

```text
/townadmin phase0 residence-smoke <world> <chunkX> <chunkZ> <memberUuid> --confirm-empty-chunk --confirm-preproduction
```

测试依次验证无碰撞、创建、边界读取、成员 `build` 权限、删除、重建、碰撞命中和最终清理。任何异常都会在 `finally` 中尝试清理 `tt_phase0_*` 测试领地。生产服必须保持 `allow-residence-smoke: false`。

## 上线前运行时闭环

- 首次安装门禁 JAR 时，由 `/townadmin phase0 status` 直接采集当前运行版本；无需提前取得 JAR 目录或日志路径。版本不一致时插件保持 `LOCKED`。
- 已于 2026-08-05 在 Paper `26.2-84`、Java `25.0.4` 的隔离测试服完成空库启动、依赖门禁、Flyway `0.1`/`1.0` 迁移、YAML 自检和 Residence 冒烟测试。
- 首次启动后 10 张业务表均为 0 行，阶段门禁记录为 1 行；Residence 冒烟领地已完整清理。
- TianjiTown 使用独立空数据库开始第一个周目。首次启动若发现业务表已有记录，应停止上线并人工确认数据库目标，不自动继续。
- MySQL 已采用默认锁定方案：数据库 `tianjitown`、应用账户 `tianjitown_app`、连接池上限 6、每日备份保留 14 份、周备份保留 8 份。

取得当前服务端目录后，可直接生成不可变 JAR 哈希和日志证据：

```bash
scripts/inspect_runtime.py /path/to/server --output reports/runtime-lock.json
```
