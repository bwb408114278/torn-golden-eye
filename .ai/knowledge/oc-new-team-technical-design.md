# OC新队时间线刷新决策技术实施方案

## 1. 文档信息

- **文档类型：** 长期技术实施方案、开发与Review验收基线
- **适用项目：** Golden-Eye
- **适用版本：** 1.3.0+
- **关联业务文档：** `.ai/knowledge/oc-new-team-final-design.md`
- **关联一次性业务补充：** `.ai/knowledge/oc-new-team-timeline-profit-supplement.md`
- **设计状态：** 已通过业务答卷Review，待工程实施
- **风险等级：** L3（成员资源时间线、已投入链义务、收益选择、既有配置升级）
- **维护职责：** AI技术专家维护本文；开发人员仅按本文修改代码、Schema和测试

> 本文是 `OC新队#保守 / OC新队#均衡 / OC新队#收益` 的唯一长期技术实施基线。开发人员不得以当前实现的“无停转、永久批次隔离、25/50/100容量比例”反向覆盖本文。实施完成后，由AI技术专家按本文进行Review并更新实施基线、证据和验收状态。

---

## 2. 目标、边界与已核验事实

### 2.1 产品目标

命令继续是匿名人工刷新决策器：

```text
g#OC新队#保守
g#OC新队#均衡
g#OC新队#收益
```

在一次真实OC快照上输出：

```text
(普通池刷新次数, 高阶池刷新次数)
```

以及匿名的安全、时间线、价值和重新评估说明。系统只能同步数据和计算建议，不能点击游戏刷新、自动补位、自动入队、移动已有成员或拆队。

### 2.2 已核验事实

1. 生产入口为 `OcNewTeamStrategyImpl.handle()`：生产环境先调用 `TornFactionOcRefreshManager.refreshOc(1, factionId)` 同步Torn数据，再由 `OcNewTeamPlanningFacade` 在单个快照上规划。
2. 当前规划数据已由 `OcPlanningSnapshotLoader` 批量加载；搜索过程不得再访问数据库。
3. `readyTime` 是当前累计准备阶段结束时间，**不是** Recruiting OC 的天然整队完成时间。后续阶段递推固定为：

   ```text
   nextReadyTime = max(joinAt, currentReadyTime) + 24h
   ```

4. `torn_faction_oc_slot` 已有 `join_time`、`progress`，无需为时间线新增原始事实字段；当前样例中 `progress` 为数值型、`ready_time` 为Torn同步的阶段时间。
5. 项目没有历史OC成员能力快照表。因此不得伪造历史全量因果回放；当前真实回放只能证明当前快照事实。
6. 当前数据库配置经只读核验：
   - NOV（16335）启用11个规划节点，其中7个可刷新根；
   - PN（20465）启用8个规划节点，其中6个可刷新根；
   - 范围、档案、链关系和岗位模板必须动态读取，不得在代码中写死上述数量或英文OC名称。
7. `create_oc_planning_catalog` 与 `add_oc_refresh_capacity_policy` 已在数据库执行；不得修改 `src/main/resources/db/changelog/1.0.1-2.0.0/1.2.0/oc-planning.yaml` 的既有changeSet。

### 2.3 明确不做事项

本期不得新增或实现：

- 成员姓名、岗位、加入时间、旧队补位动作等对外输出；
- 自动刷新、自动入队、自动拆队、自动任务平台或分布式锁；
- 无限时间线串行吞吐量、固定安全并行数、无边界成员循环复用；
- 用历史帮派成功率替代当前成员岗位能力；
- 历史能力快照表、计划结果表、任务表、审计流水表或新的外部依赖；
- 修改现有OC收益结算、飞书收益同步、推荐/分配服务的业务职责；
- 删除旧容量比例列或改写已执行Liquibase changeSet；
- 用一次当前快照回放宣称长期生产“已证明不增风险”。

---

## 3. 阶段一冻结业务参数与技术映射

业务文档第10.4节已冻结以下规则。本期将其实现为规划包内单一只读策略对象，不新增数据库配置列：

| 业务规则 | 技术常量/行为 |
|---|---|
| 重新评估操作提前量30分钟 | `OcTimelinePolicy.REPLAN_LEAD = Duration.ofMinutes(30)` |
| 保守模式不主动制造新停转 | `OcPausePolicy` 对保守模式仅接受零新增停转；已发生停转可在硬约束内恢复 |
| 均衡模式单次主动新增停转最多6小时 | `BALANCED_MAX_NEW_PAUSE = Duration.ofHours(6)` |
| 收益模式单次主动新增停转最多12小时 | `PROFIT_MAX_NEW_PAUSE = Duration.ofHours(12)` |
| 已启动高阶链节点不得主动新增停转 | 链义务节点不进入可制造停转的候选集合 |
| 待启动无人OC无固定数量上限 | 全部进入同一有限时间线；任何不可避免过期、集中开工风险或未证明义务都会阻止增加刷新向量 |
| 价值证据不足时不得提高刷新/停转建议 | `ECONOMIC_EVIDENCE_INSUFFICIENT` 风险标记；候选仅用于匿名说明，不参与提高最终向量 |

