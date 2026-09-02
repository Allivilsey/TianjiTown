# TASK-CN-011：`ResidenceLandProtectionService.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-integrations`
- 源文件：[`tianjitown-integrations/src/main/java/cn/tianji/town/integrations/residence/ResidenceLandProtectionService.java`](../../../tianjitown-integrations/src/main/java/cn/tianji/town/integrations/residence/ResidenceLandProtectionService.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线：64）
- 可见行号：无（重新扫描仅剩注释中的中文）

## 可见文本位置

以下行号是迁移前的基线记录，范围表示包含首尾行；处理后源文件已无非注释可见中文：

`62, 78, 82, 98, 106, 109, 113, 129, 133, 136, 140-141, 154, 160, 167, 213, 218, 222, 230, 242, 260, 263, 267, 273, 277, 280, 299, 314, 317, 320, 325-326, 349, 360, 370, 383, 395-396, 399, 406, 412, 416, 422, 426, 442, 445, 451, 458, 468, 472, 482, 492, 497, 503, 516, 519, 523, 527, 530, 553, 558, 562, 569, 589`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- `ResidenceLandProtectionService` 不再拼接玩家或管理员可见的中文句子；操作结果、校验结果和碰撞诊断统一返回 `ResultCode`/参数，由 Paper 边界的 `LandProtectionMessages` 在使用时解析 `messages.yml`。
- 为 `LandProtectionService` 增加 Residence 世界、投影、区域、权限、重建和修复等结构化结果码，并扩展碰撞结果以区分真实领地碰撞与 Residence/API/世界故障；`core` 与 `integrations` 未反向依赖 Paper 或配置实现。
- 新增 `chat.land-protection.*` 默认键：`world-unloaded`、`api-unavailable`、`projection-missing`、`initial-projection-collision`、`projection-create-rejected`、`projection-create-readback-failed`、`projection-already-absent`、`projection-removed`、`projection-still-present`、`projection-remove-rejected`、`rebuild-owner-mismatch`、`controlled-projection-missing`、`teleport-point-outside-projection`、`teleport-point-updated`、`expansion-projection-missing`、`projection-owner-not-controlled`、`area-owner-not-controlled`、`area-bounds-mismatch`、`expansion-collision`、`area-add-rejected`、`area-add-rollback-failed`、`expansion-area-already-absent`、`main-area-removal-rejected`、`area-still-present`、`expansion-area-removed`、`projection-rebuilt-from-database`、`main-area-missing`、`database-area-create-failed`、`database-rebuild-collision`、`database-rebuild-remove-failed`、`projection-auto-repaired`、`area-count-mismatch`、`area-missing-or-bounds-mismatch`、`projection-bounds-or-area-count-mismatch`、`projection-boundary-mismatch`、`explosion-flag-mismatch`、`explosion-flag-write-failed`、`membership-mismatch`、`member-padd-permission-mismatch`、`member-ignite-permission-mismatch`、`member-padd-permission-write-failed`、`member-ignite-permission-write-failed`、`projection-healthy`；继续复用 `unsupported-teleport-point`、`unsupported-multi-area`、`unsupported-add-area`、`unsupported-remove-area`。
- 动态 Residence 名称、世界名、区域名、所有者、成员和异常详情改为命名占位符；来自第三方或用户输入的值在 integrations 边界转义 `&`/`§`，避免注入颜色控制码。`SitePolicy`、`TownActions` 和 `TownRuntime` 已同步按结构化碰撞码处理错误，不再把故障详情当作领地名称。
- 测试覆盖 Residence 不可用和世界未加载的结构化结果、全部结果码的默认键和完整占位符渲染，以及修改临时 `messages.yml` 后执行 reload 的新输出。
- 验证通过：`mvn -pl tianjitown-integrations -am test`（Core 24、Integrations 25）；`mvn test`（Core 24、Storage 40、Integrations 25、Paper 99）。重新扫描源文件后，可见文本行数为 0；仅保留不进入输出的技术注释。按目录规则，本任务未重新生成全局 `docs/CHINESE_CODE_LINES.md`。
