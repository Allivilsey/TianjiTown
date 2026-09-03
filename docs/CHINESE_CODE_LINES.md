# 含中文字符的插件代码行清单

## 扫描范围与规则

- 扫描范围：各模块 src/main 下的 Java、YAML、SQL 文件。
- 排除：所有 messages.yml、Java/SQL/YAML 注释、测试代码、文档和脚本。
- Java 注释：//、/* ... */。
- SQL 注释：--、/* ... */。
- YAML 注释：#。
- 字符串字面量、配置值和 SQL 文本中的中文保留并计入。
- 行号范围（例如 33-34）表示范围内每一行均命中。

## 统计

- 总计：1,393 行，66 个文件。
- Java：1,380 行。
- YAML：11 行。
- SQL：2 行。
- 按模块：tianjitown-core 74 行，tianjitown-integrations 160 行，tianjitown-paper 835 行，tianjitown-storage 324 行。

## 完整位置清单

### tianjitown-core

- tianjitown-core/src/main/java/cn/tianji/town/core/application/ApplicationStatus.java: 32
- tianjitown-core/src/main/java/cn/tianji/town/core/application/ApplicationText.java: 31, 33, 35, 37, 39, 42, 73, 75, 83, 91
- tianjitown-core/src/main/java/cn/tianji/town/core/application/ApplicationWorkflow.java: 18
- tianjitown-core/src/main/java/cn/tianji/town/core/consumption/BuffDefinition.java: 15, 18, 21, 24, 27, 30, 33, 37, 47
- tianjitown-core/src/main/java/cn/tianji/town/core/consumption/BuffDurationOption.java: 7-10, 40, 45
- tianjitown-core/src/main/java/cn/tianji/town/core/consumption/BuffPricing.java: 15, 18, 30, 33-34, 47, 53, 60
- tianjitown-core/src/main/java/cn/tianji/town/core/economy/MoneyAmount.java: 9, 15, 24, 60
- tianjitown-core/src/main/java/cn/tianji/town/core/economy/TaxRate.java: 11, 21
- tianjitown-core/src/main/java/cn/tianji/town/core/governance/GovernanceRules.java: 9, 20
- tianjitown-core/src/main/java/cn/tianji/town/core/land/ChunkPosition.java: 8, 11
- tianjitown-core/src/main/java/cn/tianji/town/core/land/ExpansionDirection.java: 6-9, 35, 38-42
- tianjitown-core/src/main/java/cn/tianji/town/core/land/ExpansionPricing.java: 14
- tianjitown-core/src/main/java/cn/tianji/town/core/land/InitialTerritory.java: 77
- tianjitown-core/src/main/java/cn/tianji/town/core/land/TerritoryRules.java: 18, 24, 35, 41, 45, 49, 54, 61, 76, 83, 98
- tianjitown-core/src/main/java/cn/tianji/town/core/land/TownResidenceName.java: 19
- tianjitown-core/src/main/java/cn/tianji/town/core/ports/LandProtectionService.java: 42, 48, 54, 67, 73

### tianjitown-integrations

