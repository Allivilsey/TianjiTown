# `messages.yml` 重复消息审计

审计日期：2026-09-03  
源文件：`tianjitown-paper/src/main/resources/messages.yml`

## 结论

- 共检查 1,540 个标量消息。
- 发现 63 组完全相同的消息，共涉及 154 个键；若每组只保留一个键，理论上可减少 91 个键。
- 建议优先合并 31 组：分页按钮、治理投票与其 tooltip 镜像、通用配置校验，以及 4 组明显的界面/输出重复。
- 其余完全相同项多数是不同业务状态、不同展示层级或不同日志用途。它们可以技术性合并，但会失去按场景单独定制文案的能力，因此不建议只为减少行数而合并。
- P1 与 P2 已于 2026-09-03 实施：默认消息已收敛到公共键，Java 调用方已改用公共键，并保留旧用户 `messages.yml` 键到新键的兼容迁移。

## P1：建议直接合并

### 1. 分页按钮（2 组，14 个键）

建议新增 `dialog.common.previous` / `dialog.common.next`，统一替换以下键：

- `dialog.votes.previous|next`（1064–1065）
- `dialog.town-members.previous|next`（1089–1090）
- `dialog.visitor.previous|next`（1129–1130）
- `dialog.join.previous|next`（1215–1216）
- `dialog.town-join.previous|next`（1229–1230）
- `dialog.admin.previous|next`（1242–1243）
- `dialog.ledger.previous|next`（1657–1658）

统一文案分别为 `&e上一页`、`&e下一页`。这两项的动作和展示含义完全一致。

### 2. 治理投票与 tooltip 镜像（21 组）

`dialog.tooltip.votes.*` 大量逐字复制 `dialog.votes.*`。建议保留 `dialog.votes.*` 为唯一来源，tooltip 调用直接读取同一个键。

| 保留键 | 删除/替换键 |
| --- | --- |
| `dialog.votes.type-kick` | `dialog.tooltip.votes.type-kick` |
| `dialog.votes.type-replace-mayor` | `dialog.tooltip.votes.type-replace-mayor` |
| `dialog.votes.status-open` | `dialog.tooltip.votes.status-open` |
| `dialog.votes.status-passed` | `dialog.tooltip.votes.status-passed` |
| `dialog.votes.status-rejected` | `dialog.tooltip.votes.status-rejected` |
| `dialog.votes.status-cancelled` | `dialog.tooltip.votes.status-cancelled` |
| `dialog.votes.approve` | `dialog.tooltip.votes.approve` |
| `dialog.votes.reject` | `dialog.tooltip.votes.reject` |
| `dialog.votes.cancel` | `dialog.tooltip.votes.cancel` |
| `dialog.votes.target` | `dialog.tooltip.votes.target` |
| `dialog.votes.voters` | `dialog.tooltip.votes.voters` |
| `dialog.votes.threshold` | `dialog.tooltip.votes.threshold` |
| `dialog.votes.tally` | `dialog.tooltip.votes.tally` |
| 新增 `dialog.common.expires` | `dialog.votes.expires`、`dialog.votes.entry.deadline`、`dialog.tooltip.votes.expires`、`dialog.tooltip.votes.entry.deadline`、`dialog.tooltip.my-join.expires`、`dialog.tooltip.town-join.entry-expires` |
| `dialog.votes.already-voted` | `dialog.tooltip.votes.already-voted` |
| `dialog.votes.ineligible` | `dialog.tooltip.votes.ineligible` |
| `dialog.votes.entry.approve-count` | `dialog.tooltip.votes.entry.approve-count` |
| `dialog.votes.entry.oppose-count` | `dialog.tooltip.votes.entry.oppose-count` |
| `dialog.votes.once` | `dialog.tooltip.votes.once` |
| `dialog.votes.cancel-only` | `dialog.tooltip.votes.cancel-only` |
| `dialog.votes.cancel-irreversible` | `dialog.tooltip.votes.cancel-irreversible` |

注意：`PluginMessages.validateRequiredMessages()` 当前显式校验 3 个 `dialog.tooltip.votes.entry.*` 键；合并时需同步更新该校验和相关测试。

### 3. 通用配置校验（4 组，11 个键）

