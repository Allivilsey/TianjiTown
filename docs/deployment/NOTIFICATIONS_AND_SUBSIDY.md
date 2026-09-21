# 身份通知、补贴周期与消息配置

正式版保留已发布的初始建表脚本，使用增量迁移升级数据库，当前 schema 为 `1.1`。插件安装包为 `TianjiTown-1.0.0.jar`。

## 补贴周期与历史归属

QuickShop、Jobs 和 GlobalMarketPlus 税收补贴共用额度，统一使用上海时间，周窗口始于周一 04:00，半日窗口始于每日 04:00、16:00。存储和比较仍使用 UTC epoch 毫秒，查询、预留与重试共用周期算法；调用方传入其他时区也不改变此业务规则。三个收入来源共同消耗每个小镇的每周与 12 小时额度；额度不足时仅补贴剩余金额，耗尽后税款仍正常入账。建筑返还的周规则不变。

新建数据库直接使用当前补贴周期算法；不执行历史数据回填或版本升级。测试使用可重建的开发数据库。

```sql
SELECT status, COUNT(*), SUM(requested_minor), SUM(granted_minor)
FROM quickshop_subsidy_reservations GROUP BY status;
SELECT hex(reservation_id), business_key, requested_minor, granted_minor,
       status, created_at, week_start, period_12h_start
FROM quickshop_subsidy_reservations ORDER BY business_key;
SELECT COUNT(*), SUM(amount_minor) FROM ledger_entries;
```

## 身份变更通知

初始建表脚本创建持久待投递表 `player_change_notifications`。成员与访客变化在原事务内写入通知，失败事务回滚通知；同角色重复写入不创建通知。普通任免、管理员强制调整、接任、治理投票均受相同机制覆盖。访客入镇合并为一次“访客 → 成员”，避免删除访客时再发一次移除通知。不回填历史操作通知。

在线玩家按秒读取待投递记录，登录时也触发读取。发送后立即异步确认删除；同进程确认失败只重试确认，避免重发。离线或读取失败保留记录。聊天发送与数据库确认无法原子提交：若进程恰好在聊天发送后、持久确认前崩溃，重启后该条可能再次提示。测试已覆盖正常重启恢复与同进程重试去重，不宣称任意崩溃点的端到端恰好一次投递。

## 消息与小镇代码

保留自定义 messages.yml 的已有值，缺少的新键使用内置默认值。新增三组确认标题/悬浮提示位于 `dialog.confirmation`，分别为 `submit-application-confirm`、`accept-mayor-confirm`、`change-role-confirm`，悬浮提示追加 `-tooltip`。角色后果默认使用 `{player}` 与 `{role}`；旧自定义只含 `{role}` 仍可加载。自定义过的多行提示和代码要求需由维护者同步文案，插件不会覆盖已有自定义文本。

申请、资料校验与底层 Residence 名称对象统一要求代码为 3～9 个英文字母，不保留旧代码范围的兼容校验。小镇资料编辑只修改简介和规则，不能修改代码；不存在数据库回填或旧开发配置升级流程。

领地地图固定只包含 25 个 20px 区域按钮，底部“选择操作”进入独立页面显示数量、价格、确认、清空及继续选择。客户端各 GUI 缩放和完整测试服流程须按 [安装与预发检查](../setup/PREFLIGHT.md) 留存人工验收证据。
