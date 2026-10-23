# OC 推荐收益混算与机会成本排序 技术实施方案

## 1. 文档信息

- **文档类型：** 长期技术实施方案、开发与 Review 验收基线
- **适用项目：** Golden-Eye
- **适用版本：** 1.9.x（分支 `feat-oc`）
- **关联业务文档：** `.ai/knowledge/oc/oc_recommend_mixing_baseline.md`（长期业务基线；结论编号 C-A..C-L，撤销清单 R1–R26）
- **适用功能：** `OC推荐`（`OcRecommendStrategyImpl`）、`OC分配`（`TornOcAssignService`）
- **设计状态：** 待实施（2026-10-23 代码精读后修订，见修订记录）
- **风险等级：** L3（收益与金额口径、成员资源占用、共享 DO 被就地改写、配置缺省回退）
- **维护职责：** AI 技术专家维护本文；开发人员只按本文改代码、数据与测试，不自行调整口径

### 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-10-22 | 初版 |
| v1.1 | 2026-10-23 | 代码精读后修订：修正 MeasuredOcYieldEstimator 聚合键（oc_id → oc_name+rank+faction_id）及链式人天还原方式；修正被锁死人天范围（progress>0 成员）；补充 findRecrutList 归一化逻辑改造；补充全帮派稀缺度数据加载；从推荐/分配主链路删除 isReassign 传递；修订 OcRecommendBadgeResolver 门槛方案（选方案B）；补充 OcYieldEstimatorRegistry @Order 约束 |

> 本文回答「改哪些文件、改成什么样、怎么验证」。业务口径一律以业务基线为准，本文不重新定义口径；出现冲突时以业务基线为准，并同步修订本文。

---

## 2. 目标、边界与不做事项

### 2.1 本轮可验收目标

| 编号 | 目标 | 验收方式 |
|---|---|---|
| T1 | 大锅饭名单外的 OC 能进入候选池并被推荐 | 构造一个不在 `torn_setting_oc_reassign_oc` 里、但成员成功率达标且有空位的 OC，`OC推荐` 结果包含它 |
| T2 | 完全没有成功率记录的成员不再直接拿到空结果 | 矩阵为空时仍返回按映射值排序的候选（而非 `List.of()`） |
| T3 | 排序键统一为「机会成本」，两种分钱模式共用同一套评分 | 同一份快照下 `EQUAL` 与 `COEFFICIENT` 帮派的候选相对顺序仅因系数进入度量，不因评分公式分叉 |
| T4 | 把「`ready_time` 为空」识别为 **新队** 这一正常状态（无人加入时 Torn 不返回预计开动时间），而不是当异常兜底 | 对 `readyTime == null` 的招募中 OC 执行推荐：正常返回，且该 OC 的机会成本里「被锁死人天」为 0，不会因新队身份被误判为停转 |
| T5 | 同分结果可复现 | 同一快照连续两次推荐得到完全相同的顺序 |
| T6 | 纯道具奖励的 OC 不被判为 0 产出 | `Ship Happens` / `Window of Opportunity` 的度量值 > 0 |

### 2.2 改动范围：代码层最小化，不收缩需求

最小改动约束的是**每个改动点的代码形态**，不是可以少算几条口径。能改一行不改一段，能加一个分支不新建一套体系，能不新建类就不新建；口径需求一条不减。下表逐条给出最小代码改法及其改动量。

| 缺陷 | 最小代码改法 | 改动量 |
|---|---|---|
| D1 `readyTime` 为空 | 这是**新队**的常态：无人加入时 Torn 不返回预计开动时间，不是异常。新增 `OcPhaseEnum` 三态（新队 / 未开动 / 停转）+ 一个解析方法，替换三处 `getReadyTime().minusDays(1)` 直调；新队不做「减一天」补偿 | 新增 2 个类型 + 3 处调用点各 1 行 |
| D2 注释 99 与代码 95 不一致 | 只改注释文案，不动逻辑 | 1 行 |
| D3 无 OC 级去重 | **不改**。同一 OC 的多个空位各自独立进入推荐，保留「一个 OC 可占满 Top-N」的旧行为——多人同时抢同一岗位时，抢位失败后同队的第二个岗位仍值得加入，不该让用户再请求一次指令（业务基线 C-L1 / 5.11） | 0 行 |
| D4 无确定性 tie-break | 现有比较器尾部追加两个 `thenComparing` | 2 行 |
| D5 `checkIsReassignRecommended` 忽略帮派 override 与 rank | 不改判断逻辑，只改 Javadoc 并收窄调用方（不再作为准入闸门） | 0 行代码 |
| D6 系数缺行静默返回 0 | `return ZERO` 改成一条 warn 日志 + `return ONE` | 2 行 |
| D8 两套并行推荐引擎 | 本轮只改 `OC推荐` / `OC分配` 这一套 | 不动 |
| D9 重复的 `OcPreparationTimeCalculator` | 与本轮无关 | 不动 |
| D10 推荐理由靠中文串解析 | **保持理由文案逐字不变**，只用枚举收敛阈值来源，不碰 `OcRecommendBadgeResolver` 与既有断言 | 同类内重构 |
| D11 名单外 OC 无 `income` 历史 | 由度量层的映射策略承接，不加数据、不加表 | 新增 1 个策略实现 |
| D12 `expected_reward` 有 2 个 0 | 纯数据修数；映射值改由该字段驱动，见 §9.1 | 0 行代码 |
| D13 ending 不可观测 | 与收益度量无关 | 不动 |
| D16 名单行与开关行不一致 | 已由数据修复（本地库补 9356 开关行）。代码侧保留一处只读自检：名单与开关不一致时打 WARN，避免同类缺口再次被当成计算结论 | 约 10 行，见 §9.3 |

