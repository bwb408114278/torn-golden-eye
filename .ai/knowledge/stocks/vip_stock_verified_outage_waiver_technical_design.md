# VIP 股票已验证宕机证据豁免技术实施方案

> **文档类型：** 技术实施方案、开发边界与验收标准  
> **状态：** 待用户明确授权“开始实施”；本文件不授权修改代码、Schema、生产状态或开关  
> **业务依据：** `.ai/knowledge/stocks/vip_stock_verified_outage_waiver_business_freeze.md`  
> **代码基线：** `fea396c` / `feat-oc`  
> **版本：** `1.4.6`（以 `pom.xml` 为准）  
> **风险等级：** L3（版本化月度资格规则、历史状态重算、回放输入代际与生产开关前置条件）

---

## 1. 目标与非目标

### 1.1 目标

在不填补、不伪造、不消费 `2026-02-14 08:00:00..15:15:00` 缺失市场数据的前提下，实施业务冻结的单一、版本化月度证据豁免：

```text
exclusionId = TORN_MARKET_OUTAGE_20260214_0801_1515
scope       = ALL_35_STOCKS
reason      = VERIFIED_UPSTREAM_MARKET_DATA_OUTAGE
status      = APPROVED_FOR_MONTHLY_EVIDENCE_ONLY
```

它只改变以下月度完整性判断的日历分母/间隔解释：

```text
usableBarCoverage
maxMissingBucketGap
```

它不改变任何价格、bar、feature、收益、趋势、风险、交易、成交、实时连续性或正常数据质量规则。

### 1.2 不做事项

- 不写 `torn_stocks_history`，不创建虚拟分钟、虚拟 bar 或虚拟 feature；
- 不让不可用 bar 成为 `usable=true`；
- 不将豁免应用到 `Stock15mFeatureRollingWindow`、`strategyReady`、实时轮次、成交、ENTRY/EXIT 或 `DATA_STALE`；
- 不改变 `95% / 120分钟 / 10日` 通用阈值；
- 不自动发现、自动批准或自动新增任何未来缺口；
- 不使用 `manualOverride`，不直接将 DRAFT UPDATE 成 CONFIRMED；
- 不改 BUY / SELL / 资金 / 槽位 / 冷却 / 排序 / 通知规则；
- 不自动打开任何 `VIP_STOCK_*` 开关，也不改变 `RULE_MODE=SHADOW`；
- 不新建运行台账、分片表、报告表或生产回放目录挂载；
- 不实施外部事实导入适配器；它是先前“补数”路径，在不可恢复结论与本业务豁免后应从本轮修复范围移除。

---

## 2. 已验证的业务可行性

基于当前同步生产数据、业务冻结给出的只读模拟和复核 SQL，3～8 月在单一豁免后均满足月度基础数据门禁：

| 生效月 | raw coverage | adjusted coverage | raw 最大间隔 | adjusted 最大间隔 | 日收盘数 |
|---|---:|---:|---:|---:|---:|
| 2026-03 | 99.4351% | 99.9468% | 450 分钟 | 45 分钟 | 60 |
| 2026-04 | 99.6297% | 99.9652% | 450 分钟 | 45 分钟 | 91 |
| 2026-05 | 99.7136% | 99.9652% | 450 分钟 | 45 分钟 | 121 |
| 2026-06 | 99.7724% | 99.9724% | 450 分钟 | 45 分钟 | 152 |
| 2026-07 | 99.7871% | 99.9539% | 450 分钟 | 75 分钟 | 182 |
| 2026-08 | 99.8182% | 99.9606% | 450 分钟 | 75 分钟 | 213 |

这只证明单一排除窗口满足**基础完整性**。风格/风险/迟滞、自动确认和有效回放仍须由实现后的真实重算结果决定；不允许根据本表预写任何月度风格、风险或 CONFIRMED 状态。

---

## 3. 技术方案选择

### 3.1 选定：数据库排除注册表 + 纯月度计算策略

采用数据库表保存人工审批的不可变证据记录，理由：

