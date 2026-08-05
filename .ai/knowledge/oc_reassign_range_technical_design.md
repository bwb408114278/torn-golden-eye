# PN/NOV大锅饭OC范围与历史收益补算技术实施方案

## 1. 文档信息

- 文档类型：最终技术实施方案
- 适用项目：Golden-Eye
- 适用版本：1.2.12及以上
- 设计日期：2026-08-03
- 维护人：Bai
- 技术方案负责人：AI技术专家
- 设计状态：已通过业务审核，待工程师实施
- 当前实现基线：`f471de0`
- 当前分支：`reassign-range`
- 实施状态：未实施
- 技术验收状态：待实施后Review

> 本文是本需求唯一技术实施基线。开发人员只能按照本文修改代码、测试和运维步骤，不得自行扩大或缩小业务范围、改变日期边界、改变收益统计口径，亦不得以当前实现反向覆盖本文。实施完成后由AI技术专家进行Review，并更新本文的实现基线、实施状态和验收记录。

---

## 2. 需求目标

### 2.1 PN大锅饭OC范围

PN在现有大锅饭OC范围中新增：

- `Lock Stock`
- `Hostile Takeover`

新增范围生效时间：

```text
2026-08-01 00:00:00
```

PN在生效时间以前完成的同名OC继续作为普通收益参与排行榜；生效时间及以后完成的目标OC通过大锅饭收益参与排行榜。

### 2.2 NOV大锅饭OC范围

NOV在现有大锅饭OC范围中新增：

- `Lock Stock`
- `Stacking the Deck`
- `Manifest Cruelty`
- `Gone Fission`
- `Ace in the Hole`
- `Hostile Takeover`
- `Crane Reaction`

新增范围生效时间：

```text
2026-07-01 00:00:00
```

NOV在生效时间以前完成的同名OC继续作为普通收益参与排行榜；生效时间及以后完成的目标OC通过大锅饭收益参与排行榜。

### 2.3 总体收益口径

所有收益都必须进入排行榜，但每条收益在同一统计范围内只能命中一个来源：

```text
普通收益计入次数 + 大锅饭收益计入次数 = 1
```

禁止出现：

- 生效时间以前的普通收益因名单扩大而从排行榜消失；
- 生效时间及以后的目标OC同时按普通收益和大锅饭收益重复计入；
- 为适配本需求删除、迁移或改写飞书普通收益历史数据；
- 为本需求新增收益模式字段、迁移状态机、备份表或一次性收益迁移服务。

---

## 3. 已确认的现状与调用链

### 3.1 “OC校准”真实职责

Bot指令：

```text
OC校准 [页数]
```

实际调用链：

```text
OcCheckStrategyImpl.handle()
  → TornFactionOcService.refreshOc(pageSize)
  → TornFactionOcRefreshManager.refreshOc(pageSize, factionId)
  → Torn API读取可用OC和历史完成OC
  → TornFactionOcManager.updateOc(...)
  → 更新torn_faction_oc与torn_faction_oc_slot
  → TornOcBatchIncomeService.batchCalculateIncome(factionId, execTime)
  → TornOcIncomeService.calculateAndSaveIncome(finalOc)
  → 生成torn_faction_oc_income
  → 重算torn_faction_oc_income_summary
```

因此，只要校准页数覆盖目标历史记录，现有OC校准功能能够完成历史OC、岗位、大锅饭明细和月度汇总的补齐，不需要建设额外迁移服务。

### 3.2 飞书普通收益链路

普通OC收益由另一条独立链路产生：

```text
TornFactionOcBenefitService
  → 飞书多维表API
  → torn_faction_oc_benefit
```

本需求不修改该同步链路，不删除或重写`torn_faction_oc_benefit`中的既有数据。

### 3.3 当前大锅饭扫描范围

`TornOcBatchIncomeService.batchCalculateIncome()`当前使用：