- tianjitown-integrations/src/main/java/cn/tianji/town/integrations/globalmarketplus/GlobalMarketPlusIncomeTaxAdapter.java: 65, 67, 100, 124, 173, 228, 234
- tianjitown-integrations/src/main/java/cn/tianji/town/integrations/jobs/JobsIncomeTaxAdapter.java: 50, 52, 71, 85, 87, 96, 103, 109
- tianjitown-integrations/src/main/java/cn/tianji/town/integrations/quickshop/QuickShopHistoryProbe.java: 34, 50, 56, 62, 72, 100
- tianjitown-integrations/src/main/java/cn/tianji/town/integrations/quickshop/QuickShopTaxAdapter.java: 74-76, 98, 100, 133, 156, 195, 201, 209, 215
- tianjitown-integrations/src/main/java/cn/tianji/town/integrations/residence/ResidenceCommandGuard.java: 112-113
- tianjitown-integrations/src/main/java/cn/tianji/town/integrations/residence/ResidenceDeletionGuard.java: 73, 85, 98, 105
- tianjitown-integrations/src/main/java/cn/tianji/town/integrations/residence/ResidenceLandProtectionService.java: 62, 78, 82, 98, 106, 109, 113, 129, 133, 136, 140-141, 154, 160, 167, 213, 218, 222, 230, 242, 260, 263, 267, 273, 277, 280, 299, 314, 317, 320, 325-326, 349, 360, 370, 383, 395-396, 399, 406, 412, 416, 422, 426, 442, 445, 451, 458, 468, 472, 482, 492, 497, 503, 516, 519, 523, 527, 530, 553, 558, 562, 569, 589
- tianjitown-integrations/src/main/java/cn/tianji/town/integrations/vault/VaultEconomyProbe.java: 19, 24, 26
- tianjitown-integrations/src/main/java/cn/tianji/town/integrations/vault/VaultSettlementService.java: 25, 32, 39, 43, 52, 55, 57, 62, 74, 82, 84, 92, 105, 108, 111, 117, 120, 123, 129, 132, 136-137, 144, 156, 159, 162-163, 170, 183, 186, 189, 195, 198, 201, 207, 210, 213, 220, 235, 238, 240-241, 267, 273, 285, 293, 298, 308, 313
- tianjitown-integrations/src/main/java/cn/tianji/town/integrations/worldborder/WorldBorderBoundaryService.java: 32, 74, 87, 100, 109, 118

### tianjitown-paper