建议新增：

```text
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcTimelinePolicy.java
```

该类使用私有构造器、`static final` 常量和清晰Javadoc，不散落数字 `30`、`6`、`12`。它只承载本期已冻结的全局业务规则；未来若业务要求帮派级差异化，必须另行审批Schema/配置方案，不得在本期预埋未使用配置。

旧字段 `conservative_capacity_percent`、`balanced_capacity_percent`、`profit_capacity_percent` 保留以兼容已部署表和历史数据，但新规划路径不得读取它们作模式选择，也不得再用25/50/100缩放安全前沿。

---

## 4. 总体架构

### 4.1 保留的生产调用链

```text
OcNewTeamStrategyImpl
  → （仅生产）TornFactionOcRefreshManager.refreshOc
  → OcNewTeamPlanningFacade
  → OcPlanningSnapshotLoader（一次批量读取）
  → OcRefreshInstructionPlanner
  → OcTimelinePlanningEngine（纯内存、无DB/HTTP/Redis写入）
  → OcNewTeamPlanRenderer
```

`OcNewTeamStrategyImpl` 的权限、指令格式、生产同步边界保持不变；不新增第二个策略类或异步入口。

### 4.2 替换当前错误的核心模型

当前 `OcRefreshSafetySolver` 使用 `reservedMemberIds` 对新增OC进行整批永久隔离，并通过 `OcNoPauseRosterMatcher` 规定首人必须现在加入。该实现整体替换为有限事件时间线引擎：

```text
事实快照
→ 既有OC与已启动链义务重建
→ 生成 [snapshotTime, latestReplanAt] 的有限事件窗口
→ 枚举普通/高阶随机结果联合向量
→ 对每个结果推进全局成员区间、待启动义务和链义务
→ 验证连续完成—释放路径与模式停转政策
→ 在已证明安全的候选中按模式/价值选择
→ 输出匿名解释和重新评估窗口
```

### 4.3 资源约束

唯一成员复用硬约束为：

```text
同一成员的实际占用区间不得重叠。
```

允许有限、明确、可审计的复用：A在08:00完成，成员08:00后加入B，B仍在启动窗口内且整个证明窗口满足义务时，允许该成员复用。

禁止无边界串行吞吐推断：没有明确开始、完成、期限和最晚重评估边界时，不能假设成员将来可无限循环完成OC来放大当前刷新次数。

---

## 5. 领域模型与结果契约

### 5.1 新增内部领域对象

在 `src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/` 新增不可变record或枚举。所有record组件必须完整写Javadoc `@param`。

| 文件 | 责任 |
|---|---|
| `OcConfigurationStatus.java` | `VALID`、`INVALID`、`INCOMPLETE`，只描述配置维度。 |
| `OcProofStatus.java` | `PROVEN_SAFE`、`PROVEN_INFEASIBLE`、`UNPROVEN_TIMEOUT`、`UNPROVEN_SEARCH_BUDGET`、`UNPROVEN_HEURISTIC_MISS`、`NOT_EVALUATED`，只描述证明维度。 |
| `OcRiskFlag.java` | `DEADLOCK_RISK`、`HARD_OBLIGATION_AT_RISK`、`EMPTY_OC_EXPIRY_PRESSURE`、`RECOVERABLE_PAUSE_PRESENT`、`ECONOMIC_EVIDENCE_INSUFFICIENT` 等可并存业务风险。 |
| `OcPlanReasonCode.java` | 稳定、匿名、可测试的原因码，例如 `NO_QUALIFIED_MEMBER_BEFORE_DEADLINE`、`NO_REPLACEMENT_LIQUIDITY_ANCHOR`、`CHAIN_MAPPING_AMBIGUOUS`、`RANDOM_OUTCOME_CHANGED`。 |
| `OcMemberInterval.java` | `userId / occupiedFrom / occupiedUntil / source`；表达真实或候选占用区间。 |
| `OcTimelineEvent.java` | 事件时间、事件类型、关联OC实例或匿名义务键；包括完成释放、阶段边界、停转、恢复、首人期限、链后继生成。 |
| `OcTimelineObligation.java` | 已有人OC、已启动链后继、计划内无人OC、条件性随机结果四类义务及其期限、岗位、固定成员。 |
| `OcCommittedChainObligation.java` | 真实 `rootOcId`、链编码、当前节点序号、下一节点、后继可开始时间；每个真实根仅一条。 |
| `OcLiquidityAnchor.java` | 已证明完成事件、释放时间、替换前/后连续性依据；不可绑定为永久固定OC。 |
| `OcTimelineSafetyAssessment.java` | `configurationStatus / proofStatus / riskFlags / lowerBound / reasonCodes / anchors / nextCriticalReleaseAt / proofWindowEnd`。 |
| `OcReplanWindow.java` | `nextReplanAt / latestReplanAt / reasonCodes`。 |
| `OcPauseAssessment.java` | 新增停转时段、恢复事件、是否是已发生停转、是否符合当前模式。 |
| `OcValueEvidence.java` | 完整链/OC价值、样本层级、增量成员人天、预计释放、是否可用于提高建议。 |

