# 版本锁定策略

## 编译锁

| 组件 | 锁定值 | 说明 |
|---|---|---|
| Java bytecode | 21 | `maven.compiler.release=21` |
| Maven | 3.9+ | Enforcer 校验 |
| Paper API | `1.21.8-R0.1-20250906.215025-55` | 使用唯一快照版本，避免漂移 |
| Residence compile API | `6.0.0.1` | 公开 Maven 最接近版本；运行时必须严格等于生产锁 `6.0.1.1` |
| Vault API | `1.7` | `provided` |
| HikariCP | `5.1.0` | 打入生产 JAR |
| Flyway | `10.22.0` | core + mysql，打入生产 JAR |
| MySQL Connector/J | `8.4.0` | 打入生产 JAR |
| SnakeYAML | `2.3` | 打入生产 JAR |

Residence `6.0.1.1` 没有可解析的公开 Maven tag/artifact，不能假装做到了源码级精确锁定。取得生产 JAR 后，应将它安装到服内私有 Maven 仓库并把 `residence.api.version` 改为 `6.0.1.1`，随后重新跑 CI 与预发冒烟。

## 升级规则

任何 Paper/Leaf、Java、Residence、Vault、XConomy 或 QuickShop 升级都必须：

1. 更新本文与插件 `config.yml` 的运行版本锁；
2. 在独立预发服完成依赖启动、MySQL/YAML 自检和 Residence 冒烟；
3. 确认 TianjiTown 数据库为空白初始化，且只管理自己创建的 `tt_` Residence；
4. 生成新的构建哈希和验收记录后才进入生产。