建议放入 `validation.common`，各校验器共同引用：

| 建议公共键 | 当前重复键 | 文案 |
| --- | --- | --- |
| `validation.common.value-required` | `validation.buff.value-required`、`validation.configuration.value-required`、`validation.economy.settlement-account-required`、`validation.bonus.backup-directory-required` | `&c{path} 不能为空` |
| `validation.common.range` | `validation.runtime-configuration.range`、`validation.bonus.building-refund-weekly-limit-range`、`validation.bonus.building-refund-retention-range`、`validation.bonus.beacon-refresh-interval-range` | `&c{path} 必须在 {minimum}~{maximum} 范围内` |
| `validation.common.integer-type` | `validation.configuration.integer-type` | `&c{path} 必须为整数` |
| `validation.common.non-negative` | `validation.economy.weekly-subsidy-limit-negative`、`validation.economy.twelve-hour-subsidy-limit-negative` | `&c{path} 不能小于 0` |

### 4. 明显的界面/输出重复（4 组）

- `chat.station.invalid-copy`（216）与 `chat.station.invalid-interaction`（235）均为 `&c该讲台数据异常。`。建议统一为 `chat.station.invalid-data`。
- `chat.station.list-entry-console`（230）与 `chat.station.list-entry`（229）完全相同；Java 当前两种发送路径都读取 `list-entry`，未发现 `list-entry-console` 的调用，可直接删除未使用键。
- `dialog.tooltip.application-members.select`（1402）与 `dialog.application.member-select-hint`（1834）均为 `&7点击后从在线玩家列表中选择`。建议保留一个公共提示键。
- `dialog.tooltip.application-members.save`（1403）与 `dialog.application.save-hint`（1836）均为 `&8保存后系统会邀请两名成员确认`。建议保留一个公共提示键。

## P2：可合并，但先确认是否需要按场景定制

### 1. Dialog 公共字段

这些字段格式与占位符完全相同，适合收敛到 `dialog.common`；但合并后无法针对特定页面单独换颜色或措辞。

- `&7小镇: {town}`：`dialog.transfer.town`、`dialog.finance.town`、`dialog.rules.town`、`dialog.review.town`、`dialog.donation.town`
- `&7简介: {description}`：`dialog.town.description`、`dialog.join.town-description`、`dialog.admin.description`、`dialog.tooltip.join.town-description`、`dialog.application.description`
- `&7小镇代码: {code}`：`dialog.join.town-code`、`dialog.tooltip.join.town-code`、`dialog.tooltip.admin.entry-code`
- `&c管理员意见: {message}`：`dialog.main.application-review`、`dialog.tooltip.main.application-review`、`dialog.application.review-message`
- `&7名称: {name}`：`dialog.admin.name`、`dialog.application.name`
- `&7小镇领地名: {residence}`：`dialog.admin.residence-name`、`dialog.application.residence-name`
- `&7申请人: {applicant}`：`dialog.admin.applicant`、`dialog.application.applicant`
- `&7申请状态: {status}`：`dialog.admin.status-line`、`dialog.application.status-line`
- `&7规则: {rules}`：`dialog.join.town-rules`、`dialog.admin.rules`
- `&c{error}`：`dialog.review.error`、`dialog.donation.error`

### 2. 可抽为通用 UI 文案

- `dialog.main.finance`、`dialog.finance.title`、`dialog.finance.summary-title`：`&6公共资产`
- `dialog.main.town-summary`、`dialog.town.summary-title`、`dialog.join.town-name`：`&6{town}`
- `dialog.rules.title`、`dialog.application.rules-label`：`&6小镇规则`
- `dialog.notice.form-draft-saved-title`、`dialog.notice.draft-saved-title`：`&6草稿已保存`
- `dialog.main.handbook`、`dialog.personal.handbook`：`&6领取小镇手册`
- `dialog.tooltip.member-detail.kick-confirm`、`dialog.tooltip.transfer.accept-confirm`：`&7需要再次确认`
- `dialog.tooltip.application.preview-site`、`dialog.tooltip.admin.preview`：`&7传送至领地中心并显示火焰边界`
- `dialog.tooltip.main.my-applications-limit`、`dialog.tooltip.join.submit-limit`：`&7同时最多申请 3 个小镇`
- `dialog.tooltip.personal.disband-irreversible`、`dialog.confirmation.irreversible`：`&c此操作不可撤销`
- `dialog.common.unknown-player`、`dialog.ledger.unknown-player`：`未知玩家（{playerId}）`
- `dialog.main.territory-summary`、`dialog.finance.territory-units`：`&7领地单元: &f{count}/{maximum}`
- `dialog.governance.applications-count`、`dialog.pending.applications-count`：`&b入镇申请 · {count}`
- `dialog.transfer.reject`、`dialog.admin.reject`：`&c拒绝`
- `dialog.my-join.entry-title`、`dialog.admin.entry-title`：`&e{town}`

