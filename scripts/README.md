# XConomy SQLite 升级测试数据

`create_xconomy_sqlite.py` 使用 Python 3 标准库，生成适配 XConomy 2.26.3 的独立配置目录和 SQLite 数据库。只创建一个旧结算账户，不读取或修改正式服数据库，也不覆盖已有目录。

```powershell
python scripts/create_xconomy_sqlite.py ./upgrade-fixture/XConomy --xconomy-jar ./LocalTestServer/fix-20260912/plugins/XConomy-Paper-2.26.3.jar --name tax --balance 15000.00
```

将余额替换为旧 XConomy 结算账户的实际余额，单位为元（或服务器货币单位），不是 TianjiTown 的 `_minor` 分值。名称必须保留准确大小写，例如 `Tax`。默认 UUID 按该名称计算离线 UUID；测试服若使用其他身份映射，追加 `--uuid 实际UUID`，必须与测试服解析的旧账户 UUID 一致。

1. 停止测试服，在独立环境放入旧 TianjiTown 文件夹（包含完整且健康的数据库及其 WAL），保持 `database.file` 指向正确文件、`economy.settlement-account` 为原账户名。
2. 将生成的 `XConomy` 目录放入测试服 `plugins/`，使用真实的 XConomy 2.26.3 插件。脚本从指定 JAR 提取完整默认配置，设置 SQLite、Default UUID 模式、初始余额 0 并关闭同步等可选 enable 开关。不要混入正式服 MySQL 配置。
3. 首次迁移应使用尚未迁移的 TianjiTown 副本，不混入其他测试的 `settlement-account-migration.properties`。已迁移副本应另行测试恢复，不能用这个仅含旧账户的数据库冒充配套数据。
4. 在维护窗口暂停所有相关经济活动，无玩家连接时启动新版 TianjiTown，检查 schema `1.1`、账户改名、UUID 和余额保持不变；再次重启确认不重复迁移。

只模拟旧账户适合验证账户改名与结算余额检查，不能还原玩家余额或覆盖退款、玩家交易、MySQL 后端及跨服同步测试。Residence 等依赖数据仍需按具体测试配套准备。