- tianjitown-paper/src/main/java/cn/tianji/town/paper/AsyncTaskTracker.java: 11, 26
- tianjitown-paper/src/main/java/cn/tianji/town/paper/BuffRuntime.java: 78, 97, 134, 160-161, 236, 240, 327, 358-359, 372, 415, 421, 428, 434, 456, 459, 462, 465, 468, 473, 476, 480, 484, 549, 573, 575-578, 621, 624, 678-679, 694, 698, 705, 715, 732
- tianjitown-paper/src/main/java/cn/tianji/town/paper/BuffSettings.java: 26, 40, 43, 71, 81, 91, 99, 115, 123, 131
- tianjitown-paper/src/main/java/cn/tianji/town/paper/CommandConfirmationManager.java: 27, 94, 109
- tianjitown-paper/src/main/java/cn/tianji/town/paper/ConfigurationValues.java: 20, 26, 34, 50, 54, 67, 73, 84, 93, 101, 109, 117, 124, 129, 134
- tianjitown-paper/src/main/java/cn/tianji/town/paper/DonationCompensationCoordinator.java: 40, 43, 53, 81, 106
- tianjitown-paper/src/main/java/cn/tianji/town/paper/EconomySettings.java: 40, 43, 46, 49, 53, 57, 61, 67
- tianjitown-paper/src/main/java/cn/tianji/town/paper/GovernanceSettings.java: 58-59
- tianjitown-paper/src/main/java/cn/tianji/town/paper/PluginMessages.java: 35, 69, 83, 95, 101, 121
- tianjitown-paper/src/main/java/cn/tianji/town/paper/ProvisionResult.java: 15, 24, 29
- tianjitown-paper/src/main/java/cn/tianji/town/paper/RetryingWorkQueue.java: 25, 28, 116
- tianjitown-paper/src/main/java/cn/tianji/town/paper/RuleEditorDialogRenderer.java: 62, 77, 80, 94, 101
- tianjitown-paper/src/main/java/cn/tianji/town/paper/RuntimeConfigurationValidator.java: 31, 40, 67, 71, 78, 88, 94, 99, 112-113
- tianjitown-paper/src/main/java/cn/tianji/town/paper/SitePolicy.java: 212, 224, 244, 311, 316, 320, 340, 349, 372
- tianjitown-paper/src/main/java/cn/tianji/town/paper/TerritoryDialogRenderer.java: 195
- tianjitown-paper/src/main/java/cn/tianji/town/paper/TerritoryService.java: 54, 58, 64, 67, 73, 97, 194, 196, 204, 227, 235, 289
- tianjitown-paper/src/main/java/cn/tianji/town/paper/TianjiTownPlugin.java: 36, 53, 61, 69, 76, 78-79, 86, 101, 113-114, 127, 132, 140, 171, 199, 222, 242, 255, 270, 274, 280, 291, 313, 328, 339-340, 342-343, 352, 361, 366, 401-402, 433, 435, 483, 492, 495, 498, 501, 505, 508, 511, 516, 520, 533-535, 537, 551, 563, 571
- tianjitown-paper/src/main/java/cn/tianji/town/paper/TownActionFailures.java: 46, 50, 54-55, 58-59
- tianjitown-paper/src/main/java/cn/tianji/town/paper/TownActionResult.java: 70, 78
- tianjitown-paper/src/main/java/cn/tianji/town/paper/TownActions.java: 94, 101, 127, 144, 164, 367, 394, 418, 550, 556, 560, 562
- tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCommand.java: 155, 159, 167, 177, 186, 190, 194, 203, 227, 275, 281, 284, 361, 370, 402, 423, 431, 435, 439, 463, 472, 475, 490, 531, 546, 556, 558, 565, 575, 597, 603, 606, 624, 637, 662-664, 680, 693, 700, 704, 711, 716, 718, 726, 737, 739, 759, 777, 788, 812, 828, 830, 838, 843-844, 859, 869-870, 894, 914, 919, 951, 957, 961-962, 969, 984-985, 1068, 1071-1076, 1079, 1082, 1085, 1088, 1091, 1117
- tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCompletionEngine.java: 21-22, 253
- tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminTabCompleter.java: 111, 115
- tianjitown-paper/src/main/java/cn/tianji/town/paper/TownBonusRuntime.java: 116, 134, 152, 164, 237-238, 376, 436, 497, 521, 548
- tianjitown-paper/src/main/java/cn/tianji/town/paper/TownBonusSettings.java: 32, 35, 39, 45, 49, 60, 73, 79, 93, 96, 99, 114, 125, 131, 142, 147, 159, 166, 172
- tianjitown-paper/src/main/java/cn/tianji/town/paper/TownCommandParser.java: 17, 31, 33, 43, 72, 88, 111, 189-193, 195-197, 199-205
- tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java: 98, 106, 115, 123, 149-150, 157-158, 165, 173, 186, 195, 201, 281, 287, 290, 301, 304-305, 322, 351-352, 363-364, 370, 384, 392, 407, 433-434, 440, 443, 446-447, 486, 502, 506-507, 512, 518, 532, 538-539, 545, 553-554, 569-570, 577, 583-584, 597-598, 608, 614-615, 624, 632, 641-642, 661-662, 667, 676, 683-684, 690, 696, 709, 715, 721, 729-731, 733, 755, 759, 764, 769, 793, 808-809, 814-815, 824-825, 831, 840, 844-845, 848, 853-855, 861, 871, 884-885, 896-897, 916, 923, 928, 959, 976, 979, 1003, 1011, 1040-1041, 1056, 1076, 1084-1085, 1132-1133, 1141, 1148-1149, 1153, 1162, 1168, 1171, 1196, 1272, 1281, 1286, 1310, 1326, 1333, 1398, 1413, 1461, 1472, 1476, 1509, 1512, 1534, 1536, 1539, 1550-1551, 1573, 1600, 1630, 1661, 1665, 1688-1689, 1720, 1726, 1761
- tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java: 134, 233, 321, 372, 467, 469-470, 487, 587, 596, 762-764, 770, 773, 775, 777, 780, 782, 786-789, 796-798, 800, 804, 807, 813, 819, 824, 844-845, 848, 851, 860, 863, 868, 880, 883, 886, 889, 917-918, 921, 925, 928, 958, 962, 966, 969, 1005-1006, 1012, 1023, 1027, 1036-1039, 1043, 1048, 1052, 1060, 1068-1071, 1073, 1075-1076, 1078, 1080, 1084, 1086, 1092, 1095, 1098, 1104, 1107, 1117, 1119-1122, 1125, 1182, 1192-1194, 1197-1198, 1207, 1209-1210, 1214, 1218, 1221, 1372, 1374-1375, 1388, 1505-1517, 1566-1570, 1572, 1576, 1579, 1583, 1586, 1589, 1593, 1598, 1603, 1607, 1613, 1620, 1625, 1629, 1634-1636, 1642, 1650-1652, 1655, 1659, 1662, 1667, 1678, 1682, 1688, 1712, 1797, 1800, 1803, 1814, 1851, 1926, 1930, 1933, 1941, 1962, 1969, 1975, 1994, 2001, 2010-2014, 2018, 2022, 2072, 2129, 2132, 2146, 2175-2176, 2179, 2183, 2186, 2194, 2197-2199, 2201, 2205, 2223, 2252-2253, 2256, 2260, 2263-2264, 2273, 2279-2282, 2286, 2289, 2319-2320, 2323, 2327, 2330-2331, 2343, 2345-2352, 2354, 2359, 2362, 2365, 2368, 2371, 2375, 2379-2380, 2384, 2388, 2392, 2396, 2652-2653, 2889, 2895, 2903, 2918, 2933, 2939, 2948, 2950, 2958, 2964, 2969, 2980, 2983, 2991, 2998, 3004, 3023-3024, 3144, 3148, 3150-3151, 3159-3161, 3165, 3173, 3179, 3182-3183, 3198-3200, 3208-3212, 3229, 3306, 3480, 3648, 3699, 3704, 3707, 3850, 3889, 3948, 3950, 3957, 3999-4002, 4014, 4079, 4082, 4087, 4117, 4188, 4192, 4194, 4199, 4204-4207, 4245-4250, 4315, 4549, 4625, 4629, 4640-4651
- tianjitown-paper/src/main/resources/config.yml: 78, 89
- tianjitown-paper/src/main/resources/plugin.yml: 7, 11, 15, 25, 28, 31, 34, 37, 40

