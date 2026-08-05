# TianjiTown

天际服自用的单 Paper 服务器小镇系统。当前仓库完成的是第 0 阶段上线门禁骨架，不包含玩家功能，不应直接部署到生产服。

## 构建

要求 Maven 3.9+ 与 JDK 21+：

```bash
mvn -B clean verify
```

唯一安装包输出为：

```text
tianjitown-paper/target/TianjiTown-0.1.0-SNAPSHOT.jar
```

构建只依赖 Paper API；Residence 与 Vault API 使用 `provided` scope，均不会打入插件 JAR。

## 第 0 阶段验证

1. 阅读 [`docs/phase-0/README.md`](docs/phase-0/README.md)。
2. 设置 `TIANJITOWN_DB_URL`、`TIANJITOWN_DB_USER`、`TIANJITOWN_DB_PASSWORD`。
3. 仅在生产同版本预发服安装 JAR，执行 `/townadmin phase0 status`。
4. Residence 写入冒烟测试必须在专用预发世界、空区块并双重确认后执行。

本插件没有注册任何玩家命令。