1. 业务冻结要求审批证据、审批人、审批时间、证据 SHA-256、启停与规则版本可审计；
2. 仅硬编码一个 `if (time == ...)` 无法进入月度快照与回放输入代际，也无法支持未来需另行审批的第二个窗口；
3. 月度计算本来已有一个批量证据读取入口和 JSONB `metric_snapshot`，新增注册表不会污染事实表或交易表；
4. 新表仅由 Liquibase 种子写入首个批准窗口；正常运行路径只读，不存在自动创建/自动学习行为。

### 3.2 不选：不可变 Java 目录

代码目录可避免一张表，但会把审批人、审批时间和证据摘要隐藏在发布物中，且每次未来人工审批都必须重新发版。与业务冻结中的可审计要求不匹配。

### 3.3 不选：通用阈值放宽或时间起点重置

- 放宽到 450 分钟会放行任何未知未来缺口，违反冻结；
- 从宕机结束重置起点会改变成熟度与历史趋势输入，不是“仅排除不可观测窗口”。

---

## 4. Schema 与注册表

### 4.1 新表

新增 Liquibase 文件：

```text
src/main/resources/db/changelog/1.0.1-2.0.0/1.4.6/
└── stocks-monthly-evidence-exclusion.yaml
```

并追加注册到：

```text
src/main/resources/db/changelog/db.changelog-master.yaml
```

新增表：`torn_stock_monthly_evidence_exclusion`

| 列 | 类型 | 非空 | 含义 |
|---|---|---:|---|
| `id` | BIGINT identity | 是 | 主键 |
| `exclusion_id` | VARCHAR(96) | 是 | 稳定业务标识，唯一 |
| `start_time` | TIMESTAMP | 是 | 15 分钟桶起点（含） |
| `end_time` | TIMESTAMP | 是 | 15 分钟桶终点（不含） |
| `scope_type` | VARCHAR(32) | 是 | 首版只允许 `ALL_35_STOCKS` |
| `reason_code` | VARCHAR(96) | 是 | 首版只允许 `VERIFIED_UPSTREAM_MARKET_DATA_OUTAGE` |
| `evidence_reference` | VARCHAR(512) | 是 | 业务证据文档相对路径/稳定引用 |
| `evidence_sha256` | CHAR(64) | 是 | 审批证据内容 SHA-256 |
| `approved_by` | VARCHAR(64) | 是 | 业务审批人标识 |
| `approved_at` | TIMESTAMP | 是 | 审批时间 |
| `rule_version` | VARCHAR(64) | 是 | `PERSONALITY_RULE_V2_OUTAGE_EXCLUSION` |
| `enabled` | BOOLEAN | 是 | 是否作为本规则版本的月度证据排除项 |
| `deleted` | BIGINT | 是 | 逻辑删除，默认 0 |
| `create_time` / `update_time` | TIMESTAMP | 是 | 审计字段 |

约束和索引：

```text
UNIQUE (exclusion_id) WHERE deleted=0
CHECK (start_time < end_time)
CHECK (start_time / end_time 均为15分钟对齐)       // PostgreSQL EXTRACT(minute) % 15 = 0 且 sec=0
CHECK (scope_type='ALL_35_STOCKS')                  // 首版只支持冻结全市场范围
CHECK (reason_code='VERIFIED_UPSTREAM_MARKET_DATA_OUTAGE')
CHECK (char_length(evidence_sha256)=64)
INDEX (enabled, start_time, end_time) WHERE deleted=0
```

**重叠限制：** 由于 PostgreSQL range exclusion constraint 与业务范围/逻辑删除组合较复杂，首版不用触发器或运行时全表扫描。改由 DAO 的单一读取 SQL 中按时间升序加载，并在 `MonthlyEvidenceExclusionPolicy` 构造时 fail-fast 验证：任意 enabled 窗口重叠、未对齐、非法 scope/reason、规则版本混杂均抛出异常。若排除注册表损坏，月度重算必须失败，不得静默忽略。

### 4.2 首条种子数据

同一 changeSet 中只插入这一条：

