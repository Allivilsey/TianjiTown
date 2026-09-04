# TownUiController 拆分计划

## 背景与目标

- [ ] 以当前 `tianjitown-paper/src/main/java/org/allivlisey/tianjitown/paper/TownUiController.java` 为基线建立拆分分支。当前工作树中的该文件约 4,937 行，同时承担生命周期、事件监听、Dialog 会话、路由分发、页面渲染、业务调用、通知和临时状态管理。
- [ ] 实施前先提交或妥善保存当前规则编辑器相关改动；拆分提交不得覆盖 `RuleEditorDialogRenderer`、`TownDetailsMenuModel` 及其测试的现有未提交修改。
- [ ] 本次“模块”指 `tianjitown-paper` 内按职责拆出的 package-private 类，不新增 Maven 子模块；第一轮继续放在 `org.allivlisey.tianjitown.paper` 包，避免为了拆文件而扩大可见性。
- [ ] 最终让 `TownUiController` 只负责组装、生命周期和对外兼容入口，目标不超过 350 行；普通功能协调器目标不超过 700 行，超过时继续拆分状态/模型/渲染器。
- [ ] 拆分期间保持玩家可见文案 key、操作 action 字符串、target 编码、权限判断、异步线程边界、幂等语义和返回路径不变，不夹带 UI 改版或业务规则调整。

## 目标结构

- [ ] `TownUiController`：组合根和兼容门面。保留 `TownAdminCommand`、`TownRuntime`、`TianjiTownPlugin` 当前需要的入口，例如服务台管理、发放手册、打开财务页、管理员申请列表/预览/通知；内部仅委托给下列模块。
- [ ] `TownDialogService`：统一持有 `active`、`menuSessions`、`actionKey`、`targetKey`，负责打开/关闭 Dialog、超时、返回按钮、确认页、通知页、按钮构造、聊天回调以及“首次有效响应消费会话”。
- [ ] `TownUiActionRouter`：提供唯一的 action 分发入口、维护模式检查和非法/过期 target 的统一错误边界。使用不可变的 action-owner 注册表并在重复注册时启动失败；迁移期间允许未迁移 action 回落到旧分支，最终删除巨型 `switch`。
- [ ] `TownHomeUi`：主页、待办中心、个人中心、公开小镇详情和只读规则入口；持有 `MainView`、`TownDetailsView` 等只属于这些页面的模型。
- [ ] `ServiceStationController implements Listener`：服务台创建/删除/查询/持久化，手册识别与发放，讲台交互、插书、爆炸、活塞和破坏保护；持有服务台/手册 NamespacedKey 与 `StationRecord`。
- [ ] `TownFinanceUi`：财务主页、税率、账本、捐赠输入与税率变更通知；持有 `FinanceView`、`LedgerPage` 和账本类型显示映射。
- [ ] `TownTerritoryUi`：扩张地图、批量选择、报价确认、预览、传送点设置和领地预览；独占 `expansionSelections`、`expansionBatchRequestIds`、`GridTarget`、`TownTerritoryPreview`。
- [ ] `TownBuffShopUi`：增益商店、时长选择、购买及效果描述；持有 `BuffShopView`，复用现有 `BuffDialogRenderer`。
- [ ] `TownMembershipUi`：成员列表/详情、角色变更、踢出、访客管理、镇长转让；持有成员/访客页面模型以及相关通知。
- [ ] `TownGovernanceUi`：治理中心、规则确认与编辑、投票列表/详情/创建/投票/撤销；复用现有 `ReadOnlyRulesDialogRenderer` 和 `RuleEditorDialogRenderer`。
- [ ] `TownJoinApplicationUi`：可加入小镇列表、申请加入、我的加入申请、管理方审批与结果通知；复用现有 `JoinTownMenuModel`。
- [ ] `TownApplicationUi`：建镇申请详情、选址/预览、提交/取消、管理员之外的申请生命周期与申请结果通知。
- [ ] `TownApplicationFormUi`：新建申请和编辑小镇资料的分步表单、草稿、规则、初始成员确认/提醒、校验和保存；独占 `applicationForms`、`initialMemberReminderCooldowns`、`ApplicationFormSession`、`FormPurpose`、`ApplicationField`。
- [ ] `TownAdminApplicationUi`：审核队列、审核详情、批准/拒绝/要求修改、失败恢复和管理员预览。
- [ ] 将纯展示/编码逻辑留在小型模型类中，不创建新的万能 `Utils` 或共享 DTO 文件；某个 record 只被一个功能使用时，与该功能放在一起。

## 依赖和调用规则