不将成员名称、具体岗位或内部排程暴露到 `OcRefreshInstructionPlan` 的对外字段；它们仅停留在纯规划引擎临时状态中。

### 5.2 扩展现有结果对象

修改：

```text
OcRefreshSafetyResult.java
OcRefreshInstructionPlan.java
OcRefreshPlanningContext.java
```

要求：

1. 不再以单一 `boolean lowerBound + warnings` 表达全部状态；
2. `OcRefreshSafetyResult` 持有 `OcTimelineSafetyAssessment`、联合安全前沿、求解耗时及模式无关的候选时间线摘要；
3. `OcRefreshInstructionPlan` 增加匿名字段：配置状态、证明状态、风险标记、原因码、下一关键释放、是否允许/选择可恢复停转、`OcReplanWindow`、价值证据等级；
4. `lowerBound` 保留为“已证明刷新向量下界”的专属语义，不与配置无效、卡死风险或超时混用；
5. 兼容构造器仅可用于机械迁移；完成后删除旧语义构造器，防止调用方遗漏新状态。

### 5.3 证明状态的严格规则

`PROVEN_INFEASIBLE` 仅可在本次有限证明窗口内由以下任一条件产生：

- 硬岗位能力缺失；
- 硬期限前人数或占用区间存在确定性矛盾；
- 已启动后继义务确定无法履约；
- 完整、无截断的精确可行性检查证明无解。

搜索超时、节点预算耗尽、固定顺序失败、启发式失败或未展开全部候选，只能返回对应的 `UNPROVEN_*`。此时可以将建议保守降为`(0,0)`，但不得添加仅由未证明推断出的 `DEADLOCK_RISK`。

---

## 6. 快照加载与既有OC时间线重建

### 6.1 修改文件

```text
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcPlanningSnapshotLoader.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcPlanningSnapshot.java
src/main/java/pn/torn/goldeneye/repository/mapper/faction/oc/TornFactionOcMapper.java
src/main/resources/mapper/faction/oc/TornFactionOcMapper.xml
src/main/java/pn/torn/goldeneye/repository/dao/faction/oc/TornFactionOcDAO.java
```

新增只读查询DTO（名称可按现有repository模型风格落在 `repository/model/faction/oc/`）：

```text
OcPlanningRewardStatsDO
```

该DTO仅承载按 `(rank, ocName)` 聚合的完成次数、奖励完整记录次数、观察尝试收益、可靠收益下界；所有字段需Javadoc。不要将其写成实体表DO。

### 6.2 一次快照批量读取内容

`OcPlanningSnapshotLoader.load(factionId, snapshotTime)` 必须在搜索前批量装配：

- 当前帮派 Recruiting / Planning OC；
- 这些OC的全部slot（含 `userId`、`position`、`joinTime`、`progress`）；
- 当前仍属该帮派的成员能力；
- OC档案、岗位模板、链配置、帮派范围、工时系数；
- 当前启用档案和完整链涉及OC的收益统计；
- 当前配置校验结果。

收益统计SQL必须按OC实例聚合，不能从 `torn_faction_oc_income` 的成员行直接求和。完整单OC奖励口径：

```text
reward_money + Σ(split(reward_items_value, '#'))
```

空奖励物品值按0处理；格式非法、负数或奖励缺失的记录不作为“奖励数据完整”的样本。解析逻辑集中在 `OcRewardEvidenceCalculator`，不得让SQL、Renderer和多个Service各自拆分 `#` 字符串。

### 6.3 既有OC重建器

新增：

```text
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcExistingTimelineReconstructor.java
```

职责：将活动OC和slot事实转成 `OcTimelineObligation`、成员真实占用区间和已证明事件。

处理规则：