```text
TORN_MARKET_OUTAGE_20260214_0801_1515
[2026-02-14 08:00:00, 2026-02-14 15:15:00)
ALL_35_STOCKS
VERIFIED_UPSTREAM_MARKET_DATA_OUTAGE
PERSONALITY_RULE_V2_OUTAGE_EXCLUSION
enabled=true
```

`evidence_reference` 固定为：

```text
.ai/knowledge/stocks/vip_stock_verified_outage_waiver_business_freeze.md
```

已冻结的首条证据与审批字段如下，YAML 必须逐字使用：

```text
evidence_sha256 = 7072d83cb7aaf17e907b1391e0e4560491dc238cda299d38e59dafc36fe807a0
approved_by     = Bai
approved_at     = 2026-08-25 00:00:00 (Asia/Shanghai)
risk_rule_version = RISK_RULE_V2_OUTAGE_EXCLUSION
```

`evidence_sha256` 由业务冻结 Markdown 的实际 UTF-8 文件内容以 SHA-256 计算，开发不得重排、格式化或修改该冻结文档后继续复用该 hash。`approved_at` 写入 PostgreSQL 不带时区 `TIMESTAMP` 时保持上述 Asia/Shanghai 本地时间。

### 4.3 Java 持久化层

新建：

```text
src/main/java/pn/torn/goldeneye/repository/model/torn/stocks/portfolio/
└── TornStockMonthlyEvidenceExclusionDO.java

src/main/java/pn/torn/goldeneye/repository/mapper/torn/stocks/portfolio/
└── TornStockMonthlyEvidenceExclusionMapper.java

src/main/java/pn/torn/goldeneye/repository/dao/torn/stocks/portfolio/
└── TornStockMonthlyEvidenceExclusionDAO.java

src/main/resources/mapper/torn/stocks/portfolio/
└── TornStockMonthlyEvidenceExclusionMapper.xml
```

DAO 只提供：

```text
selectEnabledByTimeRange(startInclusive, endExclusive, ruleVersion)
```

SQL 必须：

```text
start_time < endExclusive
AND end_time > startInclusive
AND enabled=true
AND deleted=0
AND rule_version=#{ruleVersion}
ORDER BY start_time ASC, exclusion_id ASC
```

不提供 insert/update/delete 的业务 DAO 方法；审批和首个窗口仅来自 Liquibase。这样不会给线上运行留下“动态新增豁免”入口。

---

## 5. 月度领域计算

### 5.1 新增领域包

新建：

```text
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/exclusion/
├── MonthlyEvidenceExclusion.java
├── MonthlyEvidenceExclusionPolicy.java
└── MonthlyEvidenceAdjustment.java
```

职责：

- `MonthlyEvidenceExclusion`：纯不可变 record，表达已加载/验证后的一个排除窗口；
- `MonthlyEvidenceExclusionPolicy`：构造时验证窗口排序、对齐、non-overlap、首版 scope/reason 与规则版本；提供对一个证据范围的调整；
- `MonthlyEvidenceAdjustment`：原始/调整后分母、间隔、已应用 IDs、排除桶/分钟的纯计算结果。

禁止该包依赖 DAO、Spring、Clock、Scheduler、回放、交易、通知、JsonUtils 或任何实体写入。

### 5.2 修改 `StockMonthlyEvidenceComputer`

文件：

```text
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/StockMonthlyEvidenceComputer.java
```

新增重载，原签名保留用于无豁免兼容测试：

```text
computeMetrics(evidenceStart, evidenceEnd, usableBars, exclusionPolicy)
```

实现顺序：

1. 先按照现有真实 `usableBars` 计算日收盘、趋势、收益、月均、回撤、投票；这些路径绝不接收或生成虚拟 bar；
2. 按原公式计算并保存：
   ```text
   rawExpectedBuckets
   rawUsableBarCoverage
   rawMaxMissingBucketGap
   ```