### tianjitown-storage

- tianjitown-storage/src/main/java/cn/tianji/town/storage/bonus/TownBonusRepository.java: 88, 91, 121, 125, 145, 170, 305, 357, 359, 367, 424
- tianjitown-storage/src/main/java/cn/tianji/town/storage/commerce/CommerceRepository.java: 50, 163, 166, 171, 178, 196, 221, 226, 277, 282-283, 311, 323, 339, 421, 438, 454, 467, 481, 485, 494, 554, 606, 608, 621
- tianjitown-storage/src/main/java/cn/tianji/town/storage/database/DatabaseConfig.java: 16, 19, 22
- tianjitown-storage/src/main/java/cn/tianji/town/storage/database/DatabaseGate.java: 57, 68, 71-72, 84, 89-90, 110, 140, 144, 147, 159, 171
- tianjitown-storage/src/main/java/cn/tianji/town/storage/economy/EconomyRepository.java: 140, 164, 185, 202, 277, 293, 319, 326-327, 335, 345, 348, 376, 378, 387, 390, 401, 441, 457, 467, 478, 493, 506, 536, 557, 576, 607, 632, 643, 732, 770, 780, 786, 796, 803, 854, 857, 880, 887, 891, 898, 902, 912, 921, 1000, 1003, 1018, 1040, 1043, 1057, 1063, 1088, 1091, 1121, 1128, 1133, 1143, 1149, 1163, 1195, 1212, 1221, 1265, 1297, 1334, 1376, 1391, 1442, 1484, 1487, 1491, 1499, 1503, 1506, 1511, 1526, 1541, 1592, 1608, 1626, 1668, 1674, 1680, 1686, 1738, 1740, 1813
- tianjitown-storage/src/main/java/cn/tianji/town/storage/governance/GovernanceRepository.java: 86, 92, 103, 107, 116, 129, 139, 148, 161, 165, 169, 172, 174, 183, 186, 191, 207, 221, 227, 239, 243, 255, 258-260, 264, 268, 276, 282, 290, 295, 330, 341, 344, 357, 419, 422, 460, 526, 529, 549, 552, 555, 570, 626, 654, 710, 734, 743, 762, 784, 802, 828, 854, 888, 905, 955, 957, 959
- tianjitown-storage/src/main/java/cn/tianji/town/storage/town/ApplicationFormDraft.java: 31
- tianjitown-storage/src/main/java/cn/tianji/town/storage/town/TownRepository.java: 48, 51, 62, 81, 98, 110, 125, 128, 139, 144, 154, 162, 190, 206, 220, 223, 271, 301, 304, 309, 332, 381, 447, 455, 467, 484, 488, 507, 519-520, 538, 547, 551, 657, 703, 1017, 1020, 1032, 1046, 1049, 1083, 1086, 1092, 1095, 1098, 1101, 1104, 1107, 1110, 1129, 1159, 1162, 1185, 1192, 1200, 1209, 1218, 1222, 1232, 1253, 1266, 1269, 1325, 1339, 1359, 1378, 1389, 1392, 1401, 1411, 1417, 1436, 1454, 1457, 1487, 1490, 1509, 1513, 1538, 1543, 1553, 1568, 1575, 1630-1631, 1647, 1660, 1719, 1800, 1819, 1902, 1908, 1911, 1940, 1956, 2046, 2055, 2084, 2086, 2108, 2129, 2155, 2164, 2189, 2250, 2254, 2290, 2294, 2302, 2334, 2388, 2437, 2449, 2480, 2510, 2609, 2625, 2634, 2637, 2643, 2695, 2697, 2778
- tianjitown-storage/src/main/resources/db/migration/V1_0__initial_schema.sql: 632, 643