| 现实OC | 重建规则 |
|---|---|
| 满员且 `readyTime != null` | 所有已加入成员占用至该`readyTime`，生成确定完成—释放事件。 |
| 已有人且未满员、`readyTime != null` | 固定已有成员/岗位；仅为缺口生成后续联合匹配和阶段递推；完整补齐后才将所有参与成员释放在最终完成时间。 |
| 已有人且 `readyTime == null` | 不得用`createTime + 7天`或进度猜测释放；作为不可证明占用，并产生原因码。若它属于已启动链或阻塞最后路径，则自动建议停止。 |
| 计划内无人OC | 生成待启动义务，包含`createTime + 7天`首人期限；不生成当前成员占用。 |
| 非计划无人OC | 不进入容量、义务与对外输出。 |
| 非计划有人OC | 不自动补位、不改变岗位；保留已观察到的真实占用。若其完成时间无法证明，不能把成员提前释放。 |

`progress` 仅用于记录当前阶段事实、诊断和一致性校验；`readyTime` 存在时始终以Torn同步的 `readyTime` 为阶段时间权威，不允许根据`progress`覆盖它。`readyTime` 缺失时，不能因为`progress`看起来接近100%就伪造完成时间。

### 6.4 链实例重建

修改：

```text
OcChainPlanningService.java
OcRefreshSafetyRequestFactory.java
OcTeamDemand.java
OcRefreshSafetyRequest.java
```

要求：

1. 使用现实 `TornFactionOcDO.id` 与 `previousOcId` 识别正在运行的根或后继；
2. 一个真实链根实例只创建一条 `OcCommittedChainObligation`，以 `rootOcId` 去重；
3. 已运行根不能再次从根节点模拟；从当前节点以后构造剩余义务；
4. 后继首人7天期限从前置**实际完成/生成**时间起算；允许首人恰好在边界加入；
5. 多链共享计划根、节点无法按`previousOcId`与配置唯一映射、链断裂、循环、节点非READY或缺岗位模板时，配置维度为无效，两个刷新池均为0；
6. 已启动链后继不可履约时，标记硬义务风险并停止所有新增刷新建议；不能把它降级为普通独立OC。

---

## 7. 有限事件时间线引擎

### 7.1 新增与替换文件

新增：

```text
OcTimelinePlanningEngine.java
OcTimelineState.java
OcTimelineStatePruner.java
OcTimelineEventScheduler.java
OcLiquidityPathVerifier.java
OcPausePolicyEvaluator.java
OcReplanWindowCalculator.java
OcEconomicValueComparator.java
OcRewardEvidenceCalculator.java
```

修改：

```text
OcRefreshSafetySolver.java
OcRosterMatcher.java
OcNoPauseRosterMatcher.java
OcFlowRosterMatcher.java
OcRefreshSafetyRequestFactory.java
OcRefreshInstructionPlanner.java
```

`OcRefreshSafetySolver` 可以保留类名以减少门面改动，但内部不得继续使用 `reservedMemberIds` 或将 `matchWithoutPause()` 作为全模式唯一入口。完成迁移后删除未使用的批次永久预留路径；不要同时保留两套会产生不同建议的求解器。

### 7.2 证明窗口

每次规划先计算有限窗口：

```text
proofWindowStart = snapshotTime
proofWindowEnd = latestReplanAt
```

`latestReplanAt` 是以下最早业务边界减30分钟：

- 已投入义务硬期限；
- 已启动链后继首人期限；
- 当前或候选OC的允许停转恢复上限；
- 计划内无人OC首人期限；
- 当前流动性路径必须重新确认的边界。

若任一边界减提前量不晚于快照时间：

```text
nextReplanAt = snapshotTime
latestReplanAt = snapshotTime
reasonCodes += REPLAN_REQUIRED_NOW
```

随机结果已发生或指挥官刚执行刷新时，旧建议立即失效，`nextReplanAt = snapshotTime`，原因码为 `RANDOM_OUTCOME_CHANGED`。

### 7.3 事件与状态推进

事件按时间稳定排序，tie-break使用事件类型、OC实例ID、OC规划键。每个 `OcTimelineState` 至少持有：

```text
成员占用区间
既有固定成员和岗位
已投入链剩余义务
计划内待启动无人OC
当前候选随机结果
已证明完成—释放事件
流动性锚点链
已发生/计划停转信息
累计价值证据
proofWindowEnd
```

对事件节点的处理顺序：

1. 释放已完成OC的成员；
2. 处理到达的阶段边界、暂停或可恢复事件；
3. 先确保已投入OC和已启动链义务；
4. 再处理计划内无人OC；
5. 最后处理本轮普通/高阶随机结果；
6. 每一次完整匹配、停转或释放后重新验证锚点连续性。

### 7.4 跨事件岗位选择

单事件二分图匹配只生成候选，不得冻结唯一局部匹配。对于同一时刻存在多个完整匹配的情况：

- 先以岗位稀缺性、成员释放时间和可替代人数生成有限候选；
- 将不同候选分别推进为时间线状态；
- 在 `latestReplanAt` 前比较后续硬义务、链节点、待启动窗口、稀缺岗位和锚点连续性；
- 仅当某状态在所有硬约束相同或更优，并且价值指标不差时，才可支配并剪枝另一状态。