3. `MonthlyEvidenceExclusionPolicy` 仅针对 evidence 范围相交的已审批窗口计算：
   ```text
   excludedBucketCount
   excludedMinutes
   appliedExclusionIds
   adjustedExpectedBuckets = rawExpectedBuckets - excludedBucketCount
   adjustedUsableBarCoverage = usableBarCount / adjustedExpectedBuckets
   adjustedMaxMissingBucketGap = max(rawGap - actualWindowOverlap, otherRawGaps)
   ```
4. `complete` 使用：
   ```text
   adjustedUsableBarCoverage >= 0.95
   adjustedMaxMissingBucketGap <= 120
   dailyCloseCount >= 10
   trend.complete
   evidenceDays > 0
   ```
5. 没有相交排除项时，adjusted 字段必须严格等于 raw 字段，`appliedExclusionIds=[]`。

#### 关键边界

- **排除桶按 15 分钟定义，不按缺失分钟定义。** 首窗为 `[08:00,15:15)`，故 `29` 桶、`435` 日历分钟；原始事实仍显示真实的 434 分钟缺口；
- 每个已审批窗口只扣除与**证据区间**的相交部分；窗口部分落在证据范围外不得扣分母；
- 相邻可用 bar 间隔只减去它们之间的排除窗口重叠时长，不能对任何不跨越豁免窗口的 gap 处理；
- 如已有 bar 的时间位于排除窗口内，仍按真实 usable bar 参与真实价格和趋势计算；窗口不会删除或屏蔽真实数据；
- 一条非豁免 135 分钟 gap 仍必须导致不完整；不允许因为同一证据范围有一个豁免窗口而整体放松阈值；
- 证据范围无效、调整分母小于等于 0、注册表不合法必须 fail-closed，使用既有 `MONTHLY_EVIDENCE_INCOMPLETE`，日志包含排除 ID 但不得包含敏感内容。

### 5.3 扩展 `StockMonthlyEvidenceMetrics`

文件：

```text
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/StockMonthlyEvidenceMetrics.java
```

在保持现有原始字段含义不变的前提下新增：

```text
rawExpectedBucketCount
rawUsableBarCoverage
rawMaxMissingBucketGap
excludedBucketCount
excludedMinutes
adjustedExpectedBucketCount
adjustedUsableBarCoverage
adjustedMaxMissingBucketGap
appliedExclusionIds       // 有序不可变 List<String>
```

原 `usableBarCoverage` / `maxMissingBucketGap` 的兼容处理：

- 将其保留为**调整后、用于完整性判定**的指标，避免下游已有快照字段读到过时口径；
- 新增 raw 字段，确保报告和快照不隐藏真实 450 分钟缺口；
- Javadoc 必须明确字段含义，禁止以“coverage”笼统描述。

### 5.4 修改 `StockMonthlyStateCalculator`

文件：

```text
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/StockMonthlyStateCalculator.java
```

新增规则常量：

```text
PERSONALITY_RULE_V2_OUTAGE_EXCLUSION
RISK_RULE_V2_OUTAGE_EXCLUSION
```

说明：业务文档只明确“拟新增 `PERSONALITY_RULE_V2_OUTAGE_EXCLUSION` 与对应风险规则版本”，完整冻结名称应采用同前缀的 `RISK_RULE_V2_OUTAGE_EXCLUSION`，并在实现前由业务确认。如果业务要求使用其它精确字符串，以其书面值为准。

计算器接收 `MonthlyEvidenceExclusionPolicy`；它仍是纯计算组件，不访问数据库。

完整和不完整快照均必须固化：

```text
rawUsableBarCoverage
rawMaxMissingBucketGap
rawExpectedBucketCount
excludedBucketCount
excludedMinutes
adjustedUsableBarCoverage
adjustedMaxMissingBucketGap
adjustedExpectedBucketCount
appliedExclusionIds
```

同时保留旧 `usableBarCoverage` / `maxMissingBucketGap`，值等于 adjusted，供现有审计读取兼容。`rawPersonality`、`rawRiskLevel`、迟滞、投票和价格指标仍基于真实 bar，不能因为豁免而变化输入集合。

### 5.5 修改 `StockMonthlyStateInitService`

