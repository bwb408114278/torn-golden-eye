# OC大锅饭表驱动配置与指令自助技术实施方案

## 1. 文档信息

- 文档类型：最终技术实施方案
- 适用项目：Golden-Eye
- 适用版本：1.6.2及以上
- 设计日期：2026-09-14
- 维护人：Bai
- 技术方案负责人：AI技术专家
- 设计状态：已通过业务确认，待工程师实施
- 当前实现基线：`7929455`
- 当前分支：实施时从 `main` 新建 `oc-reassign-db-driven`
- 实施状态：未实施
- 技术验收状态：待实施后Review

> 本文是本需求唯一技术实施基线。开发人员只能按照本文修改代码、测试和运维步骤，不得自行扩大或缩小业务范围、改变收益统计口径、排除规则语义或指令权限模型，亦不得以当前实现反向覆盖本文。实施完成后由AI技术专家进行Review，并更新本文的实现基线、实施状态和验收记录。
>
> 本文取代 `oc_reassign_range_technical_design.md` 中"以Java常量承载大锅饭名单与排除规则"的基线设计；该文档的日期边界语义（左闭区间、生效前普通收益保留）、"普通计入次数+大锅饭计入次数=1"不变量、"不删除/不迁移飞书普通收益、不加写侧过滤"的架构决策在本文中继续有效。

---

## 2. 需求目标

### 2.1 配置表驱动

将大锅饭的全部配置从Java常量迁移到数据库表：

- 帮派级收益模式（系数/平分）可配置，收编 `TornOcWorkingHourService`、`OcBenefitQueryStrategyImpl`、推荐评分中硬编码的NOV/BSU特判；
- 帮派大锅饭OC名单、普通收益排除规则（含生效时间）、补算扫描起点、大锅饭帮派集合全部由同一批数据行派生；
- 删除 `TornConstants` 中 `ROTATION_OC_NAME`、`OC_BENEFIT_EXCLUSION_RULES`、`REASSIGN_OC_FACTION`、`PN_OC_REASSIGN_EFFECTIVE_FROM`、`NOV_OC_REASSIGN_EFFECTIVE_FROM` 及 `resolveIncomeStartTime` 中的帮派硬编码分支。

### 2.2 指令自助

- `OC大锅饭开启 <帮派ID> <系数|平分>`：仅超管，帮派级开通；
- `OC大锅饭添加 [帮派ID] <OC名称> [生效日期]`：帮派OC指挥官可添加本帮派；超管可带帮派ID添加任意帮派；
- `OC大锅饭名单 [帮派ID]`：查询当前配置。

效果目标：老OC进入新帮派为零数据库操作、纯指令完成；全新OC为"人工插系数/档案/链/岗位校准一次 + 各帮派纯指令"。

### 2.3 添加指令同步维护新队规划范围

添加成功时同步写入 `torn_setting_faction_oc_plan` 规划行，保持"大锅饭名单=规划范围"的既有镜像关系。

### 2.4 行为等价

迁移后，现有六个帮派的名单、排除规则、扫描起点、收益模式必须与当前常量实现逐行等价，排行榜与个人收益查询口径不发生任何漂移。

---

## 3. 已确认的现状与调用链

### 3.1 常量消费方清单（实施改造面）

| 常量 | 消费方 |
|---|---|
| `ROTATION_OC_NAME` | `TornOcBatchIncomeService`、`TornOcIncomeService`、`TornOcIncomeTransactionWorker`、`TornOcRecommendManager`、`TornOcRecommendService`、`TornOcAssignService` |
| `OC_BENEFIT_EXCLUSION_RULES` | `OcBenefitRankingQuery`（帮派榜/SMTH总榜/同期榜三处构造） |
| `REASSIGN_OC_FACTION` | `TornFactionOcManager`（刷新触发收益）、`TornOcRecommendManager`、`OcBenefitRankingQuery`、`TornOcIncomeService` |
| `PN/NOV_OC_REASSIGN_EFFECTIVE_FROM` | `TornOcBatchIncomeService.resolveIncomeStartTime` |

`OcBenefitRankingQuery` 的构造调用方为 `OcBenefitQueryStrategyImpl`、`OcBenefitRankStrategyImpl`、`TornFactionOcBenefitDAO`。

### 3.2 收益模式现状