### 2.3 明确不做

- 不新增任何表、字段或索引；不改现有表结构
- 不引入新依赖（不使用新的数学或缓存库）
- 不重写 `OcRecommendStrategyImpl` 指令层与其消息渲染
- 不改 `OcRecommendBadgeResolver` 与理由文案格式
- 不做「等下一次刷新」的期权定价（业务基线 5.9 明确不做）
- 不新建第二套推荐引擎，不合并现有两套
- 不改写 `torn_setting_oc_reassign_oc` / `_faction` 的任何数据（配置是否开启由运营决定，代码只负责把不一致暴露出来）

---

## 3. 现状基线（已核验）

### 3.1 生产调用链

```text
OcRecommendStrategyImpl.handle                 // 指令入口，含 refreshOc(1)
  → TornOcRecommendService.recommendOcForUser(user, topN, joinedOc)   // 单人推荐
  → TornOcRecommendService.queryCurrentOcStatus(...)                 // 当前岗位状态
  → TornOcRecommendManager.calcRecommendScore / buildRecommendReason  // 评分与理由

TornOcAssignService.allocateGlobally(...)      // 多人批量分配（另一入口）
  → findCandidatesForUser → calcRecommendScore
```

### 3.2 现有评分：两套互不可比的量纲

`TornOcRecommendManager` L143-190 存在两个分支，**时间权重在两支里是反的**：

| 分支 | 公式 | 时间权重 |
|---|---|---|
| 非大锅饭（L155-167） | `(成功率分 × 权重档 × 0.1 + rank × 10) × 0.8 + 时间分 × 0.2` | 20% |
| 大锅饭（L172-190） | `(系数 × 4 + 成功率分 × 权重档 × 0.1 + rank) × 0.2 + 时间分 × 0.8` | **80%** |

两支把「等级 ×10」「系数 ×4」「权重档 ×0.1」硬拼在同一个加式里，系数与 rank 的换算比例是人为约定，**没有任何共同的物理量纲**。结果：

- 名单内与名单外的分数不可比，只能靠 `isReassign` 二选一分支；
- 权重无法从业务目标反推，改一个常数就改变全部相对顺序；
- 大锅饭帮派里时间压倒一切（80%），等级只占 `rank × 0.2`（rank 10 贡献 2 分，而时间分 0-100）。

### 3.3 五个必修阻断点

| # | 位置 | 现象 | 违反 |
|---|---|---|---|
| B1 | `TornOcRecommendService` L79-81、L160-168 | `shouldSkipOc` 用 `getRotationOcNames` 过滤候选 OC，名单外 OC 永不进入候选池 | R2 |
| B2 | `TornOcAssignService` L188-191 | 同上，批量分配时名单外 OC 被 `continue` | R2 |
| B3 | `TornOcRecommendService` L69-72 | 成员根本没有成功率记录时直接 `return List.of()`；L181-183 再对单个岗位做一次 `matchedData == null` 跳过 | R10 |
| B4 | `TornOcRecommendService` L153 / L259 / L262 | `getReadyTime().minusDays(1)` 未判空，招募中 OC 的 `ready_time` 可为空 | T4 |
| B5 | `TornOcAssignService` L166 / L219-236 | 就地把共享 `TornFactionOcDO.readyTime` 改写后 `plusDays(1)` 再回滚，异常路径会污染同一请求内的其它读取 | 稳定性 |

---

## 4. 目标架构

### 4.1 四层切分

```text
① 候选生成   现有查询（招募中 OC + 空位 + 成员成功率）保持不变
② 度量       OcYieldEstimator 把「一个 OC / 一条链」折算成 期望净产出 与 期望人天
③ 排序       OcOpportunityCostRanker 用机会成本给候选排序，并按确定性决胜
④ 输出       OcRecommendationVO 扩展字段，指令层渲染不变
```

②③ 是纯函数，不碰数据库、不读系统时间以外的外部状态（时间由调用方传入 `now`），可脱离 Spring 单测。

### 4.2 包规划

`service/faction/oc/recommend` 现有 2 个文件，直接塞入度量与排序会让职责混在一起；按既有工程习惯（`service/faction/oc` 下已按 `chain` / `matching` / `search` / `timeline` 等职责分子包）新增两个子包：