文件：

```text
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/StockMonthlyStateInitService.java
```

通过构造器注入：

```text
TornStockMonthlyEvidenceExclusionDAO
```

在 `loadEvidenceContext()` 内：

1. 基于全体 `stockIds`、本月计算的最大 evidence range 一次批量加载相交的 enabled exclusion；
2. 映射并构造一次 `MonthlyEvidenceExclusionPolicy`，同批次全部股票复用；
3. `buildDraftState()` 将 policy 传入 calculator。

禁止每支股票查询注册表，禁止把 exclusion 作为静态字段/ThreadLocal，禁止按当前系统时间动态判断审批范围。

**版本行为：**

- 使用 V2 policy 重新计算的 3～8 月 DRAFT 写 V2/V2 规则版本；
- `autoConfirmDraftStates()` 只能接受 V2/V2 的完整 state；
- V1 已 CONFIRMED 状态不得由 `recalculateDraftStates` 覆盖；
- 如后续要重新计算已 CONFIRMED 的 V1 月，需要另行明确“版本化替代/历史状态迁移”业务方案，本轮不做。

### 5.6 复用现有按月正序重建

无需改动：

```text
src/main/java/pn/torn/goldeneye/torn/service/stocks/rebuild/StockMonthlyStateRangeRebuildService.java
```

现有实现已经按：

```text
initMonth → recalculateMonthDrafts → autoConfirmDraftStates
```

按 `effectiveMonth ASC` 顺序执行。这满足 V2 迟滞需要上月已确认状态的依赖。实施后仅需用既有重建入口对 `[2026-03-01, 2026-09-01)` 执行；该生产动作仍须单独授权。

---

## 6. 回放和可观测性

### 6.1 回放 manifest

现有 `StockReplayInputDigest` 已摘要月度决策字段和规则版本，但未摘要 `metricSnapshot`。新增 V2 后若只改排除审计字段而最终风格相同，manifest 也必须变化。

修改：

```text
src/main/java/pn/torn/goldeneye/torn/service/stocks/replay/StockReplayInputDigest.java
```

在 `appendMonthlyStates()` 中追加：

```text
state.getMetricSnapshot()
state.getEvidenceStartTime()
state.getEvidenceEndTime()
state.getStateStatus()
```

这样 raw/adjusted 指标、应用的 exclusion ID、规则版本和确认态均影响 `contentSha256`。不需要修改 `StockReplaySourceManifest` record 的字段；manifest 已包含月度规则版本和 content hash，且摘要会随 V2 快照变化。

补充 Javadoc，明确 `metricSnapshot` 是回放输入证据，不是运行时策略指标。

### 6.2 Readiness 报告

现有 `StockDataReadinessReportRunner` 是面向分钟/bar/feature总体质量的只读报告；它不加载单股月度 JSONB 详细指标。

**最小实现选择：不修改 readiness runner/SQL/report record。** 理由：

- 本次业务要求“readiness 同时显示 raw 与 adjusted”针对月度资格证据；
- 现有 report 已可靠显示原始分钟缺口、最大缺口、月度状态计数和不完整原因；
- V2 月度 `metricSnapshot` 将持久化 raw/adjusted 指标和 exclusion IDs，可用一次专用只读 SQL 生成月度审核表；
- 将逐股 JSONB 月度审计聚合强行塞进全局 readiness report，会扩大已优化 LATERAL 查询的责任与测试面。

新增**本地只读**月度审核 runner：

```text
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/
├── StockMonthlyEvidenceAuditRunner.java
└── StockMonthlyEvidenceAuditReport.java
```

并在现有 `TornStockMonthlyStateMapper.xml` 新增范围批量查询：

```text
selectByEffectiveMonthRange(startMonth, endMonth)
```

runner 复用 `StockReplayReadOnlyGuard`，在 `READ ONLY + REPEATABLE READ` 中读入 3～8 月状态；输出到：

```text
.hermes/output/vip-stock-monthly-evidence/
```

报告按月、股票显示：

