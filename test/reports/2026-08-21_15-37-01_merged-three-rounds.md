# 15.2 2026-08-21 15:37:01 三次测试合并报告

> 时间标记为推测值：依据 `testlist-20260821-mccc-expanded-coverage` 的最后一批 Maven 与 MCCC 证据，最后证据约于 15:37:01 写入。

```text
共同测试基线
分支 / 提交：main / 751943099b134d46144465d2c492c307e3fb0582
项目版本 / JAR SHA-256：1.4.0 / 4BCA0EBA8D20001E820C404D1685D14B8C47D0B955E8E53FB50336A96FF82A7F
配置 schema / Flyway schema：7 / 7.0
Maven / Paper / Java：3.9.12 / 26.2-84 / 25.0.4 LTS
Residence / Vault / XConomy / WorldBorder：6.0.2.4 / 1.7.3-b131 / 2.26.3 / 1.19
QuickShop-Hikari / Jobs / GlobalMarketPlus：6.3.0.0 / 5.2.6.6 / 1.4.1.4
测试服务器 / 时区：15.2 使用 LocalTestServer 隔离副本；15.3～15.4 使用 LocalTestServer/codex-fresh-20260821 / Asia/Shanghai
数据副本或快照：codex-fresh-20260821；启动时迁移至 schema-7；15.3 使用 ActiveTown，15.4 使用新建 CovTownF；未执行恢复演练
执行人 / 复核人：Codex / 待复核

三轮测试范围与结果演进
一｜testlist-20260821-build-paper-smoke｜2026-08-21 11:20～11:25
范围：Maven clean verify、包内容检查、Paper 启动与迁移冒烟；未驱动 MinecraftConsoleClient 玩家客户端。
结果：完成 BLD-01～BLD-12、CFG-01、CFG-02，14 / 0 / 0 / 229（通过 / 失败 / 阻塞 / 未执行）；未发现新缺陷。证据：LocalTestServer/validation/codex-maven-verify-second.log；LocalTestServer/validation/codex-fresh-20260821-113149.out.log；LocalTestServer/validation/codex-fresh-20260821-113149.err.log；各模块 target/surefire-reports。

二｜testlist-20260821-mccc-command-coverage｜2026-08-21 13:16～13:40
范围：MinecraftConsoleClient build 505 驱动 TestBot、MemberBot、MemberTwo、OutsiderBot、JoinerBot、ApplicantBot、CandidateOne、CandidateTwo，覆盖真实登录、查询、权限拒绝、参数校验、建镇、WorldBorder 选址、申请/审批、角色、规则、税率、捐款、扩张、Buff、转让和投票快照。
结果：累计 19 / 2 / 0 / 222；发现 TT-TEST-20260821-01（MEM-12：town profile 合法路径需要 13 个参数，但校验要求 11，返回 INVALID_ARGUMENT）和 TT-TEST-20260821-02（GOV-10：达到 required_yes 后投票仍为 OPEN，SQLite 快照字段语义疑似错位）。完整用例未满足的项目保持未勾选。证据：LocalTestServer/validation/mccc-testbot.log；LocalTestServer/validation/mccc-testbot-run2.log；LocalTestServer/validation/mccc-memberbot-final.log；LocalTestServer/validation/mccc-outsiderbot-final.log；LocalTestServer/validation/mccc-joinerbot.log；LocalTestServer/validation/mccc-joinerbot-reconnect.log；LocalTestServer/validation/mccc-applicantbot.log；LocalTestServer/validation/mcc-paper-20260821-132036.out.log；LocalTestServer/validation/mccc-20260821-summary.md。

三｜testlist-20260821-mccc-expanded-coverage｜2026-08-21
范围：Maven verify、SQLite PRAGMA/Flyway、税率五档及边界、多角色捐款与非法金额、入镇申请/审批、副镇长上限与越权任免、四方向扩张、speed/health LEVEL_UP 上限、投票快照/唯一票/即时结算、转让、成员退出、镇长退出拒绝、多人/单人/陈旧版本解散及测试接口关闭门禁；测试身份为 OtherM4、OtherA5、OtherA6、JoinCov、GuestCov、MemberCov、FreeCov、CandCov、DeputyCov。
结果：新增通过 DB-01、GOV-07、GOV-10、BUFF-04、MEM-17，累计 24 / 1 / 0 / 218。CovTownF 的 DONATION=5 条、合计 1400 minor units；治理投票最终为 PASSED，15.3 的 GOV-10 OPEN 现象本轮未复现，保留历史证据待根因复核，不再作为当前 CMD 失败项。证据：LocalTestServer/validation/maven-auto-20260821.log；各模块 target/surefire-reports；LocalTestServer/validation/mccc-coverage-*.log；LocalTestServer/validation/mccc-coverage-followup-mayor.log；LocalTestServer/validation/mccc-coverage-governance-create.log；LocalTestServer/validation/mccc-coverage-vote-*.log；LocalTestServer/validation/mccc-coverage-transfer-*.log；LocalTestServer/validation/mccc-coverage-disband-*.log；LocalTestServer/validation/mccc-coverage-tc01-disabled.log。

合并后的最终状态
当前清单累计：通过 24 / 失败 1 / 阻塞 0 / 未执行 218
当前缺陷：TT-TEST-20260821-01（MEM-12：town profile 合法 13 参数仍被要求 11，资料修改无法完成）。TT-TEST-20260821-02 仅作为 15.3 历史观察保留，待根因复核。
仍未执行：多镇并发、故障注入、24/48 小时及长期窗口、依赖版本矩阵、迁移/恢复、WorldBorder 全边界以及 GUI/人工项目。
环境收尾：测试服务器、客户端和 Paper 进程均已停止；15.2 的临时全新服务器目录因运行环境禁止递归删除而保留，未被任何进程占用。
最终结论：三次测试已合并记录，覆盖范围逐轮扩展，但因 MEM-12 仍未修复且大量发布阻断项目未执行，继续保持 NO-GO，不能批准生产发布。
```

> 本报告从 [自动化、命令与运维测试列表](../testlist.md) 分离，证据路径均相对于项目根目录。
