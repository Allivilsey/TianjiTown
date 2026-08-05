# MySQL 与备份/恢复方案

## 已锁定值

| 项目 | 建议值 |
|---|---|
| 数据库 | `tianjitown`（服主已确认） |
| 应用账户 | `tianjitown_app`，仅允许游戏服来源地址（服主已确认） |
| 连接池 | maximum `6`，minimum idle `1`，连接超时 `5s`（服主已确认） |
| 字符集/时区 | `utf8mb4` / `Asia/Shanghai` |
| Flyway | 插件启动时先 validate 再 migrate；禁止 clean |
| 备份 | 每日一次，保留 14 个日备份、8 个周备份；每次上线前额外备份（服主已确认） |

应用账户最小权限：目标 schema 上的 `SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES`。不要授予全局权限、`DROP`、`GRANT OPTION`、`FILE` 或用户管理权限。备份使用独立只读账号，额外按实际对象授予 `SHOW VIEW, TRIGGER, EVENT`。

示意 SQL（由 DBA 替换来源主机并交互设置密码）：

```sql
CREATE DATABASE tianjitown CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'tianjitown_app'@'<paper-host>' IDENTIFIED BY '<interactive-secret>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
ON tianjitown.* TO 'tianjitown_app'@'<paper-host>';
```

凭据只通过环境变量注入，不写入仓库。提供的插件配置中存在明文数据库凭据，上线前应轮换相关密码。

## 备份与恢复演练

插件文件快照：

```bash
scripts/backup_phase0.sh /path/to/plugins backups
```

MySQL 使用权限为 `0600` 的 client 配置文件：

```bash
scripts/backup_mysql.sh /secure/mysql-client.cnf tianjitown backups/tianjitown.sql.gz
```

恢复必须在隔离 MySQL 实例演练：创建空库、解压导入、运行 `CHECK TABLE`、启动同版本预发服并执行 `/townadmin phase0 status`。生产回滚默认只回退 JAR/配置，不自动删除表或 Residence。
