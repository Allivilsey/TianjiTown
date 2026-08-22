# 测试资料导航

版本库中的测试资料集中存放于此目录：

- [自动化、命令与运维测试列表](testlist.md)：`AUTO`、`CMD` 与 `OPS` 测试项、执行模板和报告索引；
- [人工测试列表](manualtest.md)：需要真人观察或操作的 `MANUAL` 测试项；
- [历史测试报告](reports/)：按测试轮次拆分的独立报告；
- [TestCommand 自动化接口](test-command.md)：隔离测试服务器使用的自动化接口说明。

各 Maven 模块的自动化测试源码继续保留在模块标准的 `src/test` 目录，确保 Maven 能自动发现并执行。`LocalTestServer`、`MinecraftConsoleClient` 与 `backups` 是本机测试环境及证据目录，不纳入版本控制，报告中的路径仍以项目根目录为基准。