不得因08:00局部匹配成功而消耗16:00唯一稀缺岗位成员。

为控制复杂度：

- 保留固定、可配置但仅技术性的搜索时间和状态预算；
- 先移除单节点排列的可避免阶乘计算，再调整预算；
- 预算截断只产生 `UNPROVEN_SEARCH_BUDGET` 或 `UNPROVEN_TIMEOUT`；
- 搜索日志仅记录匿名状态数、耗时、向量和原因码，不记录成员姓名/岗位。

### 7.5 流动性锚点与卡死

`OcLiquidityPathVerifier` 验证的是连续能力，而不是永久保护一个OC或成员：

```text
当前完成—释放事件
→ 释放后可满足已承诺义务
→ 在旧路径失效前形成下一次完整完成—释放事件
```

允许锚点替换：A完成后释放成员进入B，B在路径断裂前完成，则B成为新锚点。

“释放成员能承担一个岗位”不构成锚点；必须证明在窗口内存在下一完整释放事件，或证明到下次强制重评估前可维持全部已承诺义务且仍有可替换的完成路径。

只有确定性矛盾或完整无截断检查证明无路径时，添加 `DEADLOCK_RISK`。无路径的用户文案必须限定为“本次规划窗口内”。

### 7.6 停转政策

`OcPausePolicyEvaluator` 对每条候选时间线计算新增停转，而不是把既有停转误算为本次制造：

| 模式 | 允许条件 |
|---|---|
| 保守 | 不允许主动新增停转；已有停转可恢复但不作为扩大容量依据。 |
| 均衡 | 单次新增不超过6小时；有确定完整岗位恢复路径；不涉及已启动链；不破坏硬义务、锚点、无人OC可避免过期；且产生更高业务价值或更早完整释放。 |
| 收益 | 条件同均衡，但单次不超过12小时；必须按第8节价值比较严格优于无停转候选。 |

若恢复路径、价值证据或完整链证据不足，候选标记 `ECONOMIC_EVIDENCE_INSUFFICIENT`，不得借此增加刷新次数。

---

## 8. 安全前沿、模式选择与价值证据

### 8.1 联合随机前沿

普通池、高阶池必须在同一时间线和同一状态搜索中验证：

```text
(normalCount, highCount)
```

对每个向量枚举当前配置范围中普通OC的多重随机结果、高阶完整链的多重随机结果及其笛卡尔积。向量仅在每一个允许随机组合均能满足硬义务、窗口、锚点和当前模式规则时，才记为已证明安全。

计划内无人OC的存在不是当前静态成员锁；它是对每个随机组合都必须共同承受的未来义务。若新增向量使其中任一OC在首人期限前不存在可行启动/重评估路径，或造成集中开工风险，向量不安全。

### 8.2 模式选择

删除 `OcRefreshModeSelector` 中的容量比例逻辑。可保留该类名，但其职责改为从同一批**已证明安全且已评分**的候选向量中选择：

| 模式 | 选择顺序 |
|---|---|
| 保守 | 零新增停转 → 更大的已证明联合向量 → 更强流动性余量 → 普通池优先 → 稳定tie-break。 |
| 均衡 | 满足6小时政策 → 更大的已证明联合向量 → 更少待启动期限压力/更连续释放 → 普通高阶平衡 → 稳定tie-break。 |
| 收益 | 满足12小时政策 → 规划窗口全局总价值 → 增量单位成员人天 → 更早释放稀缺岗位 → 刷新总数 → 稳定tie-break。 |

已证明至少存在一个安全刷新向量时，不得因旧百分比取整返回0。

### 8.3 价值比较

`OcEconomicValueComparator` 只能在硬安全与完整时间线可行之后使用。固定顺序：

```text
禁止被迫拆队
→ 已启动链和已投入义务
→ 完整时间线可行性
→ 规划窗口全局总价值
   （新高阶根按根+全部后继的完整链价值聚合后参与）
→ 增量单位成员人天
→ 更早释放稀缺岗位
→ 稳定tie-break
```

不能让尚未启动的新高阶根仅因“高阶”机械压过总价值/人天更优的普通OC组合。

价值证据降级严格使用业务冻结顺序：

1. 足量且奖励完整：观察每次尝试收益 + 增量剩余成员人天；
2. 样本不足但存在正可靠下界：`rewardFloor` + 人天；
3. 金额证据不足：最高等级、完整链总需人数、链节点数、增量成员人天、预计释放时间；
4. 仍不可稳定区分：经济证据不足，不能据此提高刷新次数或主动新增停转。

`OcFlowRosterMatcher` 中旧的“收益模式仅按差异工时系数降低边成本”不得作为全局收益选择依据；工时系数只可作为相同时间线、相同全局价值的成员—岗位/加入顺序决胜条件。

