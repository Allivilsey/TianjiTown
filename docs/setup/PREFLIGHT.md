# 安装与预发检查

当前正式版本为 `1.0.0`。配置不设版本号，Flyway 执行初始建表、增量升级与完整性校验，目标 schema 为 `1.1`。升级支持已发布 `V1_0` 创建且校验值一致的生产数据库；先按 [SQLite 手册](../operations/SQLITE_AND_BACKUP.md) 在备份副本中验证。

## 环境和依赖

构建要求 Maven 3.9+、JDK 25+，执行 `mvn -B clean verify`，产物为 `tianjitown-paper/target/TianjiTown-1.0.0.jar`。当前 `plugin.yml` 声明 API `26.2`；实际 Paper/Leaf 和 Java 组合必须支持该 API 与 Dialog，不能把“插件不写死版本匹配”理解为支持任意旧服务端。

| 依赖 | 用途与启动检查 |
|---|---|
| Residence | 领地创建、边界、成员及访客权限、保护对账 |
| Vault 与玩家经济插件（如 XConomy） | 玩家钱包扣款与退款；Vault 必须已注册可用 Economy provider |
| WorldBorder | 验证选址和扩张边界；需通过公开 API 能力检查 |
| QuickShop-Hikari | 商店收入税；税务适配要求至少 `6.3.0.0`，并检查事件及交易账户 API |
| Jobs | 职业收入税 |
| GlobalMarketPlus | 市场成交收入税 |
| HuskSync（可选） | 回服后在同步完成时校正公共 Buff |

除 HuskSync 外，上表中的插件都要安装并启用。必需插件以 `softdepend` 声明，便于 TianjiTown 在缺失时仍能显示诊断，不代表业务上可选。税务适配器还会单独检查版本/API 并报告接入状态；必需插件存在不等于其全部接口已通过。

## 首次安装

1. 准备独立预发服务器及空数据库路径，安装上述依赖，确认服务器进程对插件目录可写。
2. 用 WorldBorder `/wb` 为开放选址的世界配置边界；整镇 5×5 单元网格，即 25×25 区块（625 区块）与默认一圈区块缓冲须完整位于边界内。中心 5×5 区块在建镇后自动激活，其余单元先预留。
3. 放入 TianjiTown JAR 并启动，生成 `config.yml`、`messages.yml` 和默认 `tianjitown.db`。Paper 首次需下载 `libraries` 声明的存储依赖；离线环境提前准备缓存。
4. 按 [配置说明](CONFIGURATION.md) 调整价格和开关。数据库路径、经济参数和商品定义等修改后重启。
5. 执行 `/tianjitown status`，检查启动状态和所有诊断细节。必要依赖、账户初始化及 SQLite 完整性检查失败会阻止启动；待恢复资金、领地差异、余额或历史诊断异常会告警并保留恢复入口，账户按异常范围隔离。`READY` 表示运行时可用，仍需核对诊断中的业务告警。
6. 在游戏内看向讲台执行 `/tianjitown station create`，测试服务台、手册及玩家 Dialog。

## LOCKED 时如何处理

先读取 `status` 和控制台中的具体失败项：依赖缺失/未启用、Vault 服务不可用、WorldBorder API 不可用、配置值非法、SQLite 不可访问、Flyway 校验失败或启动诊断异常。

启动失败时业务运行时尚未注册，不能依赖 `/tianjitown diagnose` 或 `/tianjitown reload` 重新启动初始化。修正原因后重启服务器。运行期间的 SQLite 写锁有恢复探测，见 [运维手册](../operations/OPERATIONS.md)。

不要通过清空已有数据库、删除 Flyway history 或修改校验记录绕过错误。已有开发数据库与当前初始结构不一致时，保留原文件及备份，在独立路径的新库或副本中验证；没有自动升级、自动回填或 MySQL 导入功能。

## 开放前验收

- 使用申请人、两名初始成员和管理员，完成成员确认、整镇 25×25 区块选址检查、审核和初始 5×5 区块的 Residence 创建；确认其余 24 个单元预留且禁止圈地。
- 验证入镇申请、退出、访客权限、投票、捐款、三种收入税、扩张与 Buff；检查失败操作没有重复扣款。
- 执行 `/tianjitown land reconcile all` 和 `/tianjitown diagnose`，保存结果。
- 按 [SQLite 备份手册](../operations/SQLITE_AND_BACKUP.md) 完成一次同时间点备份与隔离恢复。
- 运行环境事实报告可用 `python scripts/inspect_runtime.py /path/to/server --output reports/runtime-report.json` 生成；它只读，不参与启动判定。

按上述步骤及各功能部署文档完成人工验收，并将结果保存在本地或发布档案中。文档中的验收步骤不是已经通过的测试报告。