```text
execTime所在月份第一天
```

作为大锅饭收益扫描下限。当前时间为2026-08，因此现有实现只会生成2026-08及以后完成的OC收益，不会因OC校准读取多页历史而自动生成更早月份的大锅饭收益。

由此确定：

- PN要求从2026-08开始，现有“当前月”行为与业务起点一致；
- NOV要求从2026-07开始，现有“当前月”行为会漏算2026-07；
- 只需给批量收益服务增加帮派级扫描起点，无需复杂迁移基础设施。

---

## 4. 总体技术方案

本次采用最小侵入方案：

1. 扩大PN、NOV的`ROTATION_OC_NAME`当前大锅饭名单；
2. 大锅饭历史补算按帮派固定起点扫描：PN从2026-08-01，NOV从2026-07-01；
3. 继续通过“OC校准”拉取历史OC并触发大锅饭生成；
4. 排行榜与个人收益查询保持现有普通收益、大锅饭收益双数据源结构；
5. 将静态“按名称排除普通收益”升级为“按帮派、OC名称和生效时间排除”；
6. 使用现有`torn_setting_oc_chain`配置避免分页校准时成功链父节点被提前结算；
7. 不改数据库Schema，不增加Liquibase变更，不迁移飞书普通收益数据。

---

## 5. 大锅饭名单与常量设计

### 5.1 新增OC名称常量

在`TornConstants`中新增并复用：

```text
OC_NAME_LOCK_STOCK
OC_NAME_MANIFEST_CRUELTY
OC_NAME_GONE_FISSION
OC_NAME_HOSTILE_TAKEOVER
OC_NAME_CRANE_REACTION
```

已有常量继续复用：

```text
OC_NAME_STACKING_THE_DECK
OC_NAME_ACE_IN_THE_HOLE
```

Service、查询对象和测试中不得重复硬编码上述英文名称。

### 5.2 更新PN当前名单

PN现有名单保持不变，并追加：

```text
Lock Stock
Hostile Takeover
```

### 5.3 更新NOV当前名单

NOV现有名单保持不变，并追加：

```text
Lock Stock
Stacking the Deck
Manifest Cruelty
Gone Fission
Ace in the Hole
Hostile Takeover
Crane Reaction
```

### 5.4 名单职责边界

更新后的`ROTATION_OC_NAME`继续用于：

- 当前OC推荐与分配；
- 大锅饭批量计算候选名称过滤；
- 收益榜构造帮派级普通收益排除规则。

但对于普通收益排除，禁止再把“当前完整名单”直接解释为“所有历史月份都排除”。新增范围必须结合生效时间判断。

---

## 6. 生效边界模型

### 6.1 两类排除规则

普通收益排除规则拆为：

1. **原有大锅饭名单**：沿用既有历史口径，不附加本次新增日期；
2. **本次新增名单**：仅当OC完成时间达到帮派生效时间时，才从普通收益数据源排除。

### 6.2 PN新增规则

```text
factionId = 20465
ocName IN ('Lock Stock', 'Hostile Takeover')
ocFinishTime >= 2026-08-01 00:00:00
```

只有三项同时满足时，普通收益才被排除。

### 6.3 NOV新增规则

```text
factionId = 16335
ocName IN (
  'Lock Stock',
  'Stacking the Deck',
  'Manifest Cruelty',
  'Gone Fission',
  'Ace in the Hole',
  'Hostile Takeover',
  'Crane Reaction'
)
ocFinishTime >= 2026-07-01 00:00:00
```

只有三项同时满足时，普通收益才被排除。

### 6.4 统一时间边界

Java中使用`LocalDateTime`常量，边界为左闭区间：

```text
完成时间 < 生效时间  → 普通收益保留
完成时间 >= 生效时间 → 普通收益排除，由大锅饭收益承接
```

数据库比较必须使用：

```sql
oc_finish_time >= #{effectiveFrom}
```