---

## 9. 配置校验与数据库变更

### 9.1 不新增本期Schema

阶段一政策为全局冻结常量，且现有配置已能表达OC范围、READY状态、链、岗位、最低收益和最小样本数。因此本期：

```text
不新增表、字段、索引、约束或Liquibase changeSet。
```

现有规划changeSet已执行，严禁修改。

### 9.2 运行时配置校验

修改：

```text
OcFactionPlanningPolicyResolver.java
OcPlanCatalogValidator.java
TornSettingOcPlanningManager.java（仅当需批量读取统计/配置时）
```

规则：

- `READY + faction enabled + 有效目录 + 完整链` 才进入自动规划；
- 显式非法旧容量比例字段不能作为新模式选择依据；旧字段存在但不参与新模型；
- 帮派无显式范围、启用根无链、链共享根、链断裂、环、节点非READY、岗位模板空、差异工时帮派缺系数，都必须返回配置无效并选择`(0,0)`；
- 配置错误与求解未证明分开输出；
- 配置变化只需刷新 `TornSettingOcPlanningManager` 缓存，不需要改规划算法代码。

如真实只读核验发现当前NOV/PN配置、岗位模板、系数或链关系与业务文档不一致，停止实施并提交独立的追加式配置迁移方案；不得在Java中填补遗漏。

---

## 10. 对外输出、Shadow与可观测性

### 10.1 修改文件

```text
OcRefreshInstructionPlanner.java
OcRefreshInstructionPlan.java
OcNewTeamPlanRenderer.java
OcCurrentOccupancyCalculator.java
```

输出增加但始终匿名：

- 当前OC、已有人/无人、已有人未满员数量与实际占用摘要；
- `configurationStatus`、`proofStatus`；
- 是否存在确定性被迫拆队风险；
- 是否存在连续流动性锚点和下一关键释放时间；
- 普通池/高阶池刷新指令（零数量不展示）；
- 当前模式是否允许、是否实际选择可恢复停转及其匿名时长；
- 已证明下界与是否仅为下界；
- `nextReplanAt`、`latestReplanAt`；
- 主要匿名原因码对应的中文说明；
- 价值证据不足时的降级说明；
- 刷新或随机结果变化后立即重跑同一命令的提示。

禁止输出成员名、ID、岗位、个人加入/释放时间、固定锚点详情、链内部运行明细。

### 10.2 Shadow观察

本期不建表、不建后台调度。Shadow证据来自指挥官实际运行命令时规划引擎产生的匿名结构化日志：

```text
factionId、mode、snapshotTime、configurationStatus、proofStatus、riskFlags、
lowerBound、selectedVector、next/latestReplanAt、anchorCount、
pauseCount/pauseDuration、pendingEmptyCount、reasonCodes、elapsedMillis
```

日志不得包含成员、岗位、内部排程或奖励明细。连续Shadow观察由AI技术专家在多个真实命令快照收集与Review；命令不改变人工刷新/入队边界。

---

## 11. 具体文件清单

### 11.1 新增生产文件

```text
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcTimelinePolicy.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcExistingTimelineReconstructor.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcTimelinePlanningEngine.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcTimelineStatePruner.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcLiquidityPathVerifier.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcPausePolicyEvaluator.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcReplanWindowCalculator.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcRewardEvidenceCalculator.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcEconomicValueComparator.java

src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcConfigurationStatus.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcProofStatus.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcRiskFlag.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcPlanReasonCode.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcMemberInterval.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcTimelineEvent.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcTimelineObligation.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcCommittedChainObligation.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcLiquidityAnchor.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcTimelineSafetyAssessment.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcReplanWindow.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcPauseAssessment.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcValueEvidence.java

src/main/java/pn/torn/goldeneye/repository/model/faction/oc/OcPlanningRewardStatsDO.java
```

### 11.2 修改生产文件

```text
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcPlanningSnapshotLoader.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcRefreshSafetyRequestFactory.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcRefreshSafetySolver.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcRefreshModeSelector.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcRefreshInstructionPlanner.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcNewTeamPlanRenderer.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcCurrentOccupancyCalculator.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcChainPlanningService.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcRosterMatcher.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcNoPauseRosterMatcher.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcFlowRosterMatcher.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcFactionPlanningPolicyResolver.java
src/main/java/pn/torn/goldeneye/torn/service/faction/oc/planning/OcPlanCatalogValidator.java

src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcPlanningSnapshot.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcRefreshSafetyRequest.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcRefreshSafetyResult.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcRefreshPlanningContext.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcRefreshInstructionPlan.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcTeamDemand.java
src/main/java/pn/torn/goldeneye/torn/model/faction/crime/planning/OcFactionPlanningPolicy.java

src/main/java/pn/torn/goldeneye/repository/mapper/faction/oc/TornFactionOcMapper.java
src/main/resources/mapper/faction/oc/TornFactionOcMapper.xml
src/main/java/pn/torn/goldeneye/repository/dao/faction/oc/TornFactionOcDAO.java
```

