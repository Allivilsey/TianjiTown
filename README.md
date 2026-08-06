# TianjiTown

天际服自用的单 Paper 服务器小镇系统。当前版本为第 1 阶段生产候选版 `1.0.0`，提供申请、选址、审批、基本资料、初始 3×3 领地和最小成员体系。税收、公共账本、付费扩张、投票、Buff、资源采购和领地加成尚未开放，也不会出现在 UI 或命令帮助中。

## 构建

要求 Maven 3.9+ 与 JDK 25+：

```bash
mvn -B clean verify
```

唯一安装包输出为：

```text
tianjitown-paper/target/TianjiTown-1.0.0.jar
```

Paper API、Residence 与 Vault API 使用 `provided` scope，不会打入插件 JAR。运行时库、Flyway 迁移和 MySQL 驱动会合并到最终 JAR。

## 第 1 阶段部署

1. 阅读 [`docs/phase-1/README.md`](docs/phase-1/README.md)。
2. 按需配置各世界的服务区、黑名单和依赖版本锁，并设置 `TIANJITOWN_DB_URL`、`TIANJITOWN_DB_USER`、`TIANJITOWN_DB_PASSWORD`。
3. 先在生产同版本预发服安装，执行 `/townadmin status`，再完成申请到建镇的验收闭环。
4. 只有预发验收、备份和回滚演练通过后才可部署生产服。

插件不注册任何玩家命令。玩家通过讲台服务台、小镇手册、箱子 GUI 和可点击聊天申请表操作；镇长资料编辑仍使用书本 UI。

管理员命令使用小镇全名定位目标。申请人另行填写仅含 `1..12` 个英文字母的领地名称（建议三个字母），该名称转为小写后直接作为 Residence 名称，例如 `SKY` 生成 `sky`。审批入口仅保留 `list`、`approve`、`reject`、`change`，管理员还可在小镇主菜单中直接处理待审核申请。