禁止使用`>`造成生效时刻边界遗漏。

---

## 7. 大锅饭历史补算设计

### 7.1 修改目标

修改：

```text
TornOcBatchIncomeService.batchCalculateIncome(long factionId, LocalDateTime execTime)
```

将统一的`currentMonth`扫描下限替换为帮派级下限。

### 7.2 扫描起点

| 帮派 | 扫描起点 |
|---|---|
| PN（20465） | `2026-08-01 00:00:00` |
| NOV（16335） | `2026-07-01 00:00:00` |
| 其他大锅饭帮派 | `execTime`所在月份第一天，保持现有行为 |

建议增加私有纯函数：

```text
resolveIncomeStartTime(factionId, execTime)
```

该方法不得读取数据库、系统时间或外部配置，确保边界测试可确定复现。

### 7.3 叶子节点识别规则

当前实现通过数据库中“当前不存在后继OC”判断叶子节点。该条件对已经完整同步的链成立，但在分页校准过程中，父节点可能先于后继节点被某一页返回。若此时立即启动收益计算，会把成功的链根节点暂时误判为最终节点；后续真正叶子节点同步后又会再次计算整条链。

因此，本次实施必须将叶子节点判断升级为：

```text
数据库中不存在后继OC
AND
当前OC不是配置链中仍要求成功后继节点的父节点
```

链关系必须复用`torn_setting_oc_chain`，不得在Service中重复硬编码：

```text
Stacking the Deck → Ace in the Hole
Lock Stock → Hostile Takeover
Manifest Cruelty → Gone Fission → Crane Reaction
```

具体语义：

- 成功的配置链父节点，在后继节点尚未同步时暂不计算，等待后续校准页或后续刷新；
- 失败的OC按当前规则作为终点，可以计算自身损失；
- 已同步真实后继节点时，继续由最终叶子节点触发整条`previousOcId`链计算；
- 未配置为链父节点的独立成功OC继续作为叶子处理。

建议在批量收益查询前一次性加载配置链父节点集合，在JVM中二次过滤候选，或通过Mapper/SQL联查配置表；禁止对每条OC逐条查询配置，避免N+1。

### 7.4 保留的现有查询条件

必须继续保留：

- `faction_id = factionId`；
- 状态属于完成状态；
- 名称属于当前帮派大锅饭名单；
- `executed_time >= startTime`；
- 不存在当前OC的income记录；
- 不存在已同步后继OC；
- 成功OC不是配置链中仍要求后继节点的父节点。

### 7.5 幂等性

现有：

```sql
NOT EXISTS (
  SELECT 1
  FROM torn_faction_oc_income
  WHERE oc_id = torn_faction_oc.id
)
```

只检查当前候选节点。生产数据中已发现同一成功链根节点发生重复income记录：根节点先被当作暂无后继的叶子计算，真正叶子同步后又回溯整条链再次写入；两个异步刷新重叠还会进一步扩大重复窗口。

本次修改扩大历史扫描范围，会放大该并发窗口。因此工程师必须同时完成以下低侵入加固：

1. `TornOcBatchIncomeService`增加JVM内按帮派防重入；
2. 同一帮派同一时刻只允许一个批量收益计算流程；
3. 不同帮派可以并行；
4. 防重入标记必须在`finally`释放；
5. 抢占失败时记录`debug`或`info`并直接返回，不阻塞等待。

推荐使用：

```text
ConcurrentHashMap<Long, AtomicBoolean>
```

以`factionId`为键执行`compareAndSet(false, true)`。

Golden-Eye当前单实例部署，本次不引入分布式锁。

### 7.6 事务边界要求

现有`batchCalculateIncome()`捕获单个OC异常后继续执行，因此方法级事务无法为单条OC提供独立回滚语义。实施时不得扩大事务重构范围，但必须保证：