- [ ] 依赖方向固定为 `TownUiController -> TownUiActionRouter/各功能模块 -> TownDialogService -> Paper API`；功能模块之间不得互相持有实例。
- [ ] 跨功能跳转只生成路由并交给 `TownUiActionRouter`，或通过最小的 `TownUiNavigator` 接口完成；禁止 `TownFinanceUi` 直接调用 `TownHomeUi` 之类的横向依赖。
- [ ] `TianjiTownPlugin`、`TownRuntime` 和 `TownAdminCommand` 在迁移完成前只依赖 `TownUiController` 门面，避免拆分扩散到整个 paper 模块。
- [ ] `TownRuntime.read/write` 的回调仍通过现有主线程调度约束更新界面；所有异步读取完成后必须先检查玩家在线状态和当前会话 token，不能让旧请求覆盖新页面。
- [ ] action 名称先保持现有字符串协议。每个功能提供自己的 action 集合和 target codec（UUID、分页、成员/角色、访客、投票、网格等），集中替代散落的 `split`、`substring` 和 `UUID.fromString`；非法输入继续进入统一的“数据已过期”页面。
- [ ] `close()` 按“停止接收回调 -> 清理功能临时状态 -> 关闭仍在线玩家页面”的顺序执行；`PlayerQuitEvent` 清除该玩家的会话、表单、扩张选择和冷却状态。
- [ ] 事件监听器拆出后由 `TianjiTownPlugin` 显式注册 `TownUiController.listeners()` 返回的监听器集合，或保留门面转发直至最后一步；同一个事件不得同时注册旧监听器和新监听器。

## 分阶段实施

### 第 0 阶段：锁定行为基线

- [ ] 列出当前所有 UI action，记录其 owner、target 格式、返回路由和权限要求，形成 `TownUiRouteCatalogTest` 可读取的唯一注册表；至少覆盖重复 action、缺失 owner 和格式错误 target。
- [ ] 为高风险纯逻辑补齐特征测试：分页边界、角色/成员/访客 target、投票 target、网格坐标、规则删除版本校验、表单字段更新、文本转义和通知占位符。
- [ ] 将 `townTerritoryLore`、`townNotificationPlaceholders` 从控制器静态方法迁到对应展示模型，并同步现有测试；门面可保留临时委托，待所有调用点迁完再删。
- [ ] 记录拆分前自动测试结果，并按 `test/UI_DIALOG_MANUAL_TEST.md` 抽查主页、返回/关闭、分页、确认页、表单保存和过期会话。

### 第 1 阶段：抽出 Dialog 基础设施和路由壳

- [ ] 提取 `TownDialogService`，迁移 `openMenu`、`openDialogPage`、`openNotice`、`openConfirmation`、Dialog/Button/Component 构造、会话校验、回调和声音等通用代码。
- [ ] 为会话消费规则增加测试：旧 session 无效、同一响应只执行一次、关闭后回调无效、超时只关闭当前 session。
- [ ] 引入 `TownUiActionRouter`，先将 `MAIN`、`CLOSE` 和未知 action 接入；其余 action 临时委托原 `handleAction`，保证每次只改一个分发入口。
- [ ] 删除功能代码对 `menuSessions`、`actionKey`、`targetKey` 和 `active` 的直接访问，统一经 `TownDialogService`。

### 第 2 阶段：抽出独立事件域和低耦合功能

- [ ] 提取 `ServiceStationController`；同步迁移命令门面调用和所有服务台事件，验证讲台/手册 PDC 数据格式不变。
- [ ] 提取 `TownBuffShopUi`，注册 `BUFF_SHOP`、`BUFF_DURATIONS`、`BUY_BUFF`。
- [ ] 提取 `TownFinanceUi`，注册 `FINANCE`、`TAX_MENU`、`LEDGER`、`DONATION_INPUT`，保留 `TownRuntime.openFinance` 的门面入口。
- [ ] 每迁移一个功能，立即从旧 `handleAction` 删除对应 case 和私有方法，避免双实现长期并存。

### 第 3 阶段：抽出有玩家临时状态的领地功能

- [ ] 提取 `TownTerritoryUi`，注册 `EXPANSION_MENU`、`TOGGLE_EXPANSION`、`CLEAR_EXPANSION_SELECTION`、`CONFIRM_EXPANSION_BATCH`、`PREVIEW_EXPANSION`、`EXPAND`、`PREVIEW_TOWN`、`SET_TOWN_TELEPORT`。
- [ ] 将批量扩张选择与请求幂等键完全封装；验证失败退款后会生成新请求键、保留选择，成功或退出后按原行为清理。
- [ ] 验证预览清理、跨世界/未加载世界、安全传送等待和过期异步回调场景。

### 第 4 阶段：抽出成员和治理功能

