# 15.4 2026-08-21 清单补全与并发边界轮次

```text
测试轮次：testlist-20260821-completion
分支 / 提交：main / 751943099b134d46144465d2c492c307e3fb0582（保留测试前已有工作区改动；本轮仅修改本清单并新增忽略的本地驱动脚本与证据）
项目版本 / JAR SHA-256：1.4.0 / 4BCA0EBA8D20001E820C404D1685D14B8C47D0B955E8E53FB50336A96FF82A7F
配置 schema / Flyway schema：7 / 7.0（10 条成功迁移）
Maven / Paper / Java：3.9.12 / 26.2-84 / 25.0.4 LTS
MinecraftConsoleClient：build 505；全部实例关闭 SessionCache/ProfileKeyCache
测试服务器 / 时区：LocalTestServer/codex-fresh-20260821 隔离副本 / Asia/Shanghai
执行人 / 复核人：Codex / 待复核
开始 / 结束时间：2026-08-21 19:39 / 2026-08-21 20:45

自动化构建结果
Maven clean verify 四模块全部成功；Core 17、Storage 18、Integrations 11、Paper 48，共 37 个测试类、94 个测试，失败/错误/跳过均为 0。候选 JAR 与既有基线哈希一致。

本轮新增完整通过（26 项）
APP-10、APP-11、APP-19；MEM-02、MEM-04、MEM-05、MEM-09、MEM-10、MEM-11；GOV-02、GOV-03、GOV-04、GOV-05、GOV-06；ECO-01、ECO-02、ECO-04、ECO-05；LAND-01、LAND-02；BUFF-01、BUFF-02；TC-01、TC-05、TC-07、TC-11。

本轮失败（3 项）
APP-04：大小写混合 Residence 名 AbCdEf 被接受，town_applications.residence_name 仍按原大小写持久化，未满足“小写存储”。
GOV-01：副镇长可发起 REPLACE_MAYOR，但现任镇长失败并返回“你当前不在本次投票的活跃选民范围内”；实现先从快照排除现任镇长，再要求发起人在快照中，使镇长路径不可达。
LAND-03（P0）：核心规则声明 5×5、grid=-2～2，但 Storage prepareExpansion 仍按 abs(grid)>1 拒绝，V3 表约束也仍限定 -1～1；实际扩张至 -2 返回“目标超出 3×3 扩张网格”。

当前缺陷
TT-TEST-20260821-01：MEM-12 玩家资料入口可修改锁定的全名和简称。
TT-TEST-20260821-03：APP-04 Residence 名未以小写持久化。
TT-TEST-20260821-04：GOV-01 现任镇长无法发起 REPLACE_MAYOR。
TT-TEST-20260821-05（P0）：LAND-03 核心 5×5 规则与 Storage/迁移 3×3 约束不一致。

重要运行证据
MEM-02：第 4 份待处理入镇申请被拒；撤回后可申请第 4 镇；超过 48 小时的申请不计入名额且不能审批。
GOV-03：同镇两个身份并发创建不同类型投票时恰好一个成功；另一镇同时创建成功。GOV-04 冻结快照只包含 30 天活跃窗口内且入镇满 7 天的 3 个 UUID；窗口外、从未活动和未满 7 天成员均被排除。GOV-06 中新成员不能投，已离镇的原快照成员仍可投，eligible_voters/required_yes 不变。
ECO-01：三笔有效捐款分别为 101/202/303 minor units，Vault、清算账户、小镇余额和三条唯一 business_key 流水等额变化；ECO-02 的非法金额、余额不足、非成员与 long 边界均无余额或账本副作用。
APP-19：SITE_SELECTED 预留在撤回后写入 released_at；24 小时边界前拒绝、边界后恢复创建。BUFF-02 覆盖归档镇、商店关闭、消费关闭、账户锁定、余额不足及数据库不可用，均未扣款或创建效果。TC-01 三种门禁依次得到 TEST_INTERFACE_DISABLED、PERMISSION_DENIED、SYSTEM_NOT_READY。

部分执行但保持未勾选
APP-03：格式码、MiniMessage、空/51 条规则均稳定拒绝；MCCC 256 字符协议不能可靠发送全部超长、控制字符与嵌入换行输入。
APP-05：大小写和 NFC 等价的 Å/Å 并发最多一份成功；空格差异受 TestCommand 参数模型限制。兼容形式 ATown/ＡTown 均可创建，因清单未明确要求 NFKC，本轮记录为规范澄清项而不判产品失败。
MEM-01：数据库状态与重复申请已验证，但领导通知未取得独立客户端证据。LAND-04 仅验证第 2～4 单元价格公式。TC-06 已确认 UI 与 TestCommand 共用 TownActions，但未完成同一业务的 UI 结果逐项对照。

证据位置
各模块 target/surefire-reports；LocalTestServer/validation/mccc-completion-*.log；backups/testlist-20260821-completion/paper-completion-main.log、paper-system-not-ready.log、tianjitown-before.db、config-before.yml、res-world-before.yml。

环境收尾与状态统计
Paper、MinecraftConsoleClient 和 25565 监听均已停止。TianjiTown SQLite、config.yml、Residence res_world.yml 已恢复为测试前 SHA-256：42A68EF84F5260B04BD2761622DE50469FE851C3D89291290759DF1E51769963、DCAD09006304093911E93FCE4B76DB6425D1DC3985EC669675BBAD34D5F37455、30EA6F7707375D1F752C3ADA990A77AD93B65EEC7DA0671B723F6E108B5B1949；quick_check=ok、外键违规 0。数据库不可用测试产生的临时库已移入本轮备份目录，未留在服务器运行目录。
testlist.md 当前：通过 54 / 失败 4 / 阻塞 0 / 未执行 100。与 manualtest.md 合计：通过 55 / 失败 4 / 阻塞 0 / 未执行 184。
最终结论：NO-GO。构建和本轮 26 项完整用例通过，但 LAND-03 为未关闭 P0，另有 APP-04、MEM-12、GOV-01 三项失败，且迁移恢复、故障注入、依赖矩阵和人工界面项目尚未完成，不能批准生产发布。
```

> 本报告从 [自动化、命令与运维测试列表](../testlist.md) 分离，证据路径均相对于项目根目录。