- 单个OC生成失败不能删除或修改飞书普通收益；
- 已生成的其他OC收益不因后续OC失败而被回滚；
- 日志包含`factionId`、`ocId`、`ocName`和异常；
- 防重入标记不因异常永久占用。

若开发过程中发现单条OC失败会留下部分income，必须单独报告，不得在本需求中擅自大改事务架构。

---

## 8. OC校准执行设计

### 8.1 指令语义保持不变

不修改：

```text
OC校准 [页数]
```

的命令格式、权限和返回文案。

### 8.2 执行方式

代码部署后，由管理员执行足够页数的OC校准，使Torn API返回范围覆盖：

- PN：2026-08-01以来的历史完成OC；
- NOV：2026-07-01以来的历史完成OC。

校准会补齐：

- `torn_faction_oc`；
- `torn_faction_oc_slot`；
- `torn_faction_oc_income`；
- `torn_faction_oc_income_summary`。

### 8.3 页数确定

技术方案不写死校准页数。实施和上线时先根据Torn接口实际分页大小以及目标月份最早记录所在页确定页数，再执行命令。

验收必须证明返回范围已经覆盖：

```text
NOV最早目标时间 <= 2026-07-01
PN最早目标时间 <= 2026-08-01
```

不得仅凭“执行了若干页”认定补算完整。

### 8.4 异步完成确认

`TornFactionOcManager.updateOc()`通过共享执行器异步提交大锅饭计算。`OC校准`主流程结束不等于全部income与summary已经完成。

上线验收必须等待：

- 目标帮派批量收益日志完成；
- 待计算叶子OC查询结果为0；
- 月度汇总完成更新。

禁止在Bot刚回复“OC数据校准完成”后立即判定收益补算完成。

---

## 9. 排行榜查询设计

### 9.1 保持现有数据源

排行榜继续使用：

```text
普通收益：torn_faction_oc_benefit
大锅饭收益：torn_faction_oc_income_summary
```

不修改最终按用户、帮派分组及排序的总体结构。

### 9.2 `OcBenefitRankingQuery`调整

当前`FactionOcExclusion`仅包含：

```text
factionId
ocList
```

需要升级为能够表达两类规则的数据结构。建议结构：

```text
factionId
alwaysExcludedOcList
scheduledExclusions
```

其中每个`scheduleExclusion`至少包含：

```text
ocList
effectiveFrom
```

或者拆成扁平规则：

```text
factionId
ocList
effectiveFrom（null表示始终排除）
```

推荐扁平规则，Mapper动态SQL更直接。对应record/POJO字段必须有完整Javadoc。

### 9.3 普通收益SQL语义

对于每个大锅饭帮派，普通收益只排除满足任一规则的记录：

```text
同一factionId
AND ocName属于规则名单
AND（规则无生效时间 OR ocFinishTime >= 生效时间）
```

SQL应表达为：

```sql
NOT (
  (b.faction_id = ? AND b.oc_name IN (原有名单))
  OR
  (b.faction_id = 20465
   AND b.oc_name IN ('Lock Stock', 'Hostile Takeover')
   AND b.oc_finish_time >= '2026-08-01 00:00:00')
  OR
  (b.faction_id = 16335
   AND b.oc_name IN (NOV新增名单)
   AND b.oc_finish_time >= '2026-07-01 00:00:00')
)
```

实际SQL必须使用MyBatis参数绑定，不允许拼接日期或OC名称。

### 9.4 查询类型兼容

以下查询必须共用同一普通收益排除规则：

- 指定帮派收益榜；
- SMTH总收益榜；
- 同期收益榜；
- 用户个人排名。

禁止只修复`queryBenefitRanking`而遗漏`queryBenefitUserRanking`或`queryCohortBenefitRanking`。

### 9.5 大锅饭帮派榜行为

指定大锅饭帮派时，现有查询会：

- 从`income_summary`读取大锅饭收益；
- 从`benefit`读取不属于大锅饭范围的普通收益。