## 可见性标记

### 判定标准

- `✅`：该行的中文会直接呈现给玩家或管理员，包括玩家界面、聊天/命令反馈、管理员命令补全、控制台日志、诊断报告、配置校验错误和插件元数据。
- `❌`：仅作内部校验、状态/参数匹配、审计标识或数据库约束；不作为上述对象的直接展示文案。
- 同一文件按标记拆分了行号组；每个原清单中的命中行均在下列分组中覆盖。

### tianjitown-core

- ❌ `ApplicationStatus.java`: 32
- ✅ `ApplicationText.java`: 31, 33, 35, 37, 39, 42, 73, 75, 83, 91
- ❌ `ApplicationWorkflow.java`: 18
- ❌ `BuffDefinition.java`: 15, 18, 21, 24, 27, 30, 33, 37, 47
- ✅ `BuffDurationOption.java`: 7-10
- ❌ `BuffDurationOption.java`: 40, 45
- ❌ `BuffPricing.java`: 15, 18, 30, 33-34, 47, 53, 60
- ❌ `MoneyAmount.java`: 9, 15, 24, 60
- ❌ `TaxRate.java`: 11, 21
- ❌ `GovernanceRules.java`: 9, 20
- ❌ `ChunkPosition.java`: 8, 11
- ✅ `ExpansionDirection.java`: 6-9
- ❌ `ExpansionDirection.java`: 35, 38-42
- ❌ `ExpansionPricing.java`: 14
- ❌ `InitialTerritory.java`: 77
- ❌ `TerritoryRules.java`: 18, 24, 35, 41, 45, 49, 54, 61, 76, 83, 98
- ❌ `TownResidenceName.java`: 19
- ✅ `LandProtectionService.java`: 42, 48, 54, 67, 73

### tianjitown-integrations

