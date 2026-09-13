1. 当前插件仍然会禁止玩家使用res tp命令传送至小镇，本插件不应该限制res tp命令。
2. 小镇领地的消息应修改为“欢迎来到<小镇名>”/“你已离开<小镇名>”。
3. 申请小镇时，有玩家拒绝成为小镇初始成员，申请人的小镇菜单再次打开时应跳回选择初始成员步骤，并且重置邀请。同时当前状态下无法重新发送邀请消息，需要修复。
4. 移除多余的tienjitown maintance enable/disable命令，此命令已有on/off。
5. “已为 Konpake_Youmu 设置权限标识（container, move, shear, use, destroy, animalkilling, beacon, hook, mobkilling, build, chat, tp, leash）”玩家获取领地权限消息无需打印。
6. 统一诊断通过时无需显示诊断信息。
7. 管理员命令中所有包含原因的命令全部修改为允许不填写原因，当命令不包含原因时采用默认原因“管理员调整”。
8. 去除管理员代购buff命令，改为管理员手动设置buff命令tianjitown buff set <town> <buffkey> <time> <level>，默认一周、一级。<buffkey><time><level>应提供补全，数值限制与小镇购买相同，不消化资金。
9. 小镇buff重复购买时应对已存在buff根据剩余时间折算退款至小镇账户。
10. tianjitown town list 命令默认显示active状态的小镇，想要查看其他状态的小镇需要在命令尾部加上状态。
11. 测试发现重新加入服务器时仍然存在buff提供的生命值上限部分的生命值被清空。可能是buff在下线时被清空？或者是上线时自动刷新了buff？这种情况会导致玩家不得不等待生命值恢复，如果buff等级高的话会非常麻烦。另外其他的buff最好也检查一下代码有没有问题。
12. 命令的权限节点很多余，全部使用admin控制即可。
13. 删除小镇后有部分玩家有buff残留。
14. 创建小镇时有概率发生显示你的小镇已创建完成后聊天栏显示操作失败: Cannot invoke "org.allivilsey.tianjitown.core.land.InitialTerritory.center()"because "this.initial" is null。