- `TornOcWorkingHourService`：NOV(16335)、BSU(11796) 工时系数固定为1，其余帮派查 `torn_setting_oc_coefficient`；
- `OcBenefitQueryStrategyImpl.createDisplayConfig`：NOV、BSU 不展示岗位系数列；
- `TornOcRecommendManager.calcReassignRecommendScore`：直接查系数表，未区分模式（NOV/BSU 推荐老OC时意外吃到 faction_id=0 系数、推荐无系数行的新OC时得0分），本次一并按模式收敛。

### 3.3 系数解析口径（关键约束）

`TornSettingOcCoefficientManager.getCoefficient` 的解析规则：帮派在 `torn_setting_oc_coefficient` 有任意自有行时，只在该帮派自有行内查找，不回落 `faction_id=0`。CCRC(27902) 仅有基础4个OC的自有行，因此CCRC引入新OC必须插入自有系数行，否则该OC全部岗位有效工时为0，收益计算抛"总有效工时为0"异常。添加指令的系数校验必须复用与运行时完全相同的解析口径。

### 3.4 既有可复用设施

- 权限：`GroupPermissionService.invalidAdmin`（超管`projectProperty.getAdminId()` → `isNeedSa` → Leader `groupAdminIds` → 角色名单），`TornFactionRoleTypeEnum.OC_COMMANDER`；
- 帮派绑定：照 `OcAssignStrategyImpl` 模式，目标帮派取自发送者 `user.getFactionId()`；
- 目录自动同步：`TornSettingOcSyncManager` 补齐 `torn_setting_oc` 与 `torn_setting_oc_slot`（默认值）；
- 批量收益防重入：`TornOcBatchIncomeService` 按帮派JVM内防重入；
- 缓存：`DataCacheManager` 体系与"刷新缓存"指令全量刷新；
- 链配置：`torn_setting_oc_chain` 现有4条链（Stacking the Deck→Ace in the Hole；Lock Stock→Hostile Takeover；Manifest Cruelty→Gone Fission→Crane Reaction）。

---

## 4. 总体技术方案

1. 新建两张配置表：帮派级模式表 + 范围行表；Liquibase种子精确复刻当前常量；
2. 新建 `TornSettingOcReassignManager` 作为唯一派生门面（名单/排除规则/帮派集合/扫描起点/收益模式），挂Caffeine缓存与`DataCacheManager`；
3. 三个指令策略 + 一个配置服务 + 一个校验器，落在新建子包内；
4. 全部常量消费方切换到门面；`OcBenefitRankingQuery` 改为构造入参注入派生结果，模型层不依赖数据源；
5. 不修改收益计算公式、飞书同步链路、Mapper XML的排除SQL片段结构（片段本身继续按规则列表动态生成）。

---

## 5. 数据库设计

### 5.1 新表：torn_setting_oc_reassign_faction

帮派级大锅饭开关与收益模式，每帮派一行。

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGINT | PK | 主键 |
| faction_id | BIGINT | NOT NULL, UNIQUE | 帮派ID |
| income_mode | VARCHAR(16) | NOT NULL | 收益模式：COEFFICIENT（系数）/ EQUAL（平分） |
| enabled | BOOLEAN | NOT NULL DEFAULT TRUE | 是否启用大锅饭 |
| deleted | SMALLINT | NOT NULL DEFAULT 0 | 逻辑删除 |
| create_time / update_time | TIMESTAMP | NOT NULL | 审计时间 |

### 5.2 新表：torn_setting_oc_reassign_oc

帮派大锅饭OC范围行，每帮派每OC名称一行。

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGINT | PK | 主键 |
| faction_id | BIGINT | NOT NULL | 帮派ID |
| oc_name | VARCHAR(64) | NOT NULL | OC名称 |
| rank | SMALLINT | NOT NULL | OC级别（冗余自目录，便于审计，与链表口径一致） |
| effective_from | TIMESTAMP | NULL | 生效时间；NULL表示历史所有月份均属大锅饭（原有名单语义） |
| enabled | BOOLEAN | NOT NULL DEFAULT TRUE | 是否启用 |
| deleted | SMALLINT | NOT NULL DEFAULT 0 | 逻辑删除 |
| create_time / update_time | TIMESTAMP | NOT NULL | 审计时间 |

唯一语义：同 `(faction_id, oc_name)` 至多一行有效记录。单实例部署契约下不加数据库唯一约束，由指令幂等校验保证（与 `TornSettingOcSyncManager` 同一契约）。

### 5.3 种子数据（必须逐行等价于当前常量）

帮级行（6行，全部 enabled=true）：