该结构保持不变。新增日期规则只决定普通收益表中哪些记录被排除，不改变大锅饭汇总计算。

---

## 10. 个人收益查询设计

### 10.1 当前风险

`OcBenefitQueryStrategyImpl.queryBenefitList()`当前使用：

```text
notIn(shouldCalcReassign, ocName, ROTATION_OC_NAME[factionId])
```

扩大名单后会无条件排除生效时间以前的同名普通收益。

### 10.2 修改要求

个人收益查询必须与排行榜使用相同排除规则：

- 原有大锅饭名单始终排除普通收益；
- PN新增名单仅从2026-08-01排除；
- NOV新增名单仅从2026-07-01排除。

由于MyBatis-Plus LambdaQuery难以清晰表达多组带日期的`NOT (A OR B OR C)`，推荐新增Mapper查询方法，由XML复用排行榜同一SQL片段，而不是在Java中拼接`apply` SQL。

### 10.3 SQL复用

建议在`TornFactionOcBenefitMapper.xml`中抽取统一片段，例如：

```text
reassignOrdinaryBenefitExclusion
```

排行榜普通数据源和个人普通收益明细查询都引用该片段，避免两个入口的日期边界以后再次漂移。

### 10.4 展示保持不变

不修改：

- 大锅饭明细表格；
- 普通收益表格；
- NOV/BSU不展示岗位系数列的现有行为；
- 消息文案和图片布局。

本次只调整普通收益明细的筛选口径。

---

## 11. 飞书普通收益同步边界

`TornFactionOcBenefitService`保持现状：

- 继续从飞书API同步所有普通收益记录；
- 继续按`(ocId, userId)`幂等保存；
- 不因为大锅饭名单扩大而停止同步；
- 不删除已同步记录；
- 不新增按大锅饭名单过滤的写入逻辑。

原因：现有排行榜和个人查询通过读侧排除大锅饭范围，数据库保留飞书普通收益是既有数据模型。本需求只修正读侧历史边界和大锅饭补算范围。

---

## 12. 数据库与性能设计

### 12.1 Schema

本次不新增、删除或修改任何数据库表、字段、索引和约束，因此：

- 无Liquibase changeSet；
- 无历史数据UPDATE/DELETE；
- 无数据迁移脚本；
- 无备份表。

### 12.2 查询规模

当前本地生产同步数据表明，单帮派目标历史范围为百级OC，收益明细为千级至万级，现有月度查询规模可接受。

排行榜SQL增加的规则数量固定且很小，不引入逐行子查询或N+1。

### 12.3 索引

本次不新增索引。实施后Review若发现真实`EXPLAIN ANALYZE`出现明显性能退化，再单独提出索引方案，不在本需求中提前建设。

---

## 13. 计划修改文件

### 13.1 生产代码

1. `src/main/java/pn/torn/goldeneye/constants/torn/TornConstants.java`
   - 增加OC名称常量；
   - 更新PN、NOV当前大锅饭名单；
   - 增加原有名单/带日期新增规则所需的不可变配置。

2. `src/main/java/pn/torn/goldeneye/torn/service/faction/oc/income/TornOcBatchIncomeService.java`
   - 增加帮派级历史扫描起点；
   - 使用OC链配置排除尚未生成后继节点的成功链父节点；
   - 增加按帮派JVM内防重入；
   - 保留现有叶子节点和income幂等过滤。

3. `src/main/java/pn/torn/goldeneye/repository/dao/setting/TornSettingOcChainDAO.java`
   - 复用现有链配置批量读取能力；
   - 若现有DAO已满足需求则不新增方法，仅由Service批量加载。

4. `src/main/java/pn/torn/goldeneye/torn/model/faction/crime/income/OcBenefitRankingQuery.java`
   - 将仅名称排除模型升级为带生效时间的规则模型；
   - 补齐新增/修改字段与公共构造器Javadoc。