- ✅ `GlobalMarketPlusIncomeTaxAdapter.java`: 65, 67, 100, 124, 173, 228, 234
- ✅ `JobsIncomeTaxAdapter.java`: 50, 52, 71, 85, 87, 96, 103, 109
- ✅ `QuickShopHistoryProbe.java`: 34, 50, 56, 62, 72, 100
- ✅ `QuickShopTaxAdapter.java`: 74-76, 98, 100, 133, 156, 195, 201, 209, 215
- ✅ `ResidenceCommandGuard.java`: 112-113
- ✅ `ResidenceDeletionGuard.java`: 73, 85, 98, 105
- ✅ `ResidenceLandProtectionService.java`: 62, 78, 82, 98, 106, 109, 113, 129, 133, 136, 140-141, 154, 160, 167, 213, 218, 222, 230, 242, 260, 263, 267, 273, 277, 280, 299, 314, 317, 320, 325-326, 349, 360, 370, 383, 395-396, 399, 406, 412, 416, 422, 426, 442, 445, 451, 458, 468, 472, 482, 492, 497, 503, 516, 519, 523, 527, 530, 553, 558, 562, 569, 589
- ✅ `VaultEconomyProbe.java`: 19, 24, 26
- ✅ `VaultSettlementService.java`: 25, 32, 39, 43, 52, 55, 57, 62, 74, 82, 84, 92, 105, 108, 111, 117, 120, 123, 129, 132, 136-137, 144, 156, 159, 162-163, 170, 183, 186, 189, 195, 198, 201, 207, 210, 213, 220, 235, 238, 240-241, 267, 273, 285, 293, 298, 308, 313
- ✅ `WorldBorderBoundaryService.java`: 32, 74, 87, 100, 109, 118

### tianjitown-paper