| faction_id | 帮派 | income_mode |
|---|---|---|
| 20465 | PN | COEFFICIENT |
| 2095 | HP | COEFFICIENT |
| 27902 | CCRC | COEFFICIENT |
| 36134 | SH | COEFFICIENT |
| 16335 | NOV | EQUAL |
| 11796 | BSU | EQUAL |

范围行（25行，全部 enabled=true）：

| faction_id | OC名称 | rank | effective_from |
|---|---|---|---|
| 20465 | Ace in the Hole | 9 | NULL |
| 20465 | Stacking the Deck | 8 | NULL |
| 20465 | Break the Bank | 8 | NULL |
| 20465 | Clinical Precision | 8 | NULL |
| 20465 | Blast from the Past | 7 | NULL |
| 20465 | Window of Opportunity | 7 | NULL |
| 20465 | Lock Stock | 8 | 2026-08-01 00:00:00 |
| 20465 | Hostile Takeover | 9 | 2026-08-01 00:00:00 |
| 2095 | Break the Bank | 8 | NULL |
| 2095 | Clinical Precision | 8 | NULL |
| 2095 | Blast from the Past | 7 | NULL |
| 2095 | Window of Opportunity | 7 | NULL |
| 27902 | Break the Bank | 8 | NULL |
| 27902 | Clinical Precision | 8 | NULL |
| 27902 | Blast from the Past | 7 | NULL |
| 27902 | Window of Opportunity | 7 | NULL |
| 36134 | Break the Bank | 8 | NULL |
| 36134 | Clinical Precision | 8 | NULL |
| 36134 | Blast from the Past | 7 | NULL |
| 36134 | Window of Opportunity | 7 | NULL |
| 16335 | Break the Bank | 8 | NULL |
| 16335 | Clinical Precision | 8 | NULL |
| 16335 | Blast from the Past | 7 | NULL |
| 16335 | Window of Opportunity | 7 | NULL |
| 16335 | Lock Stock | 8 | 2026-07-01 00:00:00 |
| 16335 | Stacking the Deck | 8 | 2026-07-01 00:00:00 |
| 16335 | Manifest Cruelty | 8 | 2026-07-01 00:00:00 |
| 16335 | Gone Fission | 9 | 2026-07-01 00:00:00 |
| 16335 | Ace in the Hole | 9 | 2026-07-01 00:00:00 |
| 16335 | Hostile Takeover | 9 | 2026-07-01 00:00:00 |
| 16335 | Crane Reaction | 10 | 2026-07-01 00:00:00 |
| 11796 | Break the Bank | 8 | NULL |
| 11796 | Clinical Precision | 8 | NULL |
| 11796 | Blast from the Past | 7 | NULL |
| 11796 | Window of Opportunity | 7 | NULL |

### 5.4 派生规则（唯一口径，全部实现在TornSettingOcReassignManager）

| 派生项 | 规则 |
|---|---|
| 大锅饭名单 | 帮派enabled + 范围行enabled 的 oc_name 列表 |
| 排除规则 | 同一批范围行逐行转 `FactionOcExclusion(factionId, [ocName], effectiveFrom)`；同帮派同 `effective_from` 的行在生成 `FactionOcExclusion` 时按生效时间分组归并（NULL一组、每个日期一组），保证与现常量结构等价 |
| 大锅饭帮派集合 | 帮派表 enabled 的 faction_id 列表 |
| 补算扫描起点 | 帮派范围行中非NULL `effective_from` 的最小值；全部为NULL时取 `execTime` 所在月份第一天（保持现行为） |
| 收益模式 | 帮派行 `income_mode`；无帮派行时默认 COEFFICIENT（调用方先以帮派集合门禁，默认值仅兜底） |

### 5.5 Liquibase要求

- 文件：`src/main/resources/db/changelog/1.0.1-2.0.0/1.6.2/oc-reassign.yaml`（新建 `1.6.2` 目录）；
- 在 `db.changelog-master.yaml` 末尾追加 include；
- 两张 `createTable` 必须带表与全部字段 `remarks`（含主键、逻辑删除、审计时间字段），遵守 `.ai/knowledge/java_coding_style.md` 的Liquibase注释规范；
- 种子使用 `sql`/`insert` 变更写入5.3全部行，changeSet author 维护人 Bai。

---

## 6. 包规划与新增文件

### 6.1 新增子包

```text
pn.torn.goldeneye.torn.service.faction.oc.reassign      # 大锅饭配置指令编排
pn.torn.goldeneye.napcat.strategy.faction.crime.reassign # 大锅饭指令策略
```

`napcat/strategy/faction/crime` 下已有文件较多（排行/查询/推荐等），新指令策略一律进 `reassign` 子包，不向父包追加。