```text
torn/service/faction/oc/recommend/
├── TornOcRecommendService.java            （修改，留在原包）
├── TornOcAssignService.java               （修改，留在原包）
├── metric/                                 （新增子包：把 OC 折算成收益与人天）
│   ├── OcYieldEstimate.java
│   ├── OcYieldEstimator.java
│   ├── MeasuredOcYieldEstimator.java
│   ├── MappedOcYieldEstimator.java
│   ├── OcYieldEstimatorRegistry.java
│   └── OcChainValueReducer.java
└── rank/                                   （新增子包：机会成本排序）
    ├── OcOpportunityCost.java
    ├── OcScarcityEstimator.java
    ├── OcOpportunityCostRanker.java
    ├── OcRecommendTimeSupport.java
    └── OcRecommendTunings.java

torn/model/faction/crime/recommend/         （已存在，新增模型后 8 个文件）
├── OcRecommendCandidate.java               （新增 record）
├── OcYieldSourceEnum.java                  （新增 enum）
├── OcRecommendOutcomeEnum.java             （新增 enum）
├── OcPhaseEnum.java                        （新增 enum）
└── ...（既有 OcRecommendationVO / OcSlotDictBO / OcRecommendTableBO）
```

拆包规则：单个包超过 10 个文件时按职责再拆（当前两个新子包分别为 6 和 5，不再拆）。

### 4.3 设计模式与泛型落点

| 手法 | 落点 | 解决的具体问题 |
|---|---|---|
| 策略模式 | `OcYieldEstimator` + `Measured` / `Mapped` 两实现 | 名单内有实测、名单外只有映射；用同一接口让上层不再区分「在不在名单」 |
| 注册表 + Spring 注入 | `OcYieldEstimatorRegistry` 注入 `List<OcYieldEstimator>` | 以后新增估算来源只加实现类，不改调用方 |
| 泛型归约 | `OcChainValueReducer<T>` | 链聚合对「实测链」与「配置链」两种输入结构是同一算法，用 `Function<T, …>` 抽取差异，避免写两遍 |
| 组合比较器 | `OcOpportunityCostRanker` | 排序键 + 决胜键按顺序组合，决胜规则集中一处，不再散落在两处 `.reversed()` |
| 不可变 record | `OcYieldEstimate` / `OcOpportunityCost` / `OcRecommendCandidate` | 排序过程中不再改写 DO（消除 B5 一类副作用） |

---

## 5. 领域模型（新增）

全部放在 `torn/model/faction/crime/recommend/`，不可变 record / enum，组件写 `@param`。

| 文件 | 形态 | 内容 |
|---|---|---|
| `OcYieldEstimate` | record | `BigDecimal expectedNetReward`（期望净产出）、`BigDecimal expectedMemberDays`（期望占用总人天）、`OcYieldSourceEnum source`；方法 `netPerMemberDay()` = 前者除以后者，人天为 0 时返回 `BigDecimal.ZERO` |
| `OcYieldSourceEnum` | enum | `MEASURED`（实测 UPH）、`MAPPED`（等级映射）、`CHAIN_MAPPED`（链映射，含前置人天） |
| `OcRecommendOutcomeEnum` | enum | `IRREPLACEABLE`（替代概率≈0）、`SCARCE`、`SUBSTITUTABLE`、`UNKNOWN`，用于对外解释，**不参与排序** |
| `OcPhaseEnum` | enum | `NEW_TEAM`（无人加入，无 `readyTime`）、`NOT_STARTED`（`readyTime > now`）、`PAUSED`（`readyTime <= now`） |
| `OcRecommendCandidate` | record | `TornFactionOcDO oc`、`TornFactionOcSlotDO slot`、`TornSettingOcSlotDO requirement`、`Integer passRate`、`OcYieldEstimate estimate`、`OcOpportunityCost cost` |

`OcOpportunityCost` 与 `OcScarcityEstimator` 属排序层，放 `recommend/rank` 包。

---

## 6. 度量层实现（`recommend/metric`）

### 6.1 统一公式

```text
期望净产出 = (reward_money + Σ按 '#' 拆分的 reward_items_value) × Π(链上各步成功率) − 预期道具成本
期望人天   = (链上各步 人数 × 单岗位期望占用天数) 之和
量纲       = 期望净产出 / 期望人天        （M / 人天）
```

- 奖励必须用 `reward_money + Σitems`，`reward_items_value` 是 `#` 分隔串，**空串要当 0**（T6）。
- 链上各步成功率取自成员在该岗位的成功率不是本层职责；本层使用 **OC 级成功率**：已有实测取实测，无实测取 `torn_setting_oc` 映射。
- 每步都乘成功率，等价于「奖励只在叶子成功时结算」；分母是全部尝试步数的人天，不做额外转化率折算（实测转化率仅用于校准映射值，见业务基线 4.8）。

### 6.2 策略选择

```java
public interface OcYieldEstimator {
    boolean supports(OcYieldSourceEnum source);

    OcYieldEstimate estimate(TornFactionOcDO oc, LocalDateTime snapshotTime);
}
```

