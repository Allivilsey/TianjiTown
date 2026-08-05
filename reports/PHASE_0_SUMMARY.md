# 现服旧小镇系统只读盘点

扫描源：`/Users/allivilsey/TianjiProjects/plugins-v2/plugins`。完整机器可读结果见 `phase-0-inventory.json`，迁移阻止清单见 `../config/phase0-migration.yml`。

## 结果摘要

- DeluxeMenus 命中 14 个小镇相关菜单文件，识别出 24 个疑似 LuckPerms 小镇组。
- 旧加入菜单会执行 `lp user <player> parent set <group>`，退出菜单会 `parent set default`；这会覆盖玩家 primary group，新系统禁止沿用。
- Residence 快照共有 1,534 个顶层领地：`world` 1,377、`world_nether` 104、`world_the_end` 53、`spawnworld` 0。
- 仅按名称精确匹配得到 4 个候选旧小镇 Residence：`world:cxz`、`world:SC`、`world:tds`、`world:zmz`。它们仍需人工确认，扫描器不会自动吸收。
- ZNPCsPlus `town` NPC 已启用，位置为 `world (-111.5, 75, -226.5)`；当前动作只是向玩家发送消息，不应成为 TianjiTown 硬依赖。
- PlaceholderAPI 有 5 个小镇相关 JavaScript：`getTown.js`、`getTowngroup.js`、`getTownname.js`、`getTownqq.js`、`townperms.js`。
- QuickShop-Hikari 使用本地 H2，基础税率 `5%`、账户 `tax`、`apply-to: player`。
- DailyTaxEconomy 配置仅显示 `taxer-vault: [tianjitax]`；没有 JAR/源码，无法仅从字段名证明其征税或清理语义。

## 阻止清单

下列组对应的展示名与组 ID在迁移确认前全部保留，禁止新系统重名建镇，旧组成员禁止创建/加入另一个新镇：

| 组 | 展示名 | 组 | 展示名 |
|---|---|---|---|
| `aucuba` | Aucuba | `cxz` | 春雪镇 |
| `emskz` | 鄂木斯克镇 | `hfld` | 和风流地 |
| `hsz` | 黑水镇 | `jmz` | 基米镇 |
| `krs` | 烤肉社 | `mys` | 米游社 |
| `qxh` | 卿小黑 | `sc` | 拾城 |
| `sdb` | 斯德堡 | `sjx` | 四季乡 |
| `sxwqz` | 水箱温泉镇 | `tds` | 塔迪斯镇 |
| `tkg` | 天空港 | `tlz` | 塔罗镇 |
| `tsz` | 趟水镇 | `xlc` | 香来村 |
| `xyzm` | 下一终末/旧加入广播称“终末镇” | `yhz` | 圆蛤镇 |
| `yyx` | 云烟乡 | `yz` | 夜镇 |
| `zlc` | 杂粮村 | `zmz` | 筑梦镇 |

## 数据缺口

LuckPerms 当前使用 MySQL；目录内 `save2.json.gz` 生成于 2020-03-13，不能用于当前成员映射。因此 `phase0-migration.yml` 中所有 `town-id`、`mayor-uuid`、`residence-names` 保持空值，直到取得当前只读导出并人工复核。

插件目录也不包含生产 JAR 与 `latest.log`，故版本仍只能标记为 2025-11-18 历史基线。