5. `src/main/java/pn/torn/goldeneye/repository/mapper/faction/oc/TornFactionOcBenefitMapper.java`
   - 如个人明细改为Mapper查询，新增对应公共方法及完整Javadoc。

6. `src/main/resources/mapper/faction/oc/TornFactionOcBenefitMapper.xml`
   - 抽取统一普通收益排除SQL片段；
   - 排行榜三类查询共用规则；
   - 新增个人普通收益明细查询。

7. `src/main/java/pn/torn/goldeneye/repository/dao/faction/oc/TornFactionOcBenefitDAO.java`
   - 如新增Mapper方法，提供对应DAO封装及Javadoc。

8. `src/main/java/pn/torn/goldeneye/napcat/strategy/faction/crime/benefit/OcBenefitQueryStrategyImpl.java`
   - 个人普通收益查询改用统一日期边界规则；
   - 展示行为保持不变。

### 13.2 测试代码

至少修改或新增：

1. `TornOcBatchIncomeServiceTest`
2. `OcBenefitRankingQueryTest`
3. `TornFactionOcBenefitMapper`相关数据库测试
4. `OcBenefitQueryStrategyImpl`查询边界测试
5. 大锅饭批量计算防重入测试

测试类必须有职责Javadoc；测试类及每个`@Test`必须有中文`@DisplayName`。

---

## 14. 测试方案

### 14.1 名单测试

断言：

- PN包含`Lock Stock`、`Hostile Takeover`；
- NOV包含全部七个新增OC；
- 其他帮派名单不变；
- 不存在重复名称。

### 14.2 批量收益日期边界

| 帮派 | 完成时间 | 预期 |
|---|---|---|
| PN | 2026-07-31 23:59:59 | 不生成本次新增OC大锅饭收益 |
| PN | 2026-08-01 00:00:00 | 生成 |
| NOV | 2026-06-30 23:59:59 | 不生成本次新增OC大锅饭收益 |
| NOV | 2026-07-01 00:00:00 | 生成 |
| 其他帮派 | 上月 | 保持现有当前月过滤，不生成 |

### 14.3 链式OC测试

覆盖：

- `Stacking the Deck → Ace in the Hole`；
- `Lock Stock → Hostile Takeover`；
- `Manifest Cruelty → Gone Fission → Crane Reaction`；
- 仅叶子节点触发；
- 成功链父节点在后继尚未同步时不得提前计算；
- 失败链父节点可以作为终点计算损失；
- 整条链每个节点生成对应参与人明细；
- 已存在叶子income时不重复生成；
- 已存在根节点income但叶子不存在的异常数据必须能够被识别并记录，不得静默继续制造重复根节点明细。

### 14.4 防重入测试

同一帮派并发调用两次：

- 只有一个流程实际执行查询和保存；
- 另一个流程直接返回；
- 执行异常后标记释放；
- 后续调用可再次执行。

不同帮派并发调用：

- PN与NOV可以并行；
- 不使用全局单一锁串行化所有帮派。

### 14.5 排行榜边界测试

| 帮派 | OC | 时间 | 普通收益 | 大锅饭收益 |
|---|---|---|---:|---:|
| PN | Lock Stock | 2026-07-31 | 计入 | 不计入 |
| PN | Lock Stock | 2026-08-01 | 排除 | 计入 |
| PN | Hostile Takeover | 2026-07-31 | 计入 | 不计入 |
| PN | Hostile Takeover | 2026-08-01 | 排除 | 计入 |
| NOV | Stacking the Deck | 2026-06-30 | 计入 | 不计入 |
| NOV | Stacking the Deck | 2026-07-01 | 排除 | 计入 |
| NOV | Ace in the Hole | 2026-06-30 | 计入 | 不计入 |
| NOV | Ace in the Hole | 2026-07-01 | 排除 | 计入 |
| 其他帮派 | 同名OC | 任意 | 保持原逻辑 | 保持原逻辑 |