| 实现 | `supports` | 数据来源 |
|---|---|---|
| `MeasuredOcYieldEstimator` | `MEASURED` | `torn_faction_oc_income`：按 `(oc_name, rank, faction_id)` 跨历史实例聚合，不按当前活跃 oc_id 查询（同名 OC 每次招募产生新 oc_id，活跃实例无历史记录）；链式 OC 各步独立聚合后由 OcChainValueReducer 归约（单步聚合无法覆盖其他步骤的人天）；`final_income` 是链级净收益快照，不当个人份额 |
| `MappedOcYieldEstimator` | `MAPPED` / `CHAIN_MAPPED` | `torn_setting_oc.expected_reward` + `torn_setting_oc.required_members` + `torn_setting_oc_chain` |

`OcYieldEstimatorRegistry` 按来源选实现；**判定来源只看有没有可用的实测样本**，不看是否在大锅饭名单。两个实现类加 `@Order`（`MeasuredOcYieldEstimator` 优先），避免注入顺序不确定：

```text
该 OC (oc_name, rank, faction_id) 在 torn_faction_oc_income 中成功样本数 ≥ N（默认 3）  → MEASURED
否则                                                                                  → MAPPED / CHAIN_MAPPED
```

N 作为 `OcRecommendTunings.MIN_MEASURED_SAMPLES` 常量，默认 3，来源于业务基线 4.6 的样本离散结论：1–2 个样本的单位人天产出波动可达一倍。

### 6.3 链聚合（泛型归约）

```java
public final class OcChainValueReducer<T> {
    public OcYieldEstimate reduce(List<T> steps,
                                 Function<T, OcYieldEstimate> valueOf,
                                 Function<T, Integer> stepIndex);
}
```

归约规则（两条，集中在这一个类里，不许在调用方重复）：

1. **奖励只取叶子**：`expectedNetReward` 等于 `stepIndex` 最大的那一步的奖励，前置步骤不累加；
2. **人天累加全部步骤**：`expectedMemberDays` 等于所有步骤人天之和。

这样「实测链」（输入是 `TornFactionOcDO` 列表）与「配置链」（输入是 `torn_setting_oc_chain` 行列表）复用同一归约，差异只在传入的 `Function`。

### 6.4 数据来源表

| 需要的事实 | 来源 | 说明 |
|---|---|---|
| 奖励（现金 + 道具） | `torn_faction_oc.reward_money` / `reward_items_value` | 对名单内外**全部** OC 都有值 |
| 实测链级收益 | `torn_faction_oc_income` | 仅覆盖大锅饭池；`final_income` 是链级快照，不能当个人份额 |
| 映射收益 | `torn_setting_oc.expected_reward` | 口径 B（成功时奖励 × 成功率），本轮先修数据（§9.1） |
| 链结构 | `torn_setting_oc_chain`（`parent_oc_name/rank` → `child_oc_name/rank`，`sequence_no`） | 读取 `enabled = 1 and deleted = 0` |
| 岗位人数 | `torn_setting_oc.required_members` | |
| 单岗位期望占用天数 | `OcRecommendTunings.MEMBER_DAYS_PER_SLOT`，默认 4.5 | 业务基线 4.3 实测占用 P50 = 4.00 天 |

---

## 7. 排序层实现（`recommend/rank`）

### 7.1 机会成本公式

```text
机会成本(我不填这个位) = 期望净产出 × (1 − 替代概率)
                      + 被锁死人天 × 每日等待成本
                      − 我的占用人天 × 每日等待成本 × 占用成本系数
```

| 项 | 含义 | 取值 |
|---|---|---|
| `期望净产出` | 该 OC/链的帮派口径期望收益 | 度量层输出 |
| `替代概率` | 除我之外还能填这个人有多少 | §7.2，`null` 表示未知 |
| `被锁死人天` | 停转队里其他成员已经等待的人天 | §7.3 |
| `每日等待成本` | 一成员天折算多少钱 | `OcRecommendTunings.DEFAULT_DAILY_WAIT_COST` = 5.0（M/人天），取自业务基线 4.9 的中位量级 |
| `占用成本系数` | 我占用这段时间本可产出的机会 | `OcRecommendTunings.OCCUPANCY_COST_FACTOR` = 1.0；置 0 即退化为业务基线 5.9 的原式 |

第三项是 C-D4「等待成本 > 占用成本」的直接落地：低级 OC 的 `期望净产出 × (1 − 替代概率)` 很小，若占用人天带来的机会成本高于它，机会成本为负，自然排在最后，不需要额外写「低级 OC 不推荐」的分支。

**替代概率为 `null`（未知）时**，第二、三项照常计算，第一项按 `期望净产出 × 1.0` 计入——即当作「没有任何人能替代我」。这是中性偏保守的一侧：不会把未知岗位误判为可随意放弃。

### 7.2 替代概率

```text
eligible = count(torn_faction_oc_user 中 该帮派、该 oc_name、该 position、pass_rate ≥ 岗位要求)
demand   = 该 OC 当前剩余空位数（含本 slot）
替代概率 = min(1, max(0, eligible − 我是否在 eligible 里 ? 1 : 0) / demand)
```

三个必须注意的实现细节：

