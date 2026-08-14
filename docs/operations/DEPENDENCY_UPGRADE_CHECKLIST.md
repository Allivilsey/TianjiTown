# 依赖升级检查清单

每次升级 Paper、Java、Residence、Vault、XConomy、QuickShop-Hikari、WorldGuard 或 DailyTaxEconomy 前，在隔离环境逐项完成：

- [ ] 记录当前 JAR 版本、SHA-256、配置和数据文件位置。
- [ ] 创建 TianjiTown、Residence、QuickShop H2、XConomy 和清算账户同时间点备份并验证恢复。
- [ ] 使用生产数据副本启动，确认 config schema `5` 与 Flyway schema `5.0` 未被依赖升级改写。
- [ ] Residence：建镇、扩张、多区域边界、成员权限、系统 owner、命令防护、信标位置判断和对账均通过。
- [ ] Vault/XConomy：离线清算账户查询、存款、扣款、重启持久化和金额精度均通过。
- [ ] QuickShop：版本能力门禁、出售/收购实际收款方、税率、taxer、成功/失败/回滚事件、transaction metric 历史和统一诊断均通过。
- [ ] DailyTaxEconomy：确认 `taxer-vault`、非玩家账户和清理规则不会再次征收或清理 TianjiTown 清算账户。
- [ ] Paper/Java：插件启动、异步数据库门禁、Beacon 自定义范围/Potion Effect、BlockPlace/BlockMultiPlace 和区块卸载清理均通过。
- [ ] WorldGuard：服务区附近外部区域碰撞检查保持正确；未安装时只按文档降级边界检查。
- [ ] 完整执行申请 → 治理 → 税收/账本 → 扩张 → Buff → 领地加成玩家路径，并核对历史资源数据未变化。
- [ ] 执行 `/townadmin diagnose 7` 与 `/townadmin backup`，报告无新增差异并完成隔离恢复。
- [ ] 保留旧依赖/JAR 和整套备份，明确回滚负责人、窗口与判定条件。

QuickShop 税务适配器当前只启用已验证的 `6.2.0.10`。版本变化会主动关闭动态税；不得通过修改版本字符串绕过能力门禁。