每组断言：

```text
普通来源命中数 + 大锅饭来源命中数 = 1
```

### 14.6 查询入口一致性

使用同一批夹具验证：

- 指定帮派榜；
- SMTH总榜；
- 同期榜；
- 用户排名；
- 用户个人收益明细。

所有入口对同一条普通收益的排除结论必须一致。

### 14.7 回归测试

必须回归：

- 原有PN大锅饭OC；
- 原有NOV大锅饭OC；
- HP、CCRC、SH、BSU现有收益逻辑；
- 普通帮派收益榜；
- 飞书普通收益同步幂等；
- OC推荐和分配对扩大名单的行为。

---

## 15. 代码质量要求

1. 遵守`.ai/knowledge/java_coding_style.md`；
2. 不引入新依赖；
3. 不修改无关文件；
4. 不用字符串散落表示OC名称和日期；
5. 时间常量使用`LocalDateTime`，禁止每次解析字符串；
6. 新增POJO/record字段具备完整Javadoc；
7. 非`@Override`公共方法具备用途、参数、返回值和异常说明；
8. 先完成IDEA/Sonar/Javadoc规范清理，再进行功能测试；
9. 禁止用抑制注解绕过质量问题；
10. XML动态SQL应抽取复用片段，禁止复制三套相同日期规则。

---

## 16. 实施验证命令

工程师至少执行：

```text
JAVA_HOME="C:\Program Files\Java\jdk-21" mvn.cmd compile -q -DskipTests
```

聚焦测试应包含实际修改的测试类，例如：

```text
JAVA_HOME="C:\Program Files\Java\jdk-21" mvn.cmd test -Dtest="TornOcBatchIncomeServiceTest,OcBenefitRankingQueryTest,..."
```

随后运行相关OC收益测试包。若项目全量测试依赖外部环境，必须分别报告：

- 聚焦测试结果；
- OC收益相关回归结果；
- 全量测试结果或明确阻断原因。

不得只以编译成功替代功能测试。

---

## 17. 上线步骤

1. 部署通过Review的代码；
2. 确认新版本中PN/NOV名单和日期规则正确；
3. 暂时避免管理员并发触发多个OC刷新类命令；
4. 执行足够页数的`OC校准`，覆盖NOV 2026-07-01以来、PN 2026-08-01以来的完成OC；
5. 等待异步大锅饭计算日志完成；
6. 执行只读SQL确认待计算叶子节点为0；
7. 确认NOV `2026-07`、`2026-08`月度汇总已更新；
8. 确认PN `2026-08`月度汇总已更新；
9. 验证生效时间以前的同名普通收益仍进入排行榜；
10. 验证生效时间及以后的目标OC只通过大锅饭收益进入排行榜；
11. 验证PN榜、NOV榜、SMTH榜、同期榜和个人收益明细。

---

## 18. 只读验收SQL要求

实施完成后，工程师应提供等价只读SQL结果。

### 18.1 待计算叶子OC

分别检查：

```text
PN：executed_time >= 2026-08-01
NOV：executed_time >= 2026-07-01
```

目标名称、完成状态、满足第7.3节终点规则、且不存在income的记录数必须为0。成功的配置链父节点在后继尚未同步时属于“等待后继”，不得被误报为待计算终点。

### 18.2 历史普通收益保留

检查：

- PN在2026-08-01以前的`Lock Stock`、`Hostile Takeover`普通收益仍存在；
- NOV在2026-07-01以前的七种新增OC普通收益仍存在。

### 18.3 收益来源互斥

对边界前后样例OC验证：

- 生效前排行榜普通数据源命中；
- 生效后排行榜普通数据源不命中；
- 生效后对应月份大锅饭汇总存在。

### 18.4 重复明细

检查：

```text
oc_id + user_id + position
```

重复记录。实施不得新增新的重复数据；现有历史重复应单独列出，不得混作本次新增。