### 6.2 新增文件清单

1. `src/main/resources/db/changelog/1.0.1-2.0.0/1.6.2/oc-reassign.yaml`（见5.5）；
2. `repository/model/setting/TornSettingOcReassignFactionDO.java` — 帮派级行DO；
3. `repository/model/setting/TornSettingOcReassignOcDO.java` — 范围行DO；
4. `repository/mapper/setting/TornSettingOcReassignFactionMapper.java` — MP BaseMapper，无自定义SQL，无XML；
5. `repository/mapper/setting/TornSettingOcReassignOcMapper.java` — 同上；
6. `repository/dao/setting/TornSettingOcReassignFactionDAO.java`；
7. `repository/dao/setting/TornSettingOcReassignOcDAO.java`；
8. `constants/torn/enums/TornOcIncomeModeEnum.java` — `COEFFICIENT("coefficient")`、`EQUAL("equal")`，含 `of(String)` 解析；
9. `torn/manager/setting/TornSettingOcReassignManager.java` — 派生门面（见7.1）；
10. `torn/service/faction/oc/reassign/OcReassignConfigService.java` — 开启/添加/名单编排（见7.2）；
11. `torn/service/faction/oc/reassign/OcReassignAddValidator.java` — 添加前置校验组件（见7.3）；
12. `napcat/strategy/faction/crime/reassign/OcReassignOpenStrategyImpl.java`；
13. `napcat/strategy/faction/crime/reassign/OcReassignAddStrategyImpl.java`；
14. `napcat/strategy/faction/crime/reassign/OcReassignListStrategyImpl.java`。

回执模型作为 `OcReassignConfigService` 的嵌套record实现，不单独建文件（内部类不写 `@author/@version/@since`）。

---

## 7. 核心设计

### 7.1 TornSettingOcReassignManager（唯一派生门面）

- 实现 `DataCacheManager`：`warmUpCache` 预热两张表列表缓存；`refreshCache` 驱逐；列表加载用 `@Cacheable`，与既有 `TornSettingOcCoefficientManager` 同模式；
- 公共API（全部具备完整Javadoc）：

```text
List<String> getRotationOcNames(long factionId)                 // 名单
Map<Long, List<FactionOcExclusion>> getExclusionRules()         // 排除规则（含NULL/日期分组归并）
List<Long> getReassignFactionList()                             // 帮派集合
LocalDateTime resolveIncomeStartTime(long factionId, LocalDateTime execTime)  // 扫描起点
TornOcIncomeModeEnum getIncomeMode(long factionId)              // 收益模式
```

- `resolveIncomeStartTime` 保持纯函数风格：仅依赖已缓存列表与入参，不查库、不读系统时间；
- `FactionOcExclusion` 继续作为派生值对象复用，结构不变。

### 7.2 OcReassignConfigService（编排，事务边界）

`openFaction(factionId, mode)`：

1. 校验帮派存在于 `torn_setting_faction`；幂等：已有enabled帮派行则拒绝；
2. 插入帮派行；驱逐门面缓存；回执。

`addOc(factionId, ocName, effectiveDate)`（`@Transactional`）：

1. 调用 `OcReassignAddValidator` 完成全部前置校验（见7.3）；
2. 插入范围行（rank取目录值，effective_from可空）；
3. 同步规划范围：`torn_setting_faction_oc_plan` 不存在该 `(faction_id, oc_name)` 时插入 `enabled=true` 行；已存在则跳过并在回执注明"规划行已存在"；
4. 注册事务提交后驱逐门面缓存（照 `TornSettingOcSyncManager.registerCacheEvictAfterCommit` 模式）；
5. 事务外由策略层提交异步补算（防重入由 `TornOcBatchIncomeService` 既有机制保证）；
6. 操作日志记录 factionId、ocName、effectiveFrom、操作人（重要数据变更必须审计）。

`listFaction(factionId)`：返回帮派模式 + 范围行（名称/级别/生效时间/规划行是否存在），供名单指令渲染。

### 7.3 OcReassignAddValidator（校验器，无副作用）

按序校验，任一失败立即返回带原因的结果（record封装，不抛异常控制流程）：

