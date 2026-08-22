# 15.1 2026-08-15 本地隔离服完整执行轮次

```text
测试轮次：testlist-20260815-complete
分支 / 提交：main / 2101313d5b98ac3e4fe92d99bad785f0a2c41d76
项目版本 / JAR SHA-256：1.4.0 / 5E2C037B0D28193C01DEA99ABA3F55BD9DFB07D294956F7580F47F34685DC8AF
配置 schema / Flyway schema：7 / 7.0
Maven / Paper / Java：3.9.12 / 26.2-84 / 25.0.4 LTS
Residence / Vault / XConomy / WorldBorder：6.0.2.4 / 1.7.3-b131 / 2.26.3 / 目标部署版本
QuickShop-Hikari / Jobs / GlobalMarketPlus：6.3.0.0 / 5.2.6.6 / 1.4.1.4
测试服务器 / 时区：LocalTestServer 隔离副本 / Asia/Shanghai
数据副本或快照编号：pre-test-snapshot、fresh schema-7、database-final-inspection
执行人 / 复核人：Codex / 待复核
开始 / 结束时间：2026-08-15 00:50 / 2026-08-15 02:23
通过 / 失败 / 阻塞 / 未执行：43 / 2 / 10 / 186
缺陷列表：TT-TEST-20260815-01 已复测关闭；新增 TT-TEST-20260815-02（MEM-12：玩家资料修改可改小镇全名，锁定字段未受保护）、TT-TEST-20260815-03（BUFF-09：已有 Buff 的小镇接纳在线新成员后未刷新 speed 效果）。阻塞项为 CFG-05、OPS-09、REL-05、REL-07～REL-12、REL-14，原因分别是缺少替代依赖版本、同时间点第三方备份、24～72 小时窗口、生产数据/旧包/生产环境。
日志、截图、SQL、余额与投影证据位置：LocalTestServer/validation/testlist-20260815-complete
最终结论：NO-GO；候选包可稳定 READY，构建与 43 项完整用例通过，但存在 2 个未关闭 P1 产品缺陷、P0 阻塞项和 186 项未完整执行，不能据此批准生产发布。
```

> 本报告从 [自动化、命令与运维测试列表](../testlist.md) 分离，证据路径均相对于项目根目录。