### 3. 跨层完全相同的业务消息

以下内容相同，但分别服务于校验、命令、运行时或账本。只有在确认“不需要按渠道自定义”后再合并：

- `validation.territory.town-required`、`chat.runtime.town-required`：`你不属于任何小镇`
- `chat.admin.town-not-found-generic`、`chat.runtime.town-not-found`：`小镇不存在`
- `validation.vault.adjustment-non-zero`、`chat.admin.money-adjust-zero`：`调整金额不能为 0`
- `chat.land-protection.initial-projection-collision`、`chat.land-protection.expansion-collision`：`目标 5×5 区块与 Residence 冲突: {residence}`
- `chat.bonus.backup-result-failure`、`chat.bonus.diagnostic-failed`：`&c{detail}`

## P3：重复但建议保留独立键

这些键当前文本相同，或只在展示颜色上不同，但语义生命周期不同，保留独立配置更清晰：

- `chat.bonus.diagnostic-not-run` 与 `chat.backup.not-run`：诊断状态和备份状态都显示“尚未执行”，未来很可能分别扩充。
- `chat.lifecycle.compensation-auto` 与 `chat.lifecycle.compensation-manual`：自动补偿和人工补偿是不同恢复分支，即使当前后缀相同也应允许分别定制。
- `log.donation.operation-reason` 与 `dialog.ledger.type.donation`：一个是写入时的原因快照，一个是账本类型标签。
- `handbook.item-title` 与 `handbook.item-display-name`：书本内部标题和物品显示名属于不同 Minecraft 字段，颜色要求也不同。
- 页面入口文字与页面标题仅在去除颜色后相同，例如“成员管理”“待办中心”“个人与帮助”“申请建立小镇”“访客管理”“税率设置”。颜色承担层级信息，不应合并。
- 建镇申请字段的带颜色标题与无颜色表单 label（小镇名称/代码/简介/规则）用途不同，不应合并。
- `chat.station.list-entry` 与 console 输出如果将来需要去除点击样式或颜色，可以重新引入 console 专用键；当前实现没有这种差异，因此现有未使用键仍建议删除。

## 高相似但不应误判为重复

- `chat.admin.usage-*` 与 `chat.admin.help-*`：命令错误提示包含“用法”，帮助项包含颜色和补充说明，两者用途不同。
- `validation.application.*-format`：名称、简介和规则虽然句式一致，但主语和占位符不同。
- `dialog.application.initial-member-online-required` 与 `initial-members-online-required`：分别对应单个成员和两名成员的校验结果。
- `chat.notification.tax-updated` 与 `chat.runtime.tax-changed`：通知和操作结果使用不同颜色，通知文本还带用于拼接按钮的尾随空格。
- `station.already-exists` 与 `chat.station.already-exists`：只有“这个/该”差异，可统一措辞，但分别属于玩家放置流程与管理员查询流程。

## 实施注意事项

1. 不能只删除 YAML 键；调用方通过字符串路径读取消息，必须同步替换 Java 代码和测试中的键名。
2. `PluginMessages.validateRequiredMessages()` 包含硬编码必需键，治理投票相关合并必须同步修改。
3. `PluginMessages` 会先读取服务器数据目录中的用户版 `messages.yml`，再挂载 JAR 默认值。重命名键时应考虑旧用户配置的兼容读取，否则升级后用户对旧键的自定义会失效。
4. 建议先做 P1，并增加“旧键作为 fallback、新键优先”的一个版本迁移期；P2 留给后续文案结构整理。