1. **`faction_id` 兜底**：`torn_faction_oc_user.faction_id` 既有 0（全局默认）也有具体帮派。查询按 `(0, factionId)` 取并集，同一个 `(oc_name, rank, position)` 有帮派行时优先帮派行，否则用 0 行。
2. **与配置表的连接键不同名**：`torn_faction_oc_user.position` 是短码（如 `Assassin`），`torn_faction_oc_slot.position` 是全码（如 `Assassin#1`）。前者连 `torn_setting_oc_slot.slot_short_code`，后者连 `slot_code`。
3. **样本缺失返回 `null`**：该 `(oc_name, rank, slot)` 在矩阵里一条记录都没有时返回 `null`，由 §7.1 按中性处理。**不得**返回 0（会被当成「无人可替代」的错误高分），也**不得**为了平滑去套用其它岗位的比例。

替代概率每次推荐实时计算，不存在任何持久化的能力画像。

### 7.3 被锁死人天

```text
被锁死人天 = Σ（该 OC progress > 0 的成员 max(0, now − join_time) 天）
```

数据来自 `torn_faction_oc_slot.join_time` 与 `progress`，不需要估算。口径：

- **只统计 `progress > 0` 的成员**：`progress = 0` 的空转成员可自由换队不损失进度（业务基线 C-E7），不计入被锁死；`progress > 0` 的成员换队需重来，属于真实锁死；
- OC 状态必须是招募中（尚未进入执行）；
- 新队（`readyTime` 为空，即 `OcPhaseEnum.NEW_TEAM`）——无人有进度，该项为 0，这正是「新队不如停转队优先」的来源；
- 停转队（`readyTime` 早于 `now`）若存在有进度成员，该项自然最大。

### 7.4 比较器与决胜

```text
① 机会成本降序
② 期望净产出/人天 降序
③ 期望人天 升序（占用越短越灵活）
④ 剩余空位数 升序（更快凑齐开动，业务基线 C-E4 第三键）
⑤ ocId 升序，slotId 升序（确定性决胜）
```

排序后只做一件事：

- **基线过滤**：已加入 OC 时，只有严格优于当前岗位的候选才输出。基线值同样用机会成本计算，**不再用旧评分**，否则新旧量纲混比。`CurrentOcStatus.currentScore` 的语义随之变为「当前岗位的机会成本」。

**不做 OC 级去重**（业务基线 C-L1 / 5.11）：同一 OC 的每个空位各占一行，允许一个 OC 占满 Top-N。

- 多个成员会同时抢同一个岗位；抢位失败时同队的第二个岗位仍然值得加入，此时它已经在结果里，不必再请求一次指令。
- 同一 OC 内不同岗位的替代概率不同，机会成本因此不同，排序会把最不可替代的岗位排在最前——同队多行是**备选梯度**，不是重复信息。
- 因此 `emptyCountMap`（每个 OC 的剩余空位数）保留，作为第 ④ 决胜键。

---

## 8. 接入改造（逐文件）

### 8.1 `torn/service/faction/oc/recommend/TornOcRecommendService.java`（324 行）

**前置加载**：在方法入口处一次性加载全帮派 `torn_faction_oc_user`（`ocUserDao.queryByFactionId(factionId)`），供 `OcOpportunityCostRanker` 计算替代概率时在内存中过滤，不为每个候选岗位单独查库。

| 位置 | 改动 |
|---|---|
| L53-57 入口 | 新增一次性加载：`List<TornFactionOcUserDO> factionOcData = ocUserDao.queryByFactionId(user.getFactionId())`，向后传入排序器 |
| L69-72 | 删除「成员无成功率记录即 `return List.of()`」早退。`userOcData` 允许为空列表，交由度量层退回映射值 |
| L79-81 + L160-168 | 删除 `shouldSkipOc` 方法与其调用，连同 `isReassign` 的生成（`checkIsReassignRecommended` 调用）与传递（`collectSlotsScore` 参数）一并删除。大锅饭名单不再参与候选过滤；`isReassign` 在推荐主链路中无活跃用途 |
| L173-189 `collectSlotsScore` | 删除 L181-183 中的 `matchedData == null` 跳过；保留 `matchedData != null && passRate < requirement` 的跳过（能力门槛属于业务规则）；`matchedData == null` 时 `passRate` 传 `null` |
| L85-101 排序段 | 改为构造 `List<OcRecommendCandidate>` 交给 `OcOpportunityCostRanker.rank(candidates, currentBaseline, emptyCountMap, factionOcData, now)`；本地拼接的 `recommendComparator` 删除，`emptyCountMap` 保留并作为第 ④ 决胜键传入 |
| L143-155 `calculateCurrentScore` | 用新方法计算当前岗位的机会成本作为基线；`readyTime` 判空后再决定是否做「减一天」口径 |
| `findRecrutList` 内两处 `getReadyTime().minusDays(1)` | 这两处是**候选池归一化**——用户已在该 OC 时，其加入行为已将 readyTime 推出 +1 天，放入候选池前需归一化扣除，使比较公平。改法与评分调用点一致：先用 `OcRecommendTimeSupport.resolvePhase(oc, now)` 判断阶段，`NOT_STARTED`/`PAUSED` 时做 `-1 day`，`NEW_TEAM` 不减（新队 readyTime 为 null，也不存在用户已加入的情形，防御性保护） |
| L153 / L259 / L262（评分调用点） | 三处直调 `getReadyTime().minusDays(1)` 同样改为 `OcRecommendTimeSupport.resolvePhase(oc, now)`；「减一天」补偿只对 `NOT_STARTED` / `PAUSED` 生效 |
| L112-120 `queryCurrentOcStatus` | 逻辑不变，仅跟随 L138 的基线口径变化 |
| 类头 `@version` | 递增 |