- ❌ `AsyncTaskTracker.java`: 11, 26
- ✅ `BuffRuntime.java`: 78, 97, 134, 160-161, 236, 240, 327, 358-359, 372, 549, 573, 575-578, 621, 624, 678-679
- ❌ `BuffRuntime.java`: 415, 421, 428, 434, 456, 459, 462, 465, 468, 473, 476, 480, 484, 694, 698, 705, 715, 732
- ✅ `BuffSettings.java`: 26, 40, 43, 71, 81, 91, 99, 115, 123, 131
- ❌ `CommandConfirmationManager.java`: 27, 94, 109
- ✅ `ConfigurationValues.java`: 20, 26, 34, 50, 54, 67, 73, 84, 93, 101, 109, 117, 124, 129, 134
- ❌ `DonationCompensationCoordinator.java`: 40, 43
- ✅ `DonationCompensationCoordinator.java`: 53, 81, 106
- ✅ `EconomySettings.java`: 40, 43, 46, 49, 53, 57, 61, 67
- ✅ `GovernanceSettings.java`: 58-59
- ✅ `PluginMessages.java`: 35, 69, 83, 95, 101, 121
- ✅ `ProvisionResult.java`: 15, 24, 29
- ❌ `RetryingWorkQueue.java`: 25, 28, 116
- ✅ `RuleEditorDialogRenderer.java`: 62, 77, 80, 94, 101
- ✅ `RuntimeConfigurationValidator.java`: 31, 40, 67, 71, 78, 88, 94, 99, 112-113
- ✅ `SitePolicy.java`: 212, 224, 244, 311, 316, 320, 340, 349, 372
- ❌ `TerritoryDialogRenderer.java`: 195
- ✅ `TerritoryService.java`: 54, 58, 64, 67, 73, 97, 194, 196, 204, 227, 235, 289
- ✅ `TianjiTownPlugin.java`: 36, 53, 61, 69, 76, 78-79, 86, 101, 113-114, 127, 132, 140, 171, 199, 222, 242, 255, 270, 274, 280, 291, 313, 328, 339-340, 342-343, 352, 361, 366, 401-402, 433, 435, 483, 492, 495, 498, 501, 505, 508, 511, 516, 520, 533-535, 537, 551, 563, 571
- ❌ `TownActionFailures.java`: 46, 50, 54-55, 58-59
- ❌ `TownActionResult.java`: 70, 78
- ✅ `TownActions.java`: 94, 101, 144, 418, 556, 560, 562
- ❌ `TownActions.java`: 127, 164, 367, 394, 550
- ✅ `TownAdminCommand.java`: 155, 159, 167, 177, 186, 190, 194, 203, 227, 275, 281, 284, 361, 370, 402, 423, 431, 435, 439, 463, 472, 475, 490, 531, 546, 556, 558, 565, 575, 597, 603, 606, 624, 637, 662-664, 680, 693, 700, 704, 711, 716, 718, 726, 737, 739, 759, 777, 788, 812, 828, 830, 838, 843-844, 859, 869-870, 894, 914, 919, 951, 957, 961-962, 969, 984-985, 1068, 1071-1076, 1079, 1082, 1085, 1088, 1091, 1117
- ✅ `TownAdminCompletionEngine.java`: 21-22, 253
- ✅ `TownAdminTabCompleter.java`: 111, 115
- ✅ `TownBonusRuntime.java`: 116, 134, 152, 164, 237-238, 376, 436, 497, 521, 548
- ✅ `TownBonusSettings.java`: 32, 35, 39, 45, 49, 60, 73, 79, 93, 96, 99, 114, 125, 131, 142, 147, 159, 166, 172
- ✅ `TownCommandParser.java`: 17, 31, 33, 43, 72, 88, 111, 189-193, 195-197, 199-205
- ✅ `TownRuntime.java`: 98, 106, 115, 123, 149-150, 157-158, 165, 173, 186, 195, 201, 281, 287, 290, 301, 304-305, 322, 351-352, 363-364, 370, 384, 392, 407, 433-434, 440, 443, 446-447, 486, 502, 506-507, 512, 518, 532, 538-539, 545, 553-554, 569-570, 577, 583-584, 597-598, 608, 614-615, 624, 632, 641-642, 661-662, 667, 676, 683-684, 690, 696, 709, 715, 721, 729-731, 733, 755, 759, 764, 769, 793, 808-809, 814-815, 824-825, 831, 840, 844-845, 848, 853-855, 861, 871, 884-885, 896-897, 916, 923, 928, 959, 976, 979, 1003, 1011, 1040-1041, 1056, 1076, 1084-1085, 1132-1133, 1141, 1148-1149, 1153, 1162, 1168, 1171, 1196, 1272, 1281, 1286, 1310, 1326, 1333, 1398, 1413, 1461, 1472, 1476, 1509, 1512, 1534, 1536, 1539, 1550-1551, 1573, 1600, 1630, 1661, 1665, 1688-1689, 1720, 1726, 1761
- ✅ `TownUiController.java`: 134, 233, 321, 372, 467, 469-470, 487, 587, 596, 762-764, 770, 773, 775, 777, 780, 782, 786-789, 796-798, 800, 804, 807, 813, 819, 824, 844-845, 848, 851, 860, 863, 868, 880, 883, 886, 889, 917-918, 921, 925, 928, 958, 962, 966, 969, 1005-1006, 1012, 1023, 1027, 1036-1039, 1043, 1048, 1052, 1060, 1068-1071, 1073, 1075-1076, 1078, 1080, 1084, 1086, 1092, 1095, 1098, 1104, 1107, 1117, 1119-1122, 1125, 1182, 1192-1194, 1197-1198, 1207, 1209-1210, 1214, 1218, 1221, 1372, 1374-1375, 1388, 1505-1517, 1566-1570, 1572, 1576, 1579, 1583, 1586, 1589, 1593, 1598, 1603, 1607, 1613, 1620, 1625, 1629, 1634-1636, 1642, 1650-1652, 1655, 1659, 1662, 1667, 1678, 1682, 1688, 1712, 1797, 1800, 1803, 1814, 1851, 1926, 1930, 1933, 1941, 1962, 1969, 1975, 1994, 2001, 2010-2014, 2018, 2022, 2072, 2129, 2132, 2146, 2175-2176, 2179, 2183, 2186, 2194, 2197-2199, 2201, 2205, 2223, 2252-2253, 2256, 2260, 2263-2264, 2273, 2279-2282, 2286, 2289, 2319-2320, 2323, 2327, 2330-2331, 2343, 2345-2352, 2354, 2359, 2362, 2365, 2368, 2371, 2375, 2379-2380, 2384, 2388, 2392, 2396, 2652-2653, 2889, 2895, 2903, 2918, 2933, 2939, 2948, 2950, 2958, 2964, 2969, 2980, 2983, 2991, 2998, 3004, 3023-3024, 3144, 3148, 3150-3151, 3159-3161, 3165, 3173, 3179, 3182-3183, 3198-3200, 3208-3212, 3229, 3306, 3480, 3648, 3699, 3704, 3707, 3850, 3889, 3948, 3950, 3957, 3999-4002, 4014, 4079, 4082, 4087, 4117, 4188, 4192, 4194, 4199, 4245-4250, 4315, 4549, 4625, 4629, 4640-4651
- ❌ `TownUiController.java`: 4204-4207
- ✅ `config.yml`: 78, 89
- ✅ `plugin.yml`: 7, 11, 15, 25, 28, 31, 34, 37, 40