```text
stateStatus
personality/risk/maturity
raw coverage / raw max gap
adjusted coverage / adjusted max gap
excluded count/minutes
applied exclusion IDs
rule versions
incompleteReason
```

报告的 JSON 与 Markdown 从同一不可变 record 写出，使用临时文件 + 原子改名；不写业务表，不注册调度、不暴露 Bot 指令。

**注意：** 这是本地审核产物，不是生产回放目录挂载，也不是新报告表。

---

## 7. 与既有修复方案的关系

`.ai/knowledge/stocks/vip_stock_data_rebuild_review_remediation.md` 必须在实施时收敛：

1. 删除“外部事实导入适配器 / `VERIFIED_EXTERNAL_BACKFILL` / 15,190 事实补入”章节、测试和运维验收；该路径已被不可恢复结论与 V2 业务豁免替代；
2. 保留并优先实施两个仍有效 P1：
   - 预热期 30 日条件指标的统一 `NULL` 语义；
   - 全范围派生重建的 `start - 30 days` warmup bar 闭包；
3. 将月度重算/回放顺序改为“V2 排除注册表部署后按月正序重算”，不再要求补缺口事实；
4. 永久设计文档更新仅限稳定语义：排除只适用于月度证据，不适用于实时连续性和交易输入。

---

## 8. 精确文件清单

### 新增

```text
src/main/java/pn/torn/goldeneye/repository/model/torn/stocks/portfolio/TornStockMonthlyEvidenceExclusionDO.java
src/main/java/pn/torn/goldeneye/repository/mapper/torn/stocks/portfolio/TornStockMonthlyEvidenceExclusionMapper.java
src/main/java/pn/torn/goldeneye/repository/dao/torn/stocks/portfolio/TornStockMonthlyEvidenceExclusionDAO.java
src/main/resources/mapper/torn/stocks/portfolio/TornStockMonthlyEvidenceExclusionMapper.xml
src/main/resources/db/changelog/1.0.1-2.0.0/1.4.6/stocks-monthly-evidence-exclusion.yaml
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/exclusion/MonthlyEvidenceExclusion.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/exclusion/MonthlyEvidenceExclusionPolicy.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/exclusion/MonthlyEvidenceAdjustment.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/StockMonthlyEvidenceAuditRunner.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/StockMonthlyEvidenceAuditReport.java
src/test/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/exclusion/MonthlyEvidenceExclusionPolicyTest.java
src/test/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/StockMonthlyEvidenceAuditRunnerTest.java
src/test/java/pn/torn/goldeneye/repository/dao/torn/stocks/portfolio/TornStockMonthlyEvidenceExclusionMapperTest.java
```

### 修改

```text
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/StockMonthlyEvidenceComputer.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/StockMonthlyEvidenceMetrics.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/StockMonthlyStateCalculator.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/StockMonthlyStateInitService.java
src/main/java/pn/torn/goldeneye/repository/dao/torn/stocks/portfolio/TornStockMonthlyStateDAO.java
src/main/resources/mapper/torn/stocks/portfolio/TornStockMonthlyStateMapper.xml
src/main/java/pn/torn/goldeneye/torn/service/stocks/replay/StockReplayInputDigest.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/rebuild/Stock15mFeatureCalculator.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/rebuild/Stock15mFeatureRollingWindow.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/rebuild/StockDerivedDataRebuildService.java
src/main/resources/db/changelog/db.changelog-master.yaml
src/test/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/StockMonthlyEvidenceComputerTest.java
src/test/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/StockMonthlyStateCalculatorTest.java
src/test/java/pn/torn/goldeneye/torn/service/stocks/alert/monthly/StockMonthlyStateInitServiceTest.java
src/test/java/pn/torn/goldeneye/torn/service/stocks/replay/StockReplayInputDigestTest.java
src/test/java/pn/torn/goldeneye/torn/service/stocks/rebuild/Stock15mFeatureCalculatorTest.java
src/test/java/pn/torn/goldeneye/torn/service/stocks/alert/market/Stock15mFeatureBuildServiceTest.java
src/test/java/pn/torn/goldeneye/torn/service/stocks/rebuild/StockDerivedDataRebuildServiceTest.java
.ai/knowledge/stocks/vip_stock_data_rebuild_review_remediation.md
.ai/knowledge/stocks/vip_stock_alert_technical_design.md
```