| 序 | 校验 | 规则 | EQUAL模式 |
|---|---|---|---|
| 1 | 帮派开通 | 帮派行存在且enabled | 同 |
| 2 | 目录存在 | `torn_setting_oc` 存在该名称；不存在时回执提示"先执行OC校准触发目录自动同步" | 同 |
| 3 | 岗位齐全 | 岗位目录数 = `required_members` | 同 |
| 4 | 链完整性 | 目标是 `torn_setting_oc_chain`（enabled链）中任一链的子节点时，该链全部前序节点必须已在本帮派名单；链根单独添加合法（进入等待后继状态） | 同 |
| 5 | 系数完整 | `TornSettingOcCoefficientManager` 新增方法 `hasCompleteCoefficients(factionId, ocName, rank, slotCodes)`：复用与 `getCoefficient` 完全相同的"帮派自有行优先，否则faction_id=0"解析，要求每个岗位至少存在一条系数行 | **跳过**（平分模式无系数依赖） |
| 6 | 生效时间 | 默认当月1日；格式 `yyyy-MM-dd`；必须为当天或过去（允许回补） | 同 |
| 7 | 幂等 | 同 `(factionId, ocName)` 已存在enabled范围行则拒绝，回执携带现值 | 同 |
| 8 | 规划档案 | `torn_setting_oc_plan_profile` 无该名称行时**警告不阻断**（仅影响新队规划，不影响收益） | 同 |

系数完整性校验禁止复制解析逻辑：在 `TornSettingOcCoefficientManager` 内提炼公共私有方法供 `getCoefficient` 与 `hasCompleteCoefficients` 共用，防止两处口径漂移。

### 7.4 指令策略

三个策略均继承 `BaseGroupMsgStrategy`，帮派归属照 `OcAssignStrategyImpl` 模式取发送者绑定，不信任指令参数中的帮派归属。

**OcReassignOpenStrategyImpl**

- 命令：`OC大锅饭开启 <帮派ID> <系数|平分>`；仅超管（照 `ManageDocStrategyImpl` 的SA门禁方式）；
- 参数错误、帮派不存在、重复开通分别回执。

**OcReassignAddStrategyImpl**

- 命令：`OC大锅饭添加 [帮派ID] <OC名称> [yyyy-MM-dd]`；`getRoleType() = OC_COMMANDER`；
- 无帮派ID前缀：目标帮派=发送者帮派；带帮派ID前缀：仅超管可用（策略内以 `projectProperty.getAdminId()` 判定），非超管带前缀直接拒绝；
- 解析成功后调用 `OcReassignConfigService.addOc`；成功回执后提交异步补算任务（复用共享执行器异步提交模式，照 `TornFactionOcManager.updateOc` 对批量收益的提交方式）。

**OcReassignListStrategyImpl**

- 命令：`OC大锅饭名单 [帮派ID]`；无角色门槛（`getRoleType()` 返回null）；带帮派ID前缀仅超管可用；
- 渲染：帮派、模式、逐行 `OC名称 [级别] 生效时间(或"始终")`。

**BotCommands 新增常量**：`OC_REASSIGN_OPEN = "OC大锅饭开启"`、`OC_REASSIGN_ADD = "OC大锅饭添加"`、`OC_REASSIGN_LIST = "OC大锅饭名单"`。

### 7.5 指令回执文案（逐字模板，半角标点）

```text
# 开启成功
大锅饭已开启: PTA(9356) 模式=系数

# 添加成功
已加入大锅饭: BSU - Ace in the Hole
生效时间: 2026-09-01 00:00:00
规划范围: 已同步
补算: 已提交异步执行, 稍后可用[OC大锅饭名单]确认

# 添加成功但规划档案缺失(警告)
已加入大锅饭: BSU - Cleared for Takeoff
生效时间: 2026-09-01 00:00:00
规划范围: 已同步
警告: 该OC缺少新队规划档案(torn_setting_oc_plan_profile), 自动规划不会规划该OC
补算: 已提交异步执行, 稍后可用[OC大锅饭名单]确认

# 各失败分支
失败: 帮派未开启大锅饭, 请联系超管执行[OC大锅饭开启]
失败: 目录中不存在该OC, 请先执行[OC校准]触发目录自动同步
失败: 岗位目录不完整(3/6), 请先执行[OC校准]补齐
失败: 链式OC缺少前序节点: 添加[Ace in the Hole]需先加入[Stacking the Deck]
失败: 系数不完整, 缺少岗位系数: Muscle#1, 请先维护系数表
失败: 生效日期不能晚于今天
失败: 该OC已在大锅饭名单中, 生效时间: 2026-09-01 00:00:00
失败: 仅超管可指定帮派ID
```

---

## 8. 常量消费方改造明细

### 8.1 TornConstants