`OcRecommendTimeSupport` 放在 `recommend/rank` 包内（final 类 + 私有构造），推荐与分配两个入口共用，避免同一套阶段判断写三遍。判定顺序固定：`readyTime` 为 `null` → `NEW_TEAM`；`readyTime` 晚于 `now` → `NOT_STARTED`；否则 → `PAUSED`。

### 8.2 `torn/service/faction/oc/recommend/TornOcAssignService.java`（298 行）

| 位置 | 改动 |
|---|---|
| L188-191 | 删除名单过滤分支（`isReassign && !getRotationOcNames().contains(...)` 的 `continue`），连同 `findCandidatesForUser` 内 `isReassign` 的生成（`checkIsReassignRecommended` 调用）与传递一并删除 |
| L198-203 | 保留 `setting == null`、成功率不达标、已分配的跳过；`matchedData == null` 不再跳过，改由映射值承接 |
| L206 | 评分改为机会成本口径，复用 `OcOpportunityCostRanker` 的候选构建与比较器；入口处一次性加载全帮派 `torn_faction_oc_user` 传入排序器，与推荐路径保持一致 |
| L166 | 删除就地的 `bestMatch.oc().setReadyTime(...plusDays(1))`。分配过程中把「预期开动时间」放进局部 `Map<Long, LocalDateTime> plannedReadyMap`，只在该方法内使用，不改共享 DO（B5） |
| L219-236 `buildOriginTimeMap` | 同上，不再 `oc.setReadyTime(targetTime)`，改为返回 `origin → planned` 两个 Map |
| L169-173 | 回滚逻辑随之上移为「不写入即无需回滚」，整段删除 |

### 8.3 `torn/manager/faction/crime/recommend/TornOcRecommendManager.java`（287 行）

| 位置 | 改动 |
|---|---|
| L143-190 `calcRecommendScore` 两个重载 + 私有实现 | **整体删除**。两套量纲是本次要消除的对象；调用方全部改走 `OcOpportunityCostRanker` |
| L270-279 `calcPassRateScore` | 保留私有，仅被理由文案使用；不再进入评分 |
| L284-286 `calcPriorityScore` | 保留私有，同上 |
| L227-265 `calculateTimeScore` | 保留，仅用于理由文案的「X 小时内停转」；L229 注释「新队 99 分」改为「新队按 95 分，低于 2 小时内停转队」（D2） |
| L114-138 `checkIsReassignRecommended` | 保留，Javadoc 改写为「判断该帮派是否大锅饭、且该成员是否达到名单内任一岗位要求；**仅用于展示与分钱口径判断，不参与推荐准入**」 |
| L195-222 `buildRecommendReason` | 输出文案保持不变；把散落的 `75 / 70 / 24h / 6h` 阈值收进 `OcRecommendOutcomeEnum` 的静态映射，方法体只做拼接 |

### 8.4 `torn/manager/setting/TornSettingOcReassignManager.java`

| 位置 | 改动 |
|---|---|
| L92-101 `getRotationOcNames` | 实现不变。Javadoc 增加一句：该方法只表达「分钱池包含哪些 OC」，**推荐准入不得调用** |

删除或改动该方法会牵连 `TornOcIncomeService` L339、`TornOcBatchIncomeService` L95、`TornOcIncomeTransactionWorker` L215 与 `OcBenefitRankStrategyImpl`，属于分钱口径，本轮不动。

### 8.5 `torn/manager/setting/TornSettingOcCoefficientManager.java`

| 位置 | 改动 |
|---|---|
| L59 缺行分支 | 由 `return BigDecimal.ZERO` 改为：`log.warn` 打出 `ocName / slotCode / passRate / factionId` 后 `return BigDecimal.ONE` |

理由（真实需求）：系数为 0 会让有效工时归零，帮派分不出这个 OC 的收入且没有任何迹象；兜底为 1 至少保持「按工分分配」的既有语义，同时用告警把缺配置暴露到日志里。

### 8.6 `torn/model/faction/crime/recommend/OcRecommendationVO.java`（57 行）

保留现有构造器与全部字段（`reason` 文案不变，指令层无需改动），新增字段并新增一个构造器：

| 新增字段 | 类型 | 含义 |
|---|---|---|
| `expectedNetPerMemberDay` | `BigDecimal` | 期望净产出 / 人天，用于展示「这个岗位值多少」 |
| `opportunityCost` | `BigDecimal` | 机会成本，排序键本体 |
| `substituteCount` | `Integer` | 除我之外可填人数；`null` 表示未知 |
| `outcome` | `OcRecommendOutcomeEnum` | 稀缺度分档，仅用于解释 |

`recommendScore` 继续写入机会成本（M量级），**并同步更新 `OcRecommendBadgeResolver` 的着色门槛为机会成本量级**：