### 明确不修改

```text
StockMonthlyStateRangeRebuildService.java
StockReplayRunner.java
StockReplayInputLoader.java
StockReplaySourceManifest.java
StockDataReadinessReportRunner.java
StockDataReadinessQueryMapper.xml
VipStockAlertScheduler.java
StockRoundTransactionService.java
StockBuySignalEvaluator.java
StockBatchPathService.java
任何 VIP_STOCK_* 设置值
```

---

## 9. 测试设计

### 9.1 月度排除纯领域：主测试层

`MonthlyEvidenceExclusionPolicyTest` 必须覆盖：

1. 无排除：adjusted 与 raw 完全相同；
2. 完整包含首窗口：29 bucket / 435 minute 排除；跨窗口的单个相邻 bar gap 从 raw 450 调整为 15。月度最大 gap 的 45/75 由其它未豁免 gap 决定，必须在具有前后真实 bar 的夹具中断言；
3. 仅部分相交：只扣交集的 15 分钟倍数；
4. 不相交：零扣减；
5. 多窗口：有序且不重叠时累计，gap 只扣实际跨越窗口的时间；
6. 重叠、未15分钟对齐、`start>=end`、scope/reason/ruleVersion非法：构造 fail-fast；
7. disabled 排除项不进入 policy。

`StockMonthlyEvidenceComputerTest` 增加真实 bar 序列夹具：

1. 450 分钟 gap 跨首窗口：该相邻 bar 对的 raw gap=450、扣除完整 435 分钟排除窗口后 adjusted gap=15；同时构造另一条未豁免的45分钟 gap，断言该月份的 adjustedMaxMissingBucketGap=45、coverage 从 raw 改为 adjusted，`complete=true` 前提是其余指标满足；
2. 同样 450 gap、无 policy：继续不完整；
3. 135 分钟的非豁免 gap：即使同时存在首窗口，仍 `complete=false`；
4. 已排除区间内不制造 bar，趋势/收益/daily close 输入数量与无虚拟数据基线一致；
5. 原始与调整字段均可读取。

### 9.2 月度状态接线

`StockMonthlyStateCalculatorTest`：

- V2 完整草稿：具有真实 style/risk、快照包含 raw/adjusted/IDs、版本为 V2；
- 证据仍不完整：继续 DRAFT，style/risk 为 null；
- 前月 V2 confirmed 的 raw 字段可参与迟滞；
- V1 confirmed 不被重算路径覆盖（service/mapper 条件验证）。

`StockMonthlyStateInitServiceTest`：

- 每次批量重算只加载一次相交 exclusion，不按股票查询；
- 注入的 policy 被全部 stock 共用；
- `autoConfirmDraftStates` 仅确认 V2 完整快照；手工覆盖与状态条件保持不变。

### 9.3 回放可归属

`StockReplayInputDigestTest`：仅变更月度 `metricSnapshot` 中 `appliedExclusionIds`、raw/adjusted 值、`stateStatus`、evidence 边界时，digest 都必须改变；相同月度快照稳定一致。

### 9.4 真实 PostgreSQL

`TornStockMonthlyEvidenceExclusionMapperTest`：

- Liquibase 后读取首个 seed；
- 时间相交查询严格左闭右开；
- disabled/逻辑删除/错误规则版本不返回；
- 测试限定自身 fixture，使用项目约定精确物理清理，不手推 sequence。

`TornStockMonthlyStateAutoConfirmMapperTest` 保持并增加一项：V2 完整 DRAFT 的 `metric_snapshot` JSONB 保持 raw/adjusted/ID 数组并可在 auto-confirm 后原样回读。

### 9.5 预热与全范围重建遗留 P1

按既有 remediation 文档保留：