删除：`ROTATION_OC_NAME`、`OC_BENEFIT_EXCLUSION_RULES`、`REASSIGN_OC_FACTION`、`PN_OC_REASSIGN_EFFECTIVE_FROM`、`NOV_OC_REASSIGN_EFFECTIVE_FROM`、`PN/NOV_ORIGINAL/ADDED_ROTATION_OC_NAME`、静态块对应初始化与 `combine()` 方法。

保留：`FACTION_*_ID` 帮派ID常量、`OC_NAME_*` 名称常量（种子文档与测试引用）及其余无关常量。文件头 `@version` 更新为pom版本，`@since` 不动。

### 8.2 查询与收益读路径

| 文件 | 修改 |
|---|---|
| `OcBenefitRankingQuery` | 删除类内对两个常量的读取；构造函数增加入参 `reassignFactionList`、`factionOcExclusions`（或提供静态工厂接收门面），模型不依赖Spring与数据源 |
| `OcBenefitQueryStrategyImpl`、`OcBenefitRankStrategyImpl`、`TornFactionOcBenefitDAO` | 构造 `OcBenefitRankingQuery` 时注入门面派生结果 |
| `TornOcBatchIncomeService` | 名单与扫描起点改读门面；删除 `resolveIncomeStartTime` 内PN/NOV分支，保留方法签名与纯函数语义（委托门面） |
| `TornOcIncomeService` | 帮派集合循环与 `querySettlementLeaves` 名单改读门面 |
| `TornOcIncomeTransactionWorker` | 名单改读门面 |
| `TornFactionOcManager` | 帮派集合判断改读门面 |

### 8.3 推荐与模式读路径

| 文件 | 修改 |
|---|---|
| `TornOcRecommendManager` | `checkIsReassignRecommended` 两处常量改读门面；`calcReassignRecommendScore` 按门面模式取系数：EQUAL固定 `BigDecimal.ONE`，COEFFICIENT查系数表 |
| `TornOcRecommendService`、`TornOcAssignService` | 名单改读门面 |
| `TornOcWorkingHourService` | `isNoCoefficient` 硬编码改为门面 `getIncomeMode` 判等 `EQUAL` |
| `OcBenefitQueryStrategyImpl` | `createDisplayConfig` 改按门面模式决定是否展示岗位系数列 |
| `TornSettingOcCoefficientManager` | 新增 `hasCompleteCoefficients` 公共方法，与 `getCoefficient` 共用解析私有方法 |

---

## 9. 计划修改文件汇总

### 9.1 生产代码

新增：6.2节全部14项。

修改：

1. `constants/torn/TornConstants.java`（8.1）；
2. `constants/bot/BotCommands.java`（7.4）；
3. `torn/manager/setting/TornSettingOcCoefficientManager.java`（8.3）；
4. `torn/manager/faction/crime/TornFactionOcManager.java`（8.2）；
5. `torn/manager/faction/crime/recommend/TornOcRecommendManager.java`（8.3）；
6. `torn/service/faction/oc/income/TornOcBatchIncomeService.java`（8.2）；
7. `torn/service/faction/oc/income/TornOcIncomeService.java`（8.2）；
8. `torn/service/faction/oc/income/TornOcIncomeTransactionWorker.java`（8.2）；
9. `torn/service/faction/oc/income/TornOcWorkingHourService.java`（8.3）；
10. `torn/service/faction/oc/recommend/TornOcRecommendService.java`（8.3）；
11. `torn/service/faction/oc/recommend/TornOcAssignService.java`（8.3）；
12. `torn/model/faction/crime/income/OcBenefitRankingQuery.java`（8.2）；
13. `repository/dao/faction/oc/TornFactionOcBenefitDAO.java`（8.2）；
14. `napcat/strategy/faction/crime/benefit/OcBenefitQueryStrategyImpl.java`（8.2、8.3）；
15. `napcat/strategy/faction/crime/benefit/OcBenefitRankStrategyImpl.java`（8.2）；
16. `db.changelog-master.yaml`（5.5）。

### 9.2 测试代码

新增（4个测试类，见第10章）；适配（不改断言，仅改数据源stub为门面）：`OcBenefitQueryStrategyImplTest`、`OcBenefitRankingQueryTest`、`TornOcIncomeServiceTest`、`TornOcIncomeTransactionWorkerTest`、`TornOcBatchIncomeReentrancyTest`、`TornOcBatchIncomeQueryCountTest`、`TornOcBatchIncomeServiceTest`。

被修改的每个生产文件按规范更新文件头 `@version`（以pom为准），`@since` 保持原值。