| 旧门槛（0-100 scale） | 新门槛（M量级） | 色调 |
|---|---|---|
| ≥ 80 | ≥ 300 | SUCCESS（绿） |
| 60~79 | 150~299 | WARNING（琥珀） |
| < 60 | < 150 | NEUTRAL（灰） |

理由：机会成本量级是 M（百万货币），根据业务基线 4.9 最低级 OC 机会成本 ≈ 80-100M，链式高价值 OC 可达 300-500M。直接使用旧门槛会导致全部徽章 SUCCESS，失去区分意义。修法成本最小：只改 `OcRecommendBadgeResolver` 一个类，同步更新 Badge 测试断言即可。

### 8.7 `torn/service/faction/oc/image/OcRecommendBadgeResolver.java`（103 行）

| 位置 | 改动 |
|---|---|
| L约20-30 评分着色逻辑 | 将门槛从 `80 / 60` 改为 `300 / 150`（三个分支各改一处常量） |
| 测试 `OcRecommendBadgeResolverTest` | 同步更新评分测试值，保持相对分布不变 |

---

## 9. 数据与配置

### 9.1 `torn_setting_oc.expected_reward` 修数

映射值改由该字段驱动，因此必须先修掉两个 0 与系统性低估。口径固定为业务基线 4.10 的**口径 B**（成功时奖励 × 成功率）：

```sql
-- 幂等：只更新这两个 OC，且只在当前值明显偏低时更新
update torn_setting_oc set expected_reward = 128100000, update_time = now()
 where deleted = 0 and oc_name = 'Ship Happens'
   and (expected_reward is null or expected_reward < 128100000);

update torn_setting_oc set expected_reward = 148600000, update_time = now()
 where deleted = 0 and oc_name = 'Cleared for Takeoff'
   and (expected_reward is null or expected_reward < 148600000);
```

按工程约定放到 `db/pending/` 并在实施时由用户执行；不写进 Liquibase changelog（数据修数不进 Schema 变更集）。

### 9.2 新增常量（`OcRecommendTunings`）

| 常量 | 默认值 | 来源 |
|---|---|---|
| `MEMBER_DAYS_PER_SLOT` | `4.5` | 业务基线 4.3：实测占用 P50 = 4.00 天，P90 = 6.00 天 |
| `DEFAULT_DAILY_WAIT_COST` | `5.0` | 业务基线 4.9：主力 OC 的期望 M/人天中位量级 |
| `OCCUPANCY_COST_FACTOR` | `1.0` | `0` 时退化为业务基线 5.9 的原式 |
| `MIN_MEASURED_SAMPLES` | `3` | 业务基线 4.6：样本少于 3 个时单位人天产出波动可达一倍 |

四个值都以 `public static final` 常量集中在一个 `final` 类里，不引入 `@ConfigurationProperties`：当前没有按帮派差异化调整的需求，新增配置管道只会增加装配复杂度。每个常量行末必须注释标注数据来源（实测窗口与口径），避免后续维护时被误改。

### 9.3 配置一致性校验（D16）

`torn_setting_oc_reassign_oc`（名单）与 `torn_setting_oc_reassign_faction`（开关）是两张表，分别决定不同的事：名单决定**收益算哪些 OC**，开关决定该帮派**是否进入大锅饭结算与展示**。两者不一致时不会报错，只会静默地生效一半。2026-10-22 已出现一次实例：PTA 的开关行在本地库缺失，导致分析时把「本地缺配置」误判成「名单从未生效」；缺口已由数据补齐，本节的自检用于防止同类问题再次被当成计算结论。

在 `TornSettingOcReassignManager.warmUpCache()` 末尾追加一次只读校验：取 `getOcList()` 里出现的 factionId 集合，减去 `enabledFactionIds()`，差集非空则对每个 factionId 打一条 WARN（含该帮派名单行数）。不改数据、不阻断启动、不影响既有缓存语义。

同一次校验顺带覆盖反向情况：开关已启用但名单为空——此时 `TornOcBatchIncomeService` 会在入口直接跳过，该帮派收益永远不动。

### 9.4 不新增 Schema

本节无 DDL。映射值复用 `torn_setting_oc.expected_reward`，链结构复用 `torn_setting_oc_chain`，稀缺度复用 `torn_faction_oc_user` + `torn_setting_oc_slot`。

---

## 10. 实施顺序

| 步 | 内容 | 完成判据 |
|---|---|---|
| 1 | 新增 `model/.../recommend` 下的 record 与 enum | 编译通过，无调用方 |
| 2 | 新增 `recommend/metric` 六个文件 + `OcYieldEstimatorTest` | 纯领域测试通过；`Ship Happens` 走映射能算出 > 0 |
| 3 | 新增 `recommend/rank` 五个文件 + `OcOpportunityCostRankerTest` | 排序、同队多岗位梯度、确定性决胜、`null` 替代概率四条用例通过 |
| 4 | 接入 `TornOcRecommendService` / `TornOcAssignService` / `TornOcRecommendManager` | T1–T6 全部满足；`TornOcRecommendServiceTest` / `TornOcAssignServiceTest` / `TornOcRecommendManagerTest` 通过 |
| 5 | 更新 `OcRecommendBadgeResolver` 着色门槛 + `OcRecommendBadgeResolverTest` 同步更新 | 低机会成本 OC 徽章 NEUTRAL，高机会成本 OC 徽章 SUCCESS |
| 6 | 系数兜底 + `expected_reward` 修数 | 缺行告警可观测；映射量级与业务基线 4.9 一致 |