- 预热少于2880：全部30日条件字段 null；
- 恰好2880、high==low 和不连续分支；
- range rebuild 的 warmup bar 闭包，不产生 warmup `REPAIRED_DATA_ONLY` 或历史业务副作用。

---

## 10. 验证命令与生产验收

### 10.1 本地验证（必须串行）

```bash
JAVA_HOME="C:\\Program Files\\Java\\jdk-21" mvn.cmd compile -q -DskipTests -Dmaven.compiler.showDeprecation=true

JAVA_HOME="C:\\Program Files\\Java\\jdk-21" mvn.cmd test -Dtest="MonthlyEvidenceExclusionPolicyTest,StockMonthlyEvidenceComputerTest,StockMonthlyStateCalculatorTest,StockMonthlyStateInitServiceTest,StockMonthlyEvidenceAuditRunnerTest,StockReplayInputDigestTest,Stock15mFeatureCalculatorTest,Stock15mFeatureBuildServiceTest,StockDerivedDataRebuildServiceTest"

JAVA_HOME="C:\\Program Files\\Java\\jdk-21" mvn.cmd test -Pshared-db-test -Dshared.db.tests="TornStockMonthlyEvidenceExclusionMapperTest,TornStockMonthlyStateAutoConfirmMapperTest,TornStockStrategyFeature15mMapperTest"

JAVA_HOME="C:\\Program Files\\Java\\jdk-21" mvn.cmd clean test

git diff --check
```

### 10.2 部署后、生产操作前的只读验收

1. Liquibase 存在首条 enabled exclusion，字段、边界、hash、审批信息、规则版本与业务冻结一致；
2. 原始分钟/bar 缺口仍然存在并在普通 readiness 中可见：不能出现虚构的 `TORN_API` / `TORNSY_BACKFILL` 行；
3. 现有实时最新 7 天仍无排除影响，35×672 `strategyReady=true`；
4. 预热特征抽样：`INSUFFICIENT_HISTORY` 的全部30日条件字段均为 null；
5. 全范围重建 warmup 仅补 bar，不产生数据修复 round/业务副作用。

### 10.3 经单独授权后的受控生产动作

1. 部署代码并确认 Liquibase 成功；
2. 正常重启，保持全部开关原值；
3. 对 `[2026-03-01, 2026-09-01)` 运行既有按月正序重算；
4. 生成本地只读 monthly evidence audit，逐股核验 V2 raw/adjusted、IDs、DRAFT/CONFIRMED和规则版本；
5. 重新运行完整数据范围的 `ONLINE_BASELINE` 与 `RESTART_STRESS`；
6. 审核两套四产物：全部轨道、输入 manifest、拒绝原因、trade CSV、equity CSV，确认每个正式交易没有跨 `[2026-02-14 08:00,15:15)` 成交；
7. 再由 AI 执行开关就绪 Review；仅无 P0/P1且用户单独批准时，才讨论：

```text
VIP_STOCK_NEW_ENTRY_ENABLED=true
VIP_STOCK_FORMAL_NOTICE_ENABLED=false
VIP_STOCK_DAILY_SUMMARY_ENABLED=false
VIP_STOCK_RULE_MODE=SHADOW
```

---

## 11. 完成与停止条件

开发完成的标准：

1. 首个排除窗口只能由 Liquibase 种子存在，运行时无创建能力；
2. 无排除/首窗口/部分相交/非豁免未来缺口均有纯领域证据；
3. raw 与 adjusted 指标和 ID 进入 V2 月度 JSONB 快照；
4. replay digest 对任何 V2 月度证据快照变化敏感；
5. V1 已确认状态、manualOverride、实时 strategyReady、事实/bar/feature输入及交易链不被改变；
6. 预热 NULL 与 warmup bar 闭包两个旧 P1 同时关闭；
7. 编译、聚焦测试、真实 Mapper 和全量 Maven 通过；
8. `git diff --check` 通过；
9. 不执行生产月度重算、回放或开关修改。

达到以上标准后停止实施，等待代码 Review。代码通过不等于月度状态、回放或开关已经通过生产验收。