---

## 10. 测试方案（收敛原则）

同一规则一个主证据层级；新增4个测试类，不铺矩阵。

### 10.1 TornSettingOcReassignManagerTest（纯单元，mock DAO列表）

覆盖派生规则各一条主路径：名单组装、NULL/日期分组归并的排除规则、帮派集合、扫描起点取最小值与全NULL回落当月月初、模式默认值。

### 10.2 OcReassignConfigServiceTest（Spring/Mockito混合，真实校验器）

| 用例 | 断言 |
|---|---|
| COEFFICIENT帮派添加成功 | 范围行+规划行落库、缓存驱逐注册、回执内容 |
| EQUAL帮派添加成功并跳过系数校验 | 不调用系数完整性方法 |
| COEFFICIENT缺系数拒绝 | 不落库、回执缺失岗位清单（覆盖CCRC口径：帮派自有行存在但无该OC行时必须判不完整） |
| 链尾缺前序拒绝 | 不落库、回执缺失前序名 |
| 目录缺失拒绝 | 提示先OC校准 |
| 幂等拒绝 | 已存在enabled行时不重复写 |
| 开启帮派 | 落库；重复开启拒绝 |

### 10.3 OcReassignSeedEquivalenceTest（@SpringBootTest只读，等价性生命线）

Liquibase种子随启动落库后，硬编码断言：6帮派模式映射、每帮派范围行名称与生效时间与5.3节表格逐行一致。数据库测试不写库，无需回滚。

### 10.4 指令策略测试（OcReassignStrategyTest，一个类覆盖三个策略）

参数解析（默认当月1日、超管帮派ID前缀、非超管带前缀拒绝）、roleType/isNeedSa声明、成功路径转发编排服务。

### 10.5 既有测试适配

7.2节列出的既有测试类仅替换常量stub为门面stub，断言原样通过——这本身就是"迁移等价"的组成部分，禁止借适配之名改断言。

---

## 11. 代码质量要求

1. 遵守 `.ai/knowledge/java_coding_style.md` 与Sonar硬门禁（方法复杂度≤15、参数≤7、单文件≤800行）；
2. 校验与解析逻辑禁止复制：系数解析两出口共用私有方法；排除规则只由门面一处生成；
3. 不引入新依赖；不做无关格式化；
4. 新增DO/枚举/公共方法完整Javadoc；指令回执文案按7.5逐字实现（含半角标点）；
5. 换行符CRLF，提交前对变更文件执行unix2dos；
6. 不用异常控制校验流程（校验器返回结果record）。

---

## 12. 实施验证命令

```text
JAVA_HOME="C:\Program Files\Java\jdk-21" mvn.cmd compile -q -DskipTests
JAVA_HOME="C:\Program Files\Java\jdk-21" mvn.cmd test -Dtest="TornSettingOcReassignManagerTest,OcReassignConfigServiceTest,OcReassignSeedEquivalenceTest,OcReassignStrategyTest"
JAVA_HOME="C:\Program Files\Java\jdk-21" mvn.cmd test -Dtest="OcBenefitQueryStrategyImplTest,OcBenefitRankingQueryTest,TornOcIncomeServiceTest,TornOcIncomeTransactionWorkerTest,TornOcBatchIncomeReentrancyTest,TornOcBatchIncomeQueryCountTest,TornOcBatchIncomeServiceTest"
```

实施为L3（收益口径），发布前按项目门禁执行全量测试。

---

## 13. 上线步骤

1. 部署含changeSet的版本（种子随Liquibase自动落库）；
2. 执行只读SQL验证种子（见14章），确认6帮派模式与31行范围数据；
3. 抽验排行榜口径：PN/NOV生效边界前后普通收益与大锅饭收益与上线前一致；
4. 后续新OC/新帮派一律走指令：超管 `OC大锅饭开启`、指挥官/超管 `OC大锅饭添加`；
5. 直接改库维护系数/档案/链/岗位校准后，执行"刷新缓存"指令或等待部署重启。

---

## 14. 只读验收SQL