### tianjitown-storage

- ❌ `TownBonusRepository.java`: 88, 91, 121, 125, 145, 170, 305, 357, 359, 367, 424
- ❌ `CommerceRepository.java`: 50, 163, 166, 171, 178, 196, 221, 226, 277, 282-283, 311, 323, 339, 421, 438, 454, 467, 481, 485, 494, 554, 606, 608, 621
- ❌ `DatabaseConfig.java`: 16, 19, 22
- ❌ `DatabaseGate.java`: 57, 68, 71-72, 84, 89-90, 110, 140, 144, 147, 159, 171
- ❌ `EconomyRepository.java`: 140, 164, 185, 202, 277, 293, 319, 326-327, 335, 345, 348, 376, 378, 387, 390, 401, 441, 457, 467, 478, 493, 506, 536, 557, 576, 607, 632, 643, 732, 770, 780, 786, 796, 803, 854, 857, 880, 887, 891, 898, 902, 912, 921, 1000, 1003, 1018, 1040, 1043, 1057, 1063, 1088, 1091, 1121, 1128, 1133, 1143, 1149, 1163, 1195, 1212, 1221, 1265, 1297, 1334, 1376, 1391, 1442, 1484, 1487, 1491, 1499, 1503, 1506, 1511, 1526, 1541, 1592, 1608, 1626, 1668, 1674, 1680, 1686, 1738, 1740, 1813
- ❌ `GovernanceRepository.java`: 86, 92, 103, 107, 116, 129, 139, 148, 161, 165, 169, 172, 174, 183, 186, 191, 207, 221, 227, 239, 243, 255, 258-260, 264, 268, 276, 282, 290, 295, 330, 341, 344, 357, 419, 422, 460, 526, 529, 549, 552, 555, 570, 626, 654, 710, 734, 743, 762, 784, 802, 828, 854, 888, 905, 955, 957, 959
- ❌ `ApplicationFormDraft.java`: 31
- ❌ `TownRepository.java`: 48, 51, 62, 81, 98, 110, 125, 128, 139, 144, 154, 162, 190, 206, 220, 223, 271, 301, 304, 309, 332, 381, 447, 455, 467, 484, 488, 507, 519-520, 538, 547, 551, 657, 703, 1017, 1020, 1032, 1046, 1049, 1083, 1086, 1092, 1095, 1098, 1101, 1104, 1107, 1110, 1129, 1159, 1162, 1185, 1192, 1200, 1209, 1218, 1222, 1232, 1253, 1266, 1269, 1325, 1339, 1359, 1378, 1389, 1392, 1401, 1411, 1417, 1436, 1454, 1457, 1487, 1490, 1509, 1513, 1538, 1543, 1553, 1568, 1575, 1630-1631, 1647, 1660, 1719, 1800, 1819, 1902, 1908, 1911, 1940, 1956, 2046, 2055, 2084, 2086, 2108, 2129, 2155, 2164, 2189, 2250, 2254, 2290, 2294, 2302, 2334, 2388, 2437, 2449, 2480, 2510, 2609, 2625, 2634, 2637, 2643, 2695, 2697, 2778
- ❌ `V1_0__initial_schema.sql`: 632, 643