### 11.3 测试文件

保留并按新语义调整：

```text
OcPreparationTimeCalculatorTest.java
OcRosterPreparationTimelineTest.java
OcRefreshSafetySolverTest.java
OcRefreshSafetyRequestFactoryTest.java
OcRefreshModeSelectorTest.java
OcRefreshInstructionPlannerTest.java
OcNewTeamPlanRendererTest.java
OcChainPlanningServiceTest.java
OcCurrentOccupancyCalculatorTest.java
OcFactionPlanningPolicyResolverTest.java
```

新增：

```text
OcExistingTimelineReconstructorTest.java
OcTimelinePlanningEngineTest.java
OcLiquidityPathVerifierTest.java
OcPausePolicyEvaluatorTest.java
OcReplanWindowCalculatorTest.java
OcEconomicValueComparatorTest.java
OcTimelineStatePrunerTest.java
OcPlanningRewardStatsMapperTest.java
```

所有测试类需职责Javadoc，类与每个`@Test`必须使用中文`@DisplayName`。

---

## 12. 实施顺序

开发必须保持每一阶段可编译，避免一次性迁移全部record构造器。

1. **模型迁移：** 新增状态枚举、评估对象和计划字段；机械修复编译错误；先编译。
2. **快照重建：** 增加批量奖励统计、既有OC重建器、真实链实例义务；写RED测试后实现；运行重建器测试。
3. **窗口与状态：** 实现30分钟提前量、证明状态分离、随机结果立即重算；运行相关测试。
4. **全局时间线：** 引入区间占用、事件推进、Pareto状态裁剪、有限复用；删除永久 `reservedMemberIds` 语义；运行核心时间线测试。
5. **流动性与停转：** 接入锚点替换、确定性卡死与6/12小时模式政策；运行场景测试。
6. **联合前沿与模式：** 将普通/高阶随机组合接入同一引擎；移除百分比缩放；接入价值比较与证据降级。
7. **输出：** 扩展计划DTO、Renderer和匿名日志；禁止泄露内部成员/岗位信息。
8. **配置与回归：** 复核NOV/PN当前完整范围、配置错误fail-closed、旧命令入口与缓存刷新。
9. **真实回放与Shadow：** 编译、聚焦测试、真实只读回放、多个实际快照Shadow观察，再交由AI技术专家Review。

禁止在未通过前一阶段的编译和核心测试前，继续扩大下一阶段。

---

## 13. 测试与验收标准

### 13.1 必须的纯领域测试

| 场景 | 关键断言 |
|---|---|
| 全帮卡死 | 确定性岗位/人数矛盾时 `PROVEN_INFEASIBLE + DEADLOCK_RISK`，建议`(0,0)`。 |
| 高负载可恢复 | 当前空闲0但08:00存在完整释放，不得标记卡死；释放成员后重新参与。 |
| 有限非重叠复用 | A在08:00完成，成员08:00后加入B且B未过期，必须允许。 |
| 无限串行吞吐 | 无完成/启动/期限/重评估边界的循环假设，不能提高刷新次数。 |
| 锚点替换 | A释放后进入B，B在旧路径失效前完成，B成为新锚点，必须允许。 |
| 仅单岗位可用 | 释放成员只满足一个岗位、无法形成完整后续释放时，不得认定锚点成立。 |
| 跨事件局部最优 | 08:00 A使用可替代成员，保留16:00 B唯一稀缺成员；全局方案必须成功。 |
| 14:00—16:00恢复 | 保守拒绝新增停转；均衡/收益在各自6/12小时、价值与恢复条件满足时允许。 |
| 18:00边界 | 16:00释放成员可处理18:00义务；不得在08:00永久预留。 |
| 岗位不兼容 | 释放人数不能替代岗位匹配，恢复不可证明。 |
| 计划内无人OC | 进入未来义务但不锁定当前成员；新增刷新不能使其不可避免过期。 |
| 已启动链 | 根不重复模拟；后继从真实完成/生成时间起算；后继失败阻断所有新增刷新。 |
| 状态区分 | 超时、节点预算、固定顺序失败均为`UNPROVEN_*`，不得伪造卡死。 |
| 模式 | 不使用25/50/100比例；有安全刷新时保守不因比例变0。 |
| 价值 | 新高阶根按完整链参与全局价值，不能机械压过普通组合。 |
| 匿名输出 | 不出现成员、ID、岗位、个人时间、内部链排程。 |

### 13.2 数据库与Mapper测试

`OcPlanningRewardStatsMapperTest` 使用真实Mapper验证：

