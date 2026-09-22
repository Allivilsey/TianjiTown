# 依赖升级检查清单

每次升级 Paper、Java、Residence、Vault、XConomy、QuickShop-Hikari、WorldBorder、Jobs、GlobalMarketPlus 或可选 HuskSync 前，在隔离环境逐项完成：

- [ ] 记录当前 JAR 版本、SHA-256、配置和数据文件位置。
- [ ] 创建 TianjiTown、Residence、QuickShop H2、玩家经济数据同时间点备份并验证恢复。
- [ ] 使用生产数据副本启动，确认配置内容和当前初始数据库结构校验通过；不引入配置版本号或旧开发数据库升级流程。
- [ ] Residence：建镇、扩张、多区域边界、成员权限、系统 owner、命令防护、信标位置判断和对账均通过。
- [ ] Vault/玩家经济插件：在线和离线玩家扣款、退款、重启持久化和金额精度均通过。
- [ ] QuickShop：版本能力门禁、出售/收购实际收款方、税率、taxer、成功/失败/回滚事件及税款、补贴入账均通过。
- [ ] QuickShop 小镇交易直接扣税、无 tax 入账；升级前解除其他插件对旧税收账户的引用，确认一次性清理结果及完成标记。
- [ ] Paper/Java：插件启动、异步数据库门禁、信标提交权限、原生光束适配（getBlockEntity/getBeamSections）、区块停止与恢复、BlockPlace/BlockMultiPlace 和药水效果自然到期均通过。
- [ ] WorldBorder：矩形与椭圆边界、各世界独立配置及缓冲距离检查保持正确；未安装、未配置或 API 异常时安全失败。
- [ ] 完整执行申请 → 治理 → 税收/账本 → 扩张 → Buff → 领地加成玩家路径，核对账户、成员、领地和效果记录一致。
- [ ] Jobs：核验 `BufferedEconomy.economy` 的付款委托及 `BufferedPaymentTask` 工资调用链，成功工资仅收一次税，取消/失败工资和服务器税不收小镇税；小镇热重载后无残留或重复观察器。
- [ ] GlobalMarketPlus：核验 `Transaction-After-Taxes` 的真实语义，零售累计税、批发税率、上架税分摊、拍卖补税/退款与小镇税基一致；同时检查 `Change-Balance-Only-When-Online` 离线延迟发款及 GMP 成功事件的限制。
- [ ] 三种收入共享补贴额度，税款、补贴、账本和额度在同一数据库事务提交；失败回滚，重试不重复扣款。
- [ ] 可选 HuskSync：同步完成后刷新有效属性、清理过期修饰符，详见 [Buff 验收](../deployment/BUFFS_AND_RESOURCES.md)。
- [ ] 执行 `/tianjitown diagnose`，报告无新增差异并完成隔离恢复。
- [ ] 保留旧依赖/JAR 和整套备份，明确回滚负责人、窗口与判定条件。

QuickShop 税务适配器当前支持不低于 `6.3.0.0` 的版本，但仍会通过事件和交易账户 API 能力检查；版本或接口不匹配时会主动关闭动态税。不得通过修改版本字符串绕过能力门禁。