- [ ] 提取 `TownMembershipUi`，迁移访客、成员、角色、踢出和镇长转让 action，以及相关展示 record。
- [ ] 提取 `TownGovernanceUi`，迁移治理中心、规则确认/编辑、投票 action 和提醒通知。
- [ ] 把重复的 `target.split(":")` 改为具名 target record/codec，并测试字段数量、UUID、枚举、页码以及旧页面版本冲突。
- [ ] 验证市长、经理、普通成员和访客四种身份的按钮可见性及服务端二次权限校验；UI 隐藏不能代替权限校验。

### 第 5 阶段：抽出加入申请和管理员审核

- [ ] 提取 `TownJoinApplicationUi`，迁移浏览、提交、撤销、列表分页、批准/拒绝及通知 action。
- [ ] 提取 `TownAdminApplicationUi`，迁移审核列表/详情、审批原因 Dialog、失败恢复和管理员预览 action。
- [ ] 保持 `TownAdminCommand` 的 `showAdminApplicationList`、`previewTownForAdmin`、`notifyApplicationDecision` 调用先经过门面；稳定后再决定是否暴露更窄接口。
- [ ] 验证申请状态在页面打开后发生变化时仍走冲突/过期处理，不以旧页面执行审批。

### 第 6 阶段：抽出建镇申请和表单（最后迁移最高耦合部分）

- [ ] 先提取 `TownApplicationUi` 的详情、选址、预览、提交和取消，再提取 `TownApplicationFormUi` 的表单状态机，避免一次移动近千行。
- [ ] 将表单步骤定义为具名阶段，统一“加载草稿 -> 编辑 -> 持久化步骤 -> 最终保存/提交 -> 清理”的状态转换；保留当前 `ApplicationFormDraft` 数据格式。
- [ ] 将申请规则编辑与小镇规则编辑共享的内容限制在 `RuleEditorDialogRenderer`/小型组件，不让两个功能控制器彼此调用。
- [ ] 覆盖新申请和编辑小镇资料两种 `FormPurpose`，并测试刷新冲突、重复点击、初始成员解析/确认/提醒冷却、校验失败后输入保留、放弃草稿和保存失败恢复。

### 第 7 阶段：收尾和结构门禁

- [ ] 将 `renderMain`、`openTown`、待办/个人中心等剩余页面迁入 `TownHomeUi`，删除 legacy action fallback 和原巨型 `handleAction`。
- [ ] 精简 `TownUiController` 为构造/组装、`close()`、监听器暴露和少量对外委托；清除不再使用的 import、字段、嵌套 record/enum。
- [ ] 增加结构测试或构建检查：action owner 必须唯一，功能模块不得依赖其他功能实现类，`TownUiController` 行数上限 350，单个功能协调器行数上限 700。
- [ ] 仅在第一轮完全稳定后评估是否移动到 `org.allivlisey.tianjitown.paper.ui` 子包；包移动应作为独立提交，不与职责拆分混合。

## 每阶段验证门槛

- [ ] 自动测试：`mvn -B -ntp -pl tianjitown-paper -am test`。
- [ ] 最终构建：`mvn -B -ntp clean verify`，并确认 shaded Paper 插件仍能生成。
- [ ] 路由验收：主页入口、返回/关闭、分页首尾、确认/取消、未知 action、空/畸形 target、页面过期、快速双击、玩家退出后回调、插件关闭后回调。
- [ ] 功能验收：服务台和手册；建镇申请全流程及管理员审核；加入申请双方流程；成员/访客/镇长转让；规则确认和投票；财务/税率/捐赠/账本；增益购买；批量扩张、退款和预览。
- [ ] 手工回归以 `test/UI_DIALOG_MANUAL_TEST.md` 为主，并补跑 `docs/deployment/TOWN_LIFECYCLE.md`、`docs/deployment/GOVERNANCE.md`、`docs/deployment/ECONOMY_AND_EXPANSION.md` 中与迁移模块相关的项目。
- [ ] 每个阶段单独提交，提交中只包含一个基础设施或一个功能域的移动；发现行为回归时可以整阶段回退，不依赖后续阶段才能恢复编译。

## 完成定义

- [ ] `TownUiController` 不再直接渲染具体业务页面，不再保存表单/扩张/服务台状态，不再包含功能 action 的巨型 switch。
- [ ] 每个 action 只有一个 owner，所有 target 都由对应功能的 codec 解析，未知或过期请求统一安全失败。
- [ ] 所有临时状态都有明确 owner，并在成功、取消、玩家退出、超时和插件关闭路径得到清理。
- [ ] 外部调用点仍通过稳定门面工作，玩家可见行为、文案、权限和数据格式与拆分前一致。
- [ ] 自动测试、完整构建和相关人工回归全部通过后，再把本计划各项标记完成。