每一步都可独立编译与回滚；第 2、3 步不触碰生产入口；第 5 步仅改渲染层不影响排序。

---

## 11. 测试方案（收敛）

### 11.1 新增：2 个纯领域测试类

| 测试类 | 覆盖 |
|---|---|
| `OcYieldEstimatorTest` | ① 实测策略取 `income` 聚合值；② 映射策略在 `expected_reward` 缺失时退回等级表；③ 链归约：奖励只取叶子、人天累加全部步骤；④ 纯道具奖励不为 0 |
| `OcOpportunityCostRankerTest` | ① 替代概率为 0 的岗位排最前；② 停转队因锁死人天排前；③ 替代概率为 `null` 时按中性处理且不高于「确认无人可替代」；④ 同分按 ocId/slotId 决胜；⑤ 同一 OC 的多个岗位各自成行，按替代概率从低到高排列 |

两个类的用例总数控制在 15 条以内，全部是纯函数断言，不启动 Spring、不连数据库。

### 11.2 修改：3 个既有测试类

按「同一被测类只保留一个测试类」的既有约定，不新建 service 层测试：

| 测试类 | 追加的回归 |
|---|---|
| `TornOcRecommendServiceTest` | 名单外 OC 出现在结果里（T1）；矩阵为空时结果非空（T2）；`readyTime == null` 不抛异常（T4） |
| `TornOcAssignServiceTest` | 名单外 OC 参与批量分配；分配后共享 `TornFactionOcDO.readyTime` 未被修改（B5） |
| `TornOcRecommendManagerTest` | 理由文案与改动前逐字一致（保护 `OcRecommendBadgeResolver`） |

### 11.3 明确不做的测试

- 不为新增 record 的 getter、`OcYieldSourceEnum` 的取值、`OcRecommendTunings` 的常量单独建测试；
- 不为每个缺陷（D1–D16）各建一条用例；缺陷通过上表三个服务的回归覆盖；
- 不做并发测试（推荐是只读路径，写路径未改动）；
- 不重复验证 `torn_setting_oc_chain` 的 SQL，链结构由既有 Mapper 测试覆盖。

### 11.4 真实只读回放

用 2026-10 的快照做一次新旧对比，作为上线前的口径证据（只读，不写库）：

```text
对 6 个已开大锅饭的帮派 × 各自的 3 个活跃成员：
  新旧两套排序各取 Top-3
  记录：推荐集合差异数、命中的 OC 是否在名单内、机会成本 Top-1 的替代概率
```

判据：新结果必须能推荐到旧结果里完全缺失的 OC（T1 的实证），且不能把停转队挤出 Top-3。

---

## 12. Review 门禁

### P0（阻断）

1. 仍存在以 `getRotationOcNames` 过滤推荐候选的代码路径；
2. 仍存在 `getReadyTime().minusDays(...)` 直接调用，或把 `readyTime == null` 当成异常分支而不是「新队」状态处理；
3. `torn_faction_oc_income.final_income` 被当作个人份额使用；
4. 系数缺行仍返回 `BigDecimal.ZERO`；
5. 机会成本里出现按 `isReassign` 分叉的第二套公式。

### P1（默认阻断）

1. 替代概率被持久化（写入任何表或缓存）；
2. 剔除 `EQUAL` 帮派时跳过度量层（两种模式必须走同一条度量与排序）；
3. 分配流程仍然就地修改共享 `TornFactionOcDO`；
4. 理由文案发生变化而未同步 `OcRecommendBadgeResolver` 的测试。

### P2（不阻断，记录）

1. `OcRecommendTunings` 的常量取值缺少注释来源；
2. 新包内出现超过 10 个文件而未拆分子包；
3. 空位数为 0 的 OC 仍进入候选生成（性能项）。

---

## 13. 完成与停止条件

**完成**需同时满足：

1. §2.1 的 T1–T6 全部满足；
2. §11.1、§11.2 的测试通过，且全量测试无新增失败；
3. §11.4 的只读回放产出对比结论并归档；
4. §9.1 的数据修数脚本已执行并在库中可见；
5. `TornOcRecommendManager` 中两份 `calcRecommendScore` 已删除，仓库内无残留调用。

**停止条件**（遇到即停并上报，不自行绕过）：

1. 回放发现新排序把停转队挤出 Top-3——说明锁死人天项的权重有问题，需重新确认 `DEFAULT_DAILY_WAIT_COST`；
2. 映射值与业务基线 4.9 的实测值差异超过 20%——说明 `expected_reward` 数据仍有系统偏差，先修数再继续；
3. 需要修改表结构才能实现——说明方案偏离最小改动原则，回到设计。
