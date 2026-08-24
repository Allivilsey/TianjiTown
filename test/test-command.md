# TestCommand 自动化接口

`/testcommand` 仅用于隔离的自动化测试服务器。它与 `/townadmin` 分离，也不会被玩家界面
通过命令转发调用。玩家 Dialog 和 TestCommand 都直接调用 `TownActions`。

## 启用条件

同时满足以下条件才可使用：

1. `config.yml` 中设置 `test-command.enabled: true`；
2. 命令发送者拥有 `tianjitown.testcommand` 权限；
3. 插件数据库门禁已进入 `READY` 状态。

默认配置关闭接口，专用权限的默认值也是 `false`。

## 命令格式

`actor` 可以是在线玩家名或 UUID。业务校验始终使用该玩家身份，与其从玩家界面点击时一致。

```text
/testcommand query <actor>
/testcommand action <actor> application create <name> <shortName> <residenceName> <description> <rule1|rule2> <initialMemberUuid1> <initialMemberUuid2>
/testcommand action <actor> application update <applicationId> <version> <name> <shortName> <residenceName> <description> <rule1|rule2> <initialMemberUuid1> <initialMemberUuid2>
/testcommand action <actor> application select-site <applicationId>
/testcommand action <actor> application submit <applicationId>
/testcommand action <actor> application cancel <applicationId>

/testcommand action <actor> join apply <townId>
/testcommand action <actor> join cancel <applicationId>
/testcommand action <actor> join approve <applicationId>
/testcommand action <actor> join reject <applicationId>

/testcommand action <actor> member role <townId> <targetPlayerId> <role>
/testcommand action <actor> member kick <townId> <targetPlayerId>
/testcommand action <actor> transfer request <townId> <candidatePlayerId>
/testcommand action <actor> transfer decide <transferId> <true|false>
/testcommand action <actor> rules acknowledge <townId> <revision>
/testcommand action <actor> vote create <townId> <voteType> <targetPlayerId>
/testcommand action <actor> vote cast <voteId> <true|false>

/testcommand action <actor> town profile <townId> <version> <name> <shortName> <residenceName> <description> <rule1|rule2>
/testcommand action <actor> town leave <townId>
/testcommand action <actor> town disband <townId> <version>
/testcommand action <actor> town expand <direction>

/testcommand action <actor> finance donate <amountMinor>
/testcommand action <actor> finance tax <townId> <basisPoints>
/testcommand action <actor> finance acknowledge-tax <revision>
/testcommand action <actor> buff buy <buffKey> <one_hour|one_day|one_week|one_month>
```

`amountMinor` 使用最小货币单位，`basisPoints` 使用基点。例如货币精度为 2 时，`10000`
表示 `100.00`；`500` 基点表示 `5%`。

## 稳定输出

命令只以单行机器结果表示最终业务执行状态：

```text
RESULT success=true action=JOIN_APPLY application_id=... status=PENDING town_id=...
RESULT success=false action=BUFF_BUY reason=INSUFFICIENT_BALANCE detail="小镇公共余额不足"
```

字段按名称排序；包含空格或换行的值使用带转义的双引号。失败原因使用稳定代码，`detail`
仅用于诊断，不应作为自动化断言的主要依据。
