# 申请、显示与补贴反馈修复升级说明

本次数据库从 schema 1.1 升至 1.3。插件安装包仍为 `TianjiTown-1.4.0.jar`。

## 补贴周期与历史归属

QuickShop 税收补贴统一使用上海时间，周窗口始于周一 04:00，半日窗口始于每日 04:00、16:00。存储和比较仍使用 UTC epoch 毫秒，查询、预留与重试共用周期算法；调用方传入其他时区也不改变此业务规则。Jobs/GlobalMarketPlus 未使用这项 QuickShop 限额，建筑返还的周规则不变。

V1_2 使用每笔记录的 `created_at`（首次预留时间）重算 `week_start` 和 `period_12h_start`，不使用重试、结算时间或部署时间。历史记录按首次预留时间进入新周期，跨边界记录的周期归属会相应变化。业务唯一键、预留 ID、请求金额、获准金额、状态和账本保持不变；RESERVED 和 APPLIED 都占用额度，CANCELLED 不占额度。超过新窗口上限时剩余额度按零处理，不撤回历史已支付款，也不额外发放。

1. 停服后备份完整数据库及配置，保留旧插件包。在备份副本验证升级；不要用新版本迁移后的数据库直接运行旧包。
2. 升级前后执行下列对账查询。行数与金额按状态必须一致；逐行核对业务唯一键、ID、金额、状态不变。周期字段按 `created_at` 的上海业务窗口核对。
3. 启动新版，由 Flyway 执行 V1_2/V1_3；确认 `/townadmin status` 显示 READY 和 schema 1.3。预留业务重试沿用原记录，不能创建新的业务键补发已支付记录。
4. 保留升级前副本。需要回滚时停服恢复数据库与旧包的同一份快照，不能只替换 JAR。

```sql
SELECT status, COUNT(*), SUM(requested_minor), SUM(granted_minor)
FROM quickshop_subsidy_reservations GROUP BY status;
SELECT hex(reservation_id), business_key, requested_minor, granted_minor,
       status, created_at, week_start, period_12h_start
FROM quickshop_subsidy_reservations ORDER BY business_key;
SELECT COUNT(*), SUM(amount_minor) FROM ledger_entries;
```

## 身份变更通知

V1_3 创建持久待投递表 `player_change_notifications`。成员与访客变化在原事务内写入通知，失败事务回滚通知；同角色重复写入不创建通知。普通任免、管理员强制调整、接任、治理投票均受相同机制覆盖。访客入镇合并为一次“访客 → 成员”，避免删除访客时再发一次移除通知。不会为升级前的历史操作补建通知。

在线玩家按秒读取待投递记录，登录时也触发读取。发送后立即异步确认删除；同进程确认失败只重试确认，避免重发。离线或读取失败保留记录。聊天发送与数据库确认无法原子提交：若进程恰好在聊天发送后、持久确认前崩溃，重启后该条可能再次提示。测试已覆盖正常重启恢复与同进程重试去重，不宣称任意崩溃点的端到端恰好一次投递。

## 消息与旧代码

保留自定义 messages.yml 的已有值，缺少的新键使用内置默认值。新增三组确认标题/悬浮提示位于 `dialog.confirmation`，分别为 `submit-application-confirm`、`accept-mayor-confirm`、`change-role-confirm`，悬浮提示追加 `-tooltip`。角色后果默认使用 `{player}` 与 `{role}`；旧自定义只含 `{role}` 仍可加载。自定义过的多行提示和代码要求需由维护者同步文案，插件不会覆盖已有自定义文本。

新申请及修改后的申请代码须为 3～9 个英文字母；底层 Residence 名称解析仍兼容历史 1～12 位。已有小镇可继续读取和编辑简介、规则，不能借资料编辑修改代码。旧申请重新提交会显示新长度要求。

领地地图固定只包含 25 个 20px 区域按钮，底部“选择操作”进入独立页面显示数量、价格、确认、清空及继续选择。客户端各 GUI 缩放截图和完整测试服流程尚待验收，见本轮 AUTO 报告。