- `reward_money + reward_items_value` 的单OC聚合口径；
- 奖励物品为空、非法、缺失时不会当作完整样本；
- 仅目标档案范围被读取；
- 不从成员收益表重复累加；
- 查询按当前启用范围批量执行，不出现循环单OC查询。

如测试写入临时数据，使用测试专属命名空间与`@AfterEach`精确物理DELETE；不得依赖异步/独立事务的`@Rollback`。

### 13.3 当前真实只读回放

回放必须调用生产纯规划引擎，而不是复制近似算法；只读加载NOV、PN当前快照并分别运行三个模式。至少记录：

- 启用根、完整链和配置校验结果；
- 当前现实占用、下一关键释放、锚点、卡死风险；
- 普通/高阶选择、下界和证明状态；
- 可恢复停转、待启动压力、可避免过期；
- `nextReplanAt / latestReplanAt`；
- 同一快照重复运行的确定性；
- 同一成员无重叠占用。

回放工具只能位于临时验证目录或测试代码；不得启动完整Spring容器、Torn API、Redis、定时任务或写业务表。它证明当前快照，不是长期安全证明。

### 13.4 Shadow与发布Review

发布前需要：

1. 多个指挥官实际命令快照的匿名Shadow摘要；
2. 无被迫拆队、硬链义务失败或不可解释可避免过期的证据；
3. 均衡/收益模式未突破6/12小时和已启动链禁止停转政策；
4. AI技术专家对代码、配置、测试、当前回放与Shadow日志进行Review；
5. 无P0/P1后，才更新本文“实施状态、实现基线、验收记录”，并删除一次性补充业务文档。

---

## 14. 编译、测试与Review门禁

### 14.1 工程验证命令

```bash
JAVA_HOME="C:\Program Files\Java\jdk-21" mvn.cmd compile -q -DskipTests

JAVA_HOME="C:\Program Files\Java\jdk-21" mvn.cmd test -Dtest="OcExistingTimelineReconstructorTest,OcTimelinePlanningEngineTest,OcLiquidityPathVerifierTest,OcPausePolicyEvaluatorTest,OcReplanWindowCalculatorTest,OcEconomicValueComparatorTest,OcTimelineStatePrunerTest,OcRefreshSafetySolverTest,OcRefreshInstructionPlannerTest,OcNewTeamPlanRendererTest,OcPlanningRewardStatsMapperTest"
```

之后运行完整 `planning` 测试包；L3改动完成后运行全量Maven测试。若全量测试受外部环境阻断，必须报告实际已执行的测试、失败根因和未执行范围，不能用编译替代测试。

### 14.2 P0/P1 Review清单

**P0：**

- [ ] 任何候选都不移动已有成员或岗位；
- [ ] 已启动高阶链后继按真实实例、真实开始时间、完整链义务验证；
- [ ] 同一成员不存在重叠占用；
- [ ] 卡死风险不由超时或启发式失败伪造；
- [ ] 无人OC过期不会以破坏硬义务/流动性为代价避免；
- [ ] 旧25/50/100容量比例不再影响三模式选择。

**P1：**

- [ ] A→B的有限非重叠复用可行，无边界串行吞吐不可行；
- [ ] 锚点可替换，且替换后仍有完整连续释放路径；
- [ ] 单事件匹配不会冻结破坏后续稀缺岗位的局部选择；
- [ ] 保守/均衡/收益分别满足0/6/12小时停转政策；
- [ ] 当前所有NOV、PN启用根和完整链由配置动态覆盖；
- [ ] `nextReplanAt`、`latestReplanAt`和30分钟提前量正确；
- [ ] 输出匿名、原因稳定、状态维度不混用；
- [ ] 收益证据不足不会提高刷新或停转建议；
- [ ] 纯引擎无数据库/HTTP/Redis访问，快照加载无N+1。

**P2：**

- [ ] Javadoc、`@DisplayName`、Liquibase/Schema约束保持规范；
- [ ] 不新增依赖、无抑制注解、无无关重构；
- [ ] 日志不包含成员、岗位和个人时间；
- [ ] `git diff` 仅覆盖本文列出的文件及必要测试。

---

## 15. 完成与停止条件

仅当以下条件全部满足，AI技术专家才可判定本需求技术验收通过：

1. 代码与本文时间线、状态、锚点、有限复用、停转和价值契约一致；
2. 所有L3核心领域测试、Mapper测试和相关回归通过；
3. 当前NOV、PN真实只读回放证明配置与当前快照链路可运行；
4. 连续Shadow观察和发布前业务Review完成；
5. 无未解决P0/P1；
6. 实现没有自动刷新、自动入队、自动拆队或对外泄露内部成员排程；
7. AI技术专家更新本文实施状态、实现基线和验收记录。

在以上条件满足前，开发人员不得自行宣布功能完成或删除一次性业务补充文档。