```sql
-- 14.1 帮派模式
SELECT faction_id, income_mode, enabled FROM torn_setting_oc_reassign_faction WHERE deleted = 0 ORDER BY faction_id;
-- 期望: 6行, PN/HP/CCRC/SH=COEFFICIENT, NOV/BSU=EQUAL, 全部enabled

-- 14.2 范围行计数与生效时间分布
SELECT faction_id, count(*) AS total,
       count(*) FILTER (WHERE effective_from IS NULL) AS always_rows,
       min(effective_from) AS earliest
FROM torn_setting_oc_reassign_oc WHERE deleted = 0 AND enabled = true GROUP BY faction_id ORDER BY faction_id;
-- 期望: PN=8(always 6, earliest 2026-08-01), NOV=11(always 4, earliest 2026-07-01), HP/CCRC/SH/BSU=4(always 4, earliest NULL)

-- 14.3 名单等价抽验(以PN为例)
SELECT oc_name, rank, effective_from FROM torn_setting_oc_reassign_oc
WHERE deleted = 0 AND faction_id = 20465 ORDER BY oc_name;

-- 14.4 指令添加后验收(以BSU高阶为例)
SELECT oc_name, effective_from FROM torn_setting_oc_reassign_oc
WHERE deleted = 0 AND faction_id = 11796 AND enabled = true ORDER BY rank, oc_name;
SELECT oc_name, enabled FROM torn_setting_faction_oc_plan WHERE faction_id = 11796 ORDER BY rank, oc_name;
-- 期望: 范围行与规划行同步出现

-- 14.5 补算完成后待算叶子为0(沿用reassign-range方案18.1口径)
```

---

## 15. 风险与处理

| 风险 | 处理 |
|---|---|
| 种子与常量不等价导致排行榜口径漂移 | 10.3等价性测试硬编码对照5.3表格；上线步骤3抽验 |
| CCRC系数自有行不回落faction_id=0被漏判 | 校验复用运行时解析口径；10.2专门用例覆盖 |
| 指令重复提交/并发 | 幂等拒绝 + 单实例JVM契约；批量收益防重入既有 |
| 普通收益被误删 | 本需求无任何删除普通收益动作；排除规则仅读侧派生 |
| 规划校验器(OcPlanCatalogValidator)系数口径为"帮派或faction_id=0"较运行时宽松 | 入口已被指令校验拦截，不扩大本次范围；记录为后续建议 |
| 新OC缺规划档案影响新队规划 | 指令回执警告不阻断；档案仍人工维护 |

---

## 16. 明确不做事项

- 不做移除/禁用指令（已生成income与汇总回滚语义复杂，v1明确不做，下线走人工DB+补算预案）；
- 不修改收益计算公式、工时口径、飞书同步链路；
- 不删除/迁移 `torn_faction_oc_benefit`，不新增写侧过滤；
- 不做系数行、规划档案、链配置、岗位校准值的指令化或自动推断（spawn_pool为业务判断）；
- 不引入数据库唯一约束、分布式锁、新依赖；
- 不统一 `OcPlanCatalogValidator` 的系数校验口径（后续建议）。

---

## 17. Review清单

### P0：口径与等价

- [ ] 种子6帮派模式、31行范围数据与5.3逐行一致，等价性测试通过；
- [ ] 迁移后PN/NOV生效边界、扫描起点与常量实现等价（既有边界测试原样通过）；
- [ ] 添加指令对COEFFICIENT帮派缺系数fail-closed（含CCRC口径用例）；
- [ ] 排除规则仅读侧派生，无任何普通收益删除动作。

### P1：调用链与指令

- [ ] 全部常量消费方切换门面，`TornConstants`对应常量与静态块删除干净；
- [ ] 帮派归属取发送者绑定，帮派ID前缀仅超管可用；
- [ ] 添加成功同步写规划行；缓存驱逐在事务提交后；
- [ ] 异步补算复用既有防重入；操作日志含帮派/OC/生效时间/操作人；
- [ ] 推荐评分EQUAL模式系数固定1。

### P2：质量

- [ ] 系数解析两出口共用私有方法，无复制的解析逻辑；
- [ ] 子包规划符合6.1；无重复回执拼装代码；
- [ ] Javadoc、中文@DisplayName、CRLF、@version以pom为准；
- [ ] 新增测试限10.1~10.4四类，无同规则重复矩阵；
- [ ] 编译、聚焦测试、全量测试真实通过；`git diff`与第9章清单一致。

---

## 18. 最终验收标准

1. 实现与本文表结构、种子、派生规则、指令行为、文案逐项一致；
2. 等价性测试与既有边界测试全部通过，排行榜与个人收益口径零漂移；
3. 指令三角色路径（超管开启、指挥官添加、超管跨帮派添加）在验收环境真实走通并回执正确；
4. BSU添加高阶OC全程无系数步骤；CCRC缺自有系数行时添加被拒绝；
5. AI技术专家Review通过并更新本文实现基线、实施状态和验收记录。