---

## 19. 风险与处理

### 19.1 校准页数不足

风险：目标历史OC未被Torn API返回，导致income和summary缺失。

处理：按最早返回时间证明覆盖范围，不以固定页数作为完成证据。

### 19.2 异步收益尚未完成

风险：Bot已回复校准完成，但异步income仍在执行。

处理：以日志、待计算叶子数和summary结果三项共同验收。

### 19.3 并发刷新导致重复income

风险：多个刷新入口同时调用`updateOc()`，各自异步提交批量收益，可能并发选中同一叶子节点。

处理：按帮派JVM内防重入；实施后检查没有新增重复明细。

### 19.4 历史普通收益被静态名单误排除

风险：扩大`ROTATION_OC_NAME`后，生效前同名普通收益消失。

处理：普通收益排除必须同时满足帮派、名称和生效时间。

### 19.5 查询入口规则漂移

风险：排行榜正确，但个人收益明细仍按完整名单无日期排除。

处理：XML抽取统一SQL片段，所有读入口复用。

---

## 20. 明确不做事项

本需求不做：

- 新增OC实例收益模式字段；
- 新增收益迁移状态机；
- 新增一次性历史收益迁移服务；
- 删除或迁移`torn_faction_oc_benefit`；
- 修改飞书普通收益同步逻辑；
- 新增数据库表、字段、索引或Liquibase changeSet；
- 引入Redis锁、ShedLock或其他分布式锁；
- 新增或硬编码另一套OC链关系，链识别必须复用`torn_setting_oc_chain`；
- 重构现有收益分配公式；
- 修改个人收益图片布局和消息文案；
- 修改非PN/NOV帮派的大锅饭业务范围。

---

## 21. 实施完成后的Review清单

AI技术专家按以下顺序Review。

### P0：收益正确性

- [ ] PN新增名单完整，生效时间为2026-08-01；
- [ ] NOV新增名单完整，生效时间为2026-07-01；
- [ ] 生效前同名普通收益仍进入排行榜；
- [ ] 生效后目标OC由大锅饭收益承接；
- [ ] 所有收益均入榜且没有双算；
- [ ] NOV 2026-07历史大锅饭数据补齐；
- [ ] PN 2026-08数据补齐；
- [ ] 链式OC只由叶子节点触发；
- [ ] 未新增重复income明细。

### P1：调用链和并发

- [ ] OC校准页数覆盖范围有真实证据；
- [ ] 异步收益完成后才判定补算成功；
- [ ] 同一帮派批量收益具备JVM内防重入；
- [ ] 不同帮派仍可并行；
- [ ] 防重入标记在异常路径释放；
- [ ] 排行榜和个人查询复用同一日期边界规则。

### P2：代码质量和测试

- [ ] 无魔法OC名称或日期；
- [ ] Javadoc完整；
- [ ] 测试类和测试方法具有中文`@DisplayName`；
- [ ] 未引入新依赖；
- [ ] 未修改无关文件；
- [ ] 规范清理先于功能测试；
- [ ] Maven编译、聚焦测试和相关回归真实通过；
- [ ] `git diff`与本文计划修改范围一致。

---

## 22. 最终验收标准

仅当以下条件全部满足，技术验收才能通过：

1. 代码实现与本文日期、名单、查询和补算契约一致；
2. NOV 2026-07以来目标OC的大锅饭明细和汇总完整；
3. PN 2026-08以来目标OC的大锅饭明细和汇总完整；
4. 生效时间以前的同名普通收益仍在所有相关排行榜中可见；
5. 生效时间及以后的目标收益不重复计入；
6. OC校准重复执行保持幂等，不新增重复income；
7. 聚焦测试和相关回归测试通过；
8. AI技术专家Review通过并更新本文实现基线、实施状态和验收记录。

在上述条件满足前，开发人员不得自行宣告功能完成。
