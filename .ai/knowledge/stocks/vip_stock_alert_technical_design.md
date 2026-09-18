# VIP股票 α=0.04 长期技术设计基线

## 1. 文档定位

- 文档类型：长期技术架构与实现边界
- 业务范围：α=0.04 首批股票提醒
- 长期业务基线：`.ai/knowledge/stocks/vip_stock_virtual_portfolio_strategy.md`
- 当前一次性技术方案契约：`.ai/knowledge/stocks/vip_stock_alert_alpha_convergence_technical_plan_one_time.md`（A/B/C/D 统一开发任务；完成§12验收后删除并把结论并入§13）
- 业务验收依据：`.ai/knowledge/stocks/vip_stock_alert_business_review_conclusion_one_time.md`
- 时区：`Asia/Shanghai`
- 状态：第六批业务Review不通过；当前代码整改范围仅为`R-ALPHA-PRE-001`换仓通知组租约恢复/领取释放组状态同步及领取者安全收敛。`R-ALPHA-PRE-002`仅FORMAL允许VIP_ALPHA正式新入场、`R-ALPHA-PRE-003`通知链统一业务时钟属于已确认的代码层基本通过项，仅保留直接回归验证。`R-ALPHA-PRE-004`正式环境证据、`R-ALPHA-PRE-006`通知数据来源确认及真实BUY/SELL验收属于部署后门禁，不能写成代码已完成。
- 早间决策窗口（A）：α新决策与"已结束自然日快照"后移至`08:00`起的已结束桶，Tornsy巡检提前至07:00、股票日报提前至08:10；该变更以§4.3.1、§5.4和统一方案为基线。
- 双信号快照（B）：决策时同时落"前一日23:45收盘"与"08:00现价"两套口径，**第二套只写不读**，生产下单与通知行为不变；契约见§4.6。
- 多槽相位分散（C）：**只在影子组合`VIP_ALPHA_SHADOW`（2槽×5B、相位偏移0/2）观察，正式仓`VIP_ALPHA`零改动**；契约见§4.7、§5.1。
- 旧策略退场（D）：候选影子、无限资金影子、拒绝观察与旧版信号/回放链一并停止；弃用规格与清单见`.ai/knowledge/stocks/vip_stock_strategy_version_history.md`。

本文坚持最小改动：α是现有股票提醒系统中的新入场决策分支，不建设第二套股票平台。所有新增Java、Schema和测试必须能映射到本文的生产入口和验收证据；无法映射的扩展不得纳入本次开发。

---

## 2. 长期冻结的技术原则

1. α是当前唯一的新正式入场来源；旧版三类BUY、qualityScore、旧版五槽竞争不再参与新α入场。
2. 旧版已有批次继续按创建时规则收尾，不改写为α，不因α切换而删除或重新解释。
3. `StockRuleModeEnum`为运行模式枚举；`OFF`只禁止新买入研究和正式入场，`SHADOW`与`PROVISIONAL`不得创建`VIP_ALPHA`正式批次，只有`FORMAL`在readiness和开关通过后才允许Alpha正式新入场。
4. 复用现有调度、15分钟bar、批次、槽位、资金结算、通知和审计能力。
5. 只在公共模型无法表达业务边界时增加字段、查询条件或分支。
6. `VIP_ALPHA`为独立10B逻辑资金槽，`slot_no=1`；`VIP_FORMAL`继续使用既有5个2B槽位。
7. α与旧版允许同时持有同一股票，但资金、批次、SELL配对、规则版本和收益统计必须按组合CODE隔离。
8. 日线、排名、phase、执行bar和换仓语义只实现一份，使用不可变规则对象和纯领域计算器复用。
9. 不新增动态SELL、复杂Shadow运行轨道、第二批炒股推荐或研究平台。
10. 公共入场/成交组装只补充实际成交事实，不得把批次已经冻结的Alpha规则身份（`portfolio_code`、`primary_strategy`、买入/卖出/分配/消息规则版本）覆盖为旧版默认值；历史旧版批次没有专用身份，继续使用旧版默认值。
11. Alpha消息必须经Alpha感知的最小渲染分支，复用既有BUY/SELL发送、审计、payload冻结与幂等链；不得把`primaryStrategy=ALPHA`交给只认识旧版三类BUY的解析器，不得展示旧版`qualityScore`或旧版五槽容量语义，不新增第二套Alpha消息产品。
12. 决策事实与执行事实必须分列保存：决策桶起点（`decision_bar_start_time`）、执行桶起点（`execution_bar_start_time`）、批次来源bar（`signal_time`）与批次执行bar（`entry_time`/`exit_time`）不得互相冒充。
13. α新决策与已结束自然日快照只允许在`StockAlphaRuleDefinition.DECISION_WINDOW_START`（`08:00`，Asia/Shanghai）起的已结束桶内产生，执行桶仍为该决策桶的严格下一根15分钟bar；窗口只约束"新建事实"，不约束已持久化决策的复用、消费与执行。
14. 价格口径只允许一份实现：`PREVIOUS_CLOSE`（已结束自然日23:45收盘）是**唯一生产下单口径**；`LATEST_PRICE`（08:00现价追加一天）只写入观察列，全仓不得有生产代码消费观察列。
15. 相位语义只允许一份实现（`StockAlphaPhaseTrack`与轨道注册表）：决策日、phase与槽位归属不得在业务类内重复计算；**各槽唯一变量是相位偏移**，出现第二个变量即打回。
16. 多槽只落在影子组合；正式仓`VIP_ALPHA`的槽数、资金、账本、批次身份与通知模板在交付期间零改动。影子通知只落审计记录（`SHADOW_RECORDED`），**永不进入可发送集合**。
17. 旧版信号评估、候选影子、无限资金影子、拒绝观察与回放研究链进入停用范围；停用后不得再写入对应账本，历史行与其枚举值保留以保证可解析。

---

## 3. 生产入口与现状边界

### 3.1 现有主入口

```text
VipStockAlertScheduler.executeRound()
→ Stock15mBarBuildService
→ Stock15mFeatureBuildService
→ StockRoundTransactionService
→ 现有批次/槽位/通知能力
```

重点现有文件：

```text
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/market/round/VipStockAlertScheduler.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/market/round/StockRoundTransactionService.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/market/StockAlertRuntimeGate.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/market/Stock15mBarBuildService.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/portfolio/StockPortfolioService.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/portfolio/StockBatchPathService.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/portfolio/StockBatchExitService.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/portfolio/StockEntrySettlementService.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/portfolio/StockVirtualBatchAssembler.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/notice/StockNoticeComposeService.java
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/notice/StockNoticeSendService.java
```

### 3.2 新旧链路

```text
现有调度入口
→ 现有bar构建
→ α日线收盘快照/排名/phase（新增分支）
→ 现有批次、槽位和结算服务（按portfolioCode隔离）
→ 现有通知审计和发送服务
```

旧版BUY候选链保留供历史、回放和存量兼容使用，但切换后不得从生产新入场路径产生新的正式旧版批次。不得把α塞入`StockBuySignalEvaluator`、`BuyStrategyMatcher`或三个旧策略类。

---

## 4. α业务技术契约

### 4.1 固定输入和公式

- 股票池固定35支，`TCSE`不参与选股。
- 自然日收盘为该日最后一根`usable=true`且`last_price>0`的15分钟bar。
- 35支必须全部具备合法收盘才构成共同有效日。
- 至少60个共同有效自然日后，达到60、65、70……日时产生合法`phase=0`决策。
- `r20/r1/R20/R1/alphaScore`使用`BigDecimal`，scale 18、`HALF_UP`；同分采用平均名次；最终按`alphaScore DESC → stocksId ASC`。

```text
r20 = close / 20个有效观察日之前close - 1
r1  = close / 前一个有效观察日close - 1
R20 = rank(-r20)
R1  = rank(r1)
alphaScore = 0.96 × R20 + 0.04 × R1
```

排名算法只存在于一个纯组件中，预填、线上和回放不得各写一套。

### 4.2 目标策略

```text
无α开放持仓 → ALPHA_INITIAL_ENTRY，选择Top1
持仓仍在Top3 → ALPHA_TARGET_HELD
持仓跌出Top3且Top1不同 → ALPHA_TARGET_CHANGED
```

`DATA_INSUFFICIENT`不是正式决策类型，不消费phase；执行bar失败属于已提交决策的执行失败，不追补更晚bar。

### 4.3 执行bar

统一使用一个`StockAlphaExecutionBarPolicy`：

```text
signalBucketStart = decisionTime向下对齐15分钟边界
expectedExecutionBarStart = signalBucketStart + 15分钟
```

只允许使用该精确桶、已结束、可用且价格合法的bar；不跨断层、不使用后续bar。初始BUY和换仓共用该策略，执行桶写入决策/批次，重启时只恢复同一桶。决策桶（`signalBucketStart`）必须显式持久化为`torn_stock_alpha_decision.decision_bar_start_time`，不得由执行桶反推冒充决策时点；批次的`signal_time`为该决策桶，`entry_time`/`exit_time`为执行桶。

### 4.3.1 决策窗口（早间）

α新决策与"已结束自然日快照"只允许在窗口内产生，窗口起点为`StockAlphaRuleDefinition.DECISION_WINDOW_START = 08:00`（`Asia/Shanghai` 墙钟，语义为"允许的最早已结束桶起点"）。

```text
08:00 桶（08:15:10 处理）→ 构建已结束自然日快照 + 落决策
                          decision_bar_start_time = 08:00，execution_bar_start_time = 08:15
08:15 桶（08:30:10 处理）→ 初始BUY / 原子换仓成交，写入PENDING通知审计
同一分钟轮次尾部          → sendPendingNotices()，播报 ≈ 08:30–08:31
跟随窗口                  → follow_until = entry_time + 60min = 08:15–09:15
```

- 判定宿主唯一：`StockAlphaExecutionBarPolicy#isDecisionWindowOpen(LocalDateTime)`；业务类不得内联时间比较或硬编码`08:00`。
- 语义为"不早于"而非"等于"：当日快照瞬时未就绪或决策bar不可用时按桶重试，只顺延当日成交与播报，不丢失phase。
- 窗口只约束"新建决策"与"已结束自然日快照构建"；已持久化决策的复用、消费、执行、结算与通知不受窗口约束，保证切换发版瞬间的在途决策不被饿死。
- 执行桶关系不变：仍为决策桶的严格下一根15分钟bar；不跨桶追补。
- 上游时序：Tornsy每日巡检 07:00 → 股票日报 08:10 → α窗口 08:00 起。

### 4.4 换仓原子性

目标变化时在一个现有事务边界内完成：

```text
锁VIP_ALPHA槽 → 锁当前α批次/决策
→ 校验旧仓和新目标同一执行桶
→ 校验价格、现金、整数股和结算
→ 原仓SELL + 新仓BUY + 槽位绑定 + 通知审计
→ 提交
```

任一侧失败，整体回滚，原仓保持OPEN，不产生单侧SELL、孤儿BUY或孤儿通知事实。不得先调用公共旧版退出服务再单独创建新BUY。

### 4.5 α退出

α唯一正常策略SELL为`ALPHA_REBALANCE`。不得触发旧版固定止盈、固定止损、14天、RANGE或动态SELL。管理关闭属于独立管理事件，不得伪装成策略换仓。

### 4.6 双信号快照（B）

决策时在**同一份**日线与排名实现上计算两套口径：

```text
PREVIOUS_CLOSE : 已结束自然日23:45收盘序列(现行生产口径)
LATEST_PRICE   : 把08:00桶lastPrice当作新增一天追加到序列末尾
                 (r1 = 08:00价 / 前日23:45收盘; r20 = 08:00价 / 前第20个共同有效日)
```

- 两套口径必须复用同一个`StockAlphaRankingCalculator`与同一个`StockAlphaTargetPolicy`，不得复制排名或目标实现；
- 生产目标只取`selected_stocks_id`；观察口径写`alt_signal_reference_price`/`alt_selected_stocks_id`/`alt_source_snapshot_digest`；
- 观察口径失败只记日志并把观察列写空，**不得影响生产决策**；
- 口径语义唯一宿主为`alert.alpha.basis`包，禁止业务类内联口径判断。

### 4.7 相位轨道（C）

```text
StockAlphaPhaseTrack(trackCode, portfolioCode, slotNo, phaseOffset)
isDecisionDay(commonDayCount) = commonDayCount >= WARMUP 且 (commonDayCount - WARMUP) % 5 == phaseOffset
phaseOf(commonDayCount)       = (commonDayCount - WARMUP - phaseOffset) / 5
```

轨道注册表（唯一来源）：

| trackCode | portfolioCode | slotNo | phaseOffset | 启用 |
|---|---|---|---|---|
| `VIP_ALPHA#1` | `VIP_ALPHA` | 1 | 0 | 恒启用 |
| `VIP_ALPHA_SHADOW#1` | `VIP_ALPHA_SHADOW` | 1 | 0 | 影子开关 |
| `VIP_ALPHA_SHADOW#2` | `VIP_ALPHA_SHADOW` | 2 | 2 | 影子开关 |

- 决策归属由`torn_stock_alpha_decision.phase_track_code`显式承载，唯一键为`(phase_track_code, decision_business_date, phase)`；
- 槽位选择唯一宿主为`StockAlphaSlotPolicy`，按`(portfolioCode, slotNo)`精确匹配，禁止"数量恰好为1"式硬编码；
- 5槽**不设为目标形态**；影子收益高低**不得**作为采纳依据（只降波动类改动，见长期业务基线§14.7/§15.7）。

---

## 5. 组合CODE和数据边界

### 5.1 组合定义

```text
VIP_FORMAL       : 旧版正式组合，slot_no=1..5，2B/slot（退场中）
VIP_ALPHA        : α正式组合，slot_no=1，10B（本次交付零改动）
VIP_ALPHA_SHADOW : α影子组合，slot_no=1..2，5B/slot（本次交付，仅影子观察）
```

复用`torn_stock_portfolio_slot`，不新增资金槽表。α正式仓只锁`VIP_ALPHA/slot_no=1`；α影子仓锁`VIP_ALPHA_SHADOW/slot_no=1..2`；旧版只锁`VIP_FORMAL/slot_no=1..5`。影子资金独立，不占用、不挪用正式仓槽位。

### 5.2 必须增加CODE的闭包

只有以下实体确实参与组合隔离时才增加`portfolio_code`或等价来源字段：

```text
TornStockVirtualBatch
资金槽查询/初始化
交易或成交事实（若现有字段无法追溯来源）
通知审计/幂等查询（若现有键不足）
收益和日报查询（若当前查询会混账）
锁、唯一键和活跃批次查询
```

原始行情、通用bar、通用特征和股票池数据不因α存在而机械增加CODE。

历史正式批次按已有账本类型确定性回填`VIP_FORMAL`；无法无歧义识别时迁移失败并禁止α新入场，不猜测。

### 5.3 α最小审计字段

如现有批次字段无法表达，追加：

```text
portfolio_code
alpha_decision_id
alpha_rule_version
stock_universe_version
feature_data_as_of
r20/r1/r20_normalized/r1_normalized/alpha_score/rank_position
execution_bar_start_time
phase_track_code            (决策归属相位轨道,见§4.7)
alt_signal_reference_price  (B观察口径参考价,只写不读)
alt_selected_stocks_id      (B观察口径目标名单,只写不读)
alt_source_snapshot_digest  (B观察口径来源摘要,只写不读)
```

不得把旧`quality_score`改作`alpha_score`。是否真的需要每个字段，必须在代码追踪后确认，禁止按本文列表机械扩表。

Alpha批次的规则身份在决策阶段冻结，由`buy_rule_version=ALPHA_0.04_V1`、`sell_rule_version=ALPHA_REBALANCE_ONLY`、`allocation_rule_version=ALPHA_100_PERCENT`、`message_rule_version=ALPHA_V1`连同`portfolio_code=VIP_ALPHA`、`primary_strategy=ALPHA`、`alpha_decision_id`共同承载；公共入场/成交组装不得覆盖这些字段。当前实现把评分输入保存在`torn_stock_alpha_daily_snapshot`，批次经`alpha_decision_id → 决策(决策业务日/共同有效日序号/选中股票) → 快照(r20/r1/R20/R1/alpha_score/rank_position/stock_universe_version)`读回，不新增重复评分列。

### 5.4 α日线与决策持久化

若现有表无法保存可复核的日线来源和phase决策，新增最小两张表：

```text
torn_stock_alpha_daily_snapshot
(stocks_id, business_date, close_price, source_bar_id, source_bar_start_time,
 stock_universe_version, alpha_rule_version, r20, r1, r20_rank, r1_rank,
 r20_normalized, r1_normalized, alpha_score, rank_position, common_valid)

torn_stock_alpha_decision
(phase_track_code, decision_business_date, common_day_index, phase, decision_type,
 current_batch_id, selected_stocks_id, source_snapshot_digest,
 decision_bar_start_time, execution_bar_start_time, execution_status,
 failure_reason, rebalance_batch_id,
 alt_signal_reference_price, alt_selected_stocks_id, alt_source_snapshot_digest)
唯一键: (phase_track_code, decision_business_date, phase)
```

已结束自然日快照的最早构建时刻由§4.3.1的窗口决定：生产首次有效触发点为次日的α决策窗口起点（08:00桶，08:15:10处理），晚于每日07:00的Tornsy巡检修复。`StockAlphaDailyCloseService`的"最近已结束自然日"语义与23:45首触发注释保持不变，构建时机由`VipStockAlertScheduler`的窗口守卫决定；超管预填入口（`预填股票α日线#日期`）不受窗口约束。

表名、列名和索引以实际现有Schema核对为准；若现有模型已能无损承载，则不新增表。`decision_bar_start_time`在`1.6.1`迁移中追加为可空列，仅在首次落决策时冻结，不参与冲突更新，也不需要历史回填（部署前α决策表为空；如需兼容历史行，回填口径为`execution_bar_start_time - 15分钟`）。

`phase_track_code`与`alt_*`三列在`1.6.5`迁移中追加：`phase_track_code`为`NOT NULL DEFAULT 'VIP_ALPHA#1'`（仅元数据列，历史行由默认值补齐，不改任何业务事实）；B的三列为可空且**只写不读**。

---

## 6. 包规划与文件变更边界

包按职责拆分，禁止一个超大AlphaService。

### 6.1 新增业务包

```text
pn.torn.goldeneye.torn.service.stocks.alert.alpha
├── config
│   └── StockAlphaRuleDefinition.java        (α规则版本、组合身份与35支成员唯一常量来源)
├── market
│   ├── StockAlphaDailyCloseCalculator.java
│   ├── StockAlphaDailyCloseService.java
│   └── StockAlphaReadinessGate.java
├── ranking
│   ├── StockAlphaRankingCalculator.java
│   └── StockAlphaRankingResult.java
├── decision
│   ├── StockAlphaTargetPolicy.java
│   └── StockAlphaDecisionService.java
├── track
│   ├── StockAlphaPhaseTrack.java            (相位轨道值对象:决策日与phase唯一语义)
│   ├── StockAlphaTrackRegistry.java         (轨道注册表:正式1条+影子2条)
│   └── StockAlphaSlotPolicy.java            (槽位选择唯一宿主,替代Entry/Rebalance重复实现)
├── basis
│   ├── StockAlphaPriceBasis.java            (价格口径接口)
│   ├── StockAlphaPreviousCloseBasis.java    (生产口径:已结束自然日23:45收盘)
│   ├── StockAlphaLatestPriceBasis.java      (B观察口径:08:00现价追加一天)
│   └── StockAlphaPriceBasisRegistry.java    (口径注册表)
├── notice
│   ├── StockAlphaNoticeRenderer.java        (α买卖正文唯一文案来源,纯静态)
│   └── StockAlphaNoticeAuditWriter.java     (α通知审计写入唯一宿主;D项从alert.shadow搬迁)
└── execution
    ├── StockAlphaExecutionBarPolicy.java   (决策桶/执行桶/决策窗口时间的唯一算法与判定宿主)
    ├── StockAlphaBatchIdentity.java         (α批次业务身份唯一写入点)
    ├── StockAlphaEntryService.java
    └── StockAlphaRebalanceService.java
```

职责边界：

- `config`：不可变规则、α批次身份版本常量、组合和35支成员映射；不访问数据库。
- `market`：日线收盘、共同有效日和准备度；不创建交易事实。
- `ranking`：纯公式、平均名次和确定性排序；不写库。
- `decision`：phase消费和目标策略，持久化决策桶与执行桶；不直接执行旧版BUY。
- `track`：相位轨道与槽位归属的唯一语义来源；只做纯计算与槽位选择，不写资金、不发送消息。
- `basis`：价格口径的唯一实现来源；只构造收盘序列，不写库、不决定目标。
- `notice`：α买卖正文与α通知审计写入的唯一来源；不承载发送、payload冻结、幂等职责，不构成第二套Alpha消息服务。
- `execution`：统一执行bar、α身份写入、初始入场接线和原子换仓；复用公共资金/批次服务。

### 6.2 持久化文件

按现有分层新增或修改：

```text
repository/model/torn/stocks/portfolio/TornStockAlphaDailySnapshotDO.java
repository/model/torn/stocks/portfolio/TornStockAlphaDecisionDO.java
repository/dao/torn/stocks/portfolio/TornStockAlphaDailySnapshotDAO.java
repository/dao/torn/stocks/portfolio/TornStockAlphaDecisionDAO.java
repository/mapper/torn/stocks/portfolio/TornStockAlphaDailySnapshotMapper.java
repository/mapper/torn/stocks/portfolio/TornStockAlphaDecisionMapper.java
resources/mapper/torn/stocks/portfolio/TornStockAlphaDailySnapshotMapper.xml
resources/mapper/torn/stocks/portfolio/TornStockAlphaDecisionMapper.xml
```

超过单包可读范围时保持`market/ranking/decision/execution`职责拆分，不把DAO放入业务包。

### 6.3 必须核对或最小修改的现有文件

```text
VipStockAlertScheduler.java
StockRoundTransactionService.java
StockAlertRuntimeGate.java
StockPortfolioService.java
StockPortfolioInitService.java
TornStockVirtualBatchDO.java
TornStockVirtualBatchMapper.java
TornStockVirtualBatchMapper.xml
StockVirtualBatchAssembler.java
StockAlphaEntryService.java
StockAlphaRebalanceService.java
StockBatchPathService.java
StockBatchExitService.java
StockEntrySettlementService.java
StockShadowRecordWriter.java（D项搬迁α审计职责后删除）
StockNoticeComposeService.java
StockNoticeSendService.java
TornStockNoticeAuditDO.java
TornStockAlphaDecisionDO.java
TornStockAlphaDecisionMapper.xml
StockDailySummaryQueryService.java
StockDailySummaryRenderer.java
```

修改原则：

- 调度器只增加α阶段接线，不复制调度器。
- 公共资金服务按组合定义读取槽位，不新增α资金Service。
- 批次查询、锁和唯一键显式带组合CODE。
- 公共退出服务按组合/规则来源分流，α跳过旧版固定SELL。
- 公共入场/成交组装器对Alpha批次只补充实际成交字段，保留已冻结的Alpha规则身份；旧版批次继续写旧版默认版本。
- Alpha文案由`StockAlphaNoticeRenderer`按已审核的新版Alpha模板渲染，公共组合器只做身份分流，不调用旧版三类BUY解析器，也不新增第二套消息服务；Alpha模板允许且必须与旧版模板明确区分。
- 通知复用现有审计和发送链，只增加α必要文案与关联字段。
- 日报按CODE查询，避免把α渲染为旧版五槽。

### 6.4 明确不修改

除非编译接线或公共查询闭包确实要求，不修改：

```text
BuyStrategyMatcher.java
StockBuySignalEvaluator.java
DeepMeanReversionBuyStrategy.java
RangeLowerBuyStrategy.java
StrictReboundConfirmBuyStrategy.java
```

不删除旧策略，不把α实现为旧策略接口，不顺手重构旧版。

---

## 7. Schema、迁移和配置

### 7.1 Liquibase

若代码追踪确认需要Schema变化，新增版本目录和master include；不修改已执行changeSet。迁移顺序：

1. 追加必要批次/审计字段；
2. 确定性回填历史组合CODE；
3. 添加实际查询需要的复合索引/约束；
4. 创建α快照/决策表（仅在现有表不足时）；
5. `1.6.1`追加`torn_stock_alpha_decision.decision_bar_start_time`（可空、不回填、不参与冲突更新）；
6. 幂等插入`VIP_ALPHA`单槽；
7. `1.6.5`追加`torn_stock_alpha_decision`的B观察列（可空、只写不读）；
8. `1.6.5`追加`phase_track_code`（`NOT NULL DEFAULT 'VIP_ALPHA#1'`，仅元数据列）并把唯一键改为`(phase_track_code, decision_business_date, phase)`；
9. `VIP_ALPHA_SHADOW`（2槽×5B）由`StockPortfolioInitService`幂等初始化，不产生BUY/SELL；
10. 在空库和已有历史批次库分别验证。

每个表和字段必须有remarks，字符串/金额按项目YAML规范加引号。迁移不得产生BUY、SELL、持仓、成交或通知。

### 7.2 配置

复用现有VIP开关；不新增Alpha总开关平台。必须区分：

- α新入场；
- α已有批次管理；
- 通知发送；
- 日报。

新增`VIP_STOCK_ALPHA_SHADOW_ENABLED`（默认false）作为α影子组合的唯一开关，独立于`VIP_STOCK_RULE_MODE`与`VIP_STOCK_NEW_ENTRY_ENABLED`——影子不得借用FORMAL-only的正式新入场许可。

关闭α新入场不停止已有α批次；关闭影子不停止正式仓；关闭α不自动恢复旧版新入场；回退需人工批准。

---

## 8. 事务、幂等和失败边界

- 快照按股票、业务日、规则版本和股票池版本幂等UPSERT。
- 同一业务日、版本和phase最多一条有效决策。
- 重复调度先读取已有决策，不重复消费phase。
- α/旧版查询、锁和内存Map不能只以`stocksId`作为跨策略唯一键。
- 通知在交易事务提交后发送；发送入口采用数据库级领取语义：只有`PENDING`/`FAILED_RETRYABLE`且未达3次总尝试上限的通知可被原子领取为`SENDING`（领取即累计一次尝试并写入`claim_token`/`claim_time`），冻结与终态回写必须绑定同一领取标识，未领取成功者不得调用Bot，旧领取者不能覆盖新状态。
- 普通通知继续按单通知领取、冻结和回写；Alpha换仓通知必须以`rebalanceAssociationId`对应的恰好两腿为不可拆分组，组领取、组释放、租约恢复、成功/失败回写和异常收敛都必须保持完整组边界。
- Alpha换仓组的领取释放和租约恢复必须同步写两腿的`send_status`与`rebalance_group_status`：未达上限统一为`FAILED_RETRYABLE`，达到上限统一为`FAILED_FINAL`；尝试次数不归零，`update_time`使用同一`businessNow`。两腿状态、组状态或尝试次数无法证明一致时不得猜测重试结果。
- Alpha换仓组级正常成功/失败回写必须同时满足完整两腿、相同`claim_token`、预期状态和相同尝试次数，实际更新行数必须等于2；返回0/1或抛出异常不得宣称完整送达或完整失败。Bot成功但回写结果未知时禁止再次调用Bot。
- Alpha换仓异常收敛必须区分当前流程持有者、其他流程持有者和无持有者：没有当前`claim_token`所有权证明时不得覆盖任何其他流程持有的`SENDING`行；竞争流程只停止本次动作并保留人工核验，不得按关联ID无条件收敛。已确认完整`SENT`组不得被异常收敛覆盖。
- 通知生命周期为`PENDING → SENDING → SENT`或`SENDING → FAILED_RETRYABLE → SENDING → SENT/FAILED_FINAL`：总尝试次数固定3次（首次1次、自动重发2次），达到上限进入`FAILED_FINAL`不再自动发送；失败与状态未知（回写异常、更新行数不足、领取租约超时）不得只写日志，必须按完整换仓组重新读取并持久化收敛为`FAILED_RETRYABLE`、`FAILED_FINAL`或`INCONSISTENT`。自动重发直接复用首次冻结的`messageText`/`payloadSnapshot`/`payloadHash`，重复消息可依据批次标识识别。
- 通知失败不回滚已提交交易；换仓合并消息以`rebalanceAssociationId`为单位领取、冻结和回写，完整两腿必须在同一组级条件下进入同一状态，已`SENT`腿不得重复发送，不得将单腿成功解释为完整换仓通知成功；缺腿、重复腿、字段冲突、部分完成或状态不可解释时fail-closed并持久化人工核验状态。
- 通知领取、租约恢复、payload冻结补齐和组级终态回写使用同一发送流程的`StockMarketClock`业务时间；Mapper关键时间通过显式参数传入，禁止通知Java链直接调用`LocalDateTime.now()`，也不得未说明地混用数据库`CURRENT_TIMESTAMP`。
- 预填任务只能写快照/排名数据，禁止调用批次、资金、结算和通知writer。
- `VIP_ALPHA`换仓失败必须整体回滚；旧版异常不能改写α，α异常不能吞掉旧版存量SELL。

---

## 9. 收敛后的测试策略

本次为L3，但只测试生产可达的关键风险。

### 9.1 一套纯领域完整矩阵

```text
StockAlphaRankingCalculatorTest
```

覆盖公式、平均名次、精度、同分、35支完整性和确定性排序。

```text
StockAlphaDailyCloseCalculatorTest
StockAlphaTargetPolicyTest
StockAlphaExecutionBarPolicyTest
```

分别覆盖日边界/缺失、phase与Top3、唯一执行桶和过期边界。每条算法只在一个测试类完整覆盖，不在Service和集成测试重复。

### 9.2 少量编排测试

修改现有Scheduler、RoundTransaction、Portfolio、Exit和Notice测试，最多验证：

- α成为唯一正式新入场；
- 旧版存量仍收尾；
- α不走旧版SELL；
- 换仓任一侧失败无单侧事实；
- 通知失败不回滚交易；
- 关闭α新入场仍管理已有α批次；
- α通知文案可识别（α=0.04主策略、20日反转主因子、1日反弹4%、Top1），不展示旧版质量分、旧版策略依据与五槽语义；
- α批次`ENTRY_PENDING → OPEN`后保留Alpha规则身份，旧版批次仍写旧版默认版本；
- 换仓新仓来源bar为决策桶、执行bar为唯一执行桶，两者可区分。

### 9.3 必要真实PostgreSQL测试

仅保留Mock无法证明的：

- CODE查询隔离和同股并持；
- `VIP_ALPHA`槽幂等初始化；
- 快照/决策唯一键和重复调度幂等；
- 换仓事务回滚及资金/批次读回；
- 换仓事务回滚只需一个代表性集成测试方法，该方法必须显式使用`@Transactional`和`@Rollback`，不要求为同一业务方法机械扩展多个成功/失败测试方法；
- 涉及资金、槽位、批次、决策或通知审计数据的测试方法必须显式使用`@Transactional`和`@Rollback`，业务写入和读回优先使用真实DAO，不得在Java测试文件中堆积业务SQL；
- α批次成交后规则身份与`decision_bar_start_time`的真实数据库读回；
- 通知唯一约束。

复用项目共享库测试方式，不新建隔离profile/独立库；跨线程数据使用`@AfterEach`精确物理DELETE，禁止`setval`、手工ID和`@Disabled`掩盖失败。

### 9.4 不做

- 不为每个Review finding创建测试类；
- 不测试getter/setter、私有方法和框架行为；
- 不做复杂Shadow全量矩阵；
- 不做第二套平台的端到端测试；
- 不用读取XML/YAML字符串断言代替真实SQL。
### 9.5 早间决策窗口（一次性方案）的收敛要求

窗口判定只允许一个主证据与两处接线证据，禁止重复矩阵：

```text
StockAlphaExecutionBarPolicyTest     // 窗口边界（07:45关 / 08:00开 / null关）唯一完整覆盖
StockAlphaDecisionServiceTest        // 守卫位置语义：关窗不建决策，但已持久化决策仍被复用
VipStockAlertSchedulerTest           // 单行接线：窗口外桶不构建α日线快照
```

不新增调度器/cron测试（禁止用注解断言代替行为验证）；不为`StockRoundTransactionService`新增窗口用例（其决策服务为mock）；不新增参数化多时段矩阵、回测用例与集成测试；现有α测试夹具（09:45/10:00）已落在窗口内，不得改写。

### 9.6 收敛交付B/C/D的收敛要求

```text
StockAlphaPhaseTrackTest   // 偏移0/2的决策日与phase,以及同一天只有一个轨道决策
StockAlphaPriceBasisTest   // LATEST_PRICE追加一天后窗口平移;PREVIOUS_CLOSE序列不受影响
StockAlphaSlotPolicyTest   // 按(portfolioCode, slotNo)精确匹配,数量不为1不再误判
```

不新增C的多槽端到端矩阵、不为D的删除写字符串断言测试（用编译+全量测试证明无残留）、不为影子通知新增发送链测试；B的观察口径测试只覆盖序列构造，不覆盖通知与结算。

---

## 10. 开发、发布和回退顺序

1. 代码追踪并冻结实际受影响表和文件。
2. 追加兼容Schema（如确实需要），保持α新入场关闭。
3. 实现规则对象、日线、排名、phase和统一执行bar。
4. 接入现有批次/槽位/结算/通知公共能力。
5. 通过聚焦测试、真实Mapper/事务测试和迁移验证。
6. 完成35支成员校验和历史预填，证明交易事实delta为0。
7. `TECHNICALLY_READY`后人工批准打开α新入场。
8. 首条真实BUY发送并完成业务确认后，按批准开启日报。
9. 真实目标变化形成配对`ALPHA_REBALANCE` SELL并完成业务确认。
10. 早间决策窗口一次性方案：窗口常量与两处守卫（决策服务新建决策、调度器日线构建）与两个cron（Tornsy 07:00、股票日报 08:10）**同批发布**；发版建议避开每日08:00–09:35（α窗口执行与`entry_stale_at`敏感区）。窗口前的在途PENDING决策仍按原执行桶消费。
11. 首个相位日采集一次性方案§8.2的只读证据（决策桶/执行桶/成交价/跟随窗口/播报时刻/日线构建时刻与bar一致性）并留档。
12. A/B/C/D同批发布；D按「先文档→再清仓→再停开关→最后删码」分步放行；`VIP_SHADOW_CANDIDATE`与`UNLIMITED_SHADOW`直接清仓，`VIP_FORMAL`按原规则自然收尾；孤儿通知行及其生产者测试类已于交付前清理完毕（非开发任务，见统一方案§3.2）。
13. D的组件删除必须在α通知审计写入器从`alert.shadow`搬迁完成后进行。
14. 发版后打开`VIP_STOCK_DAILY_SUMMARY_ENABLED`，再采集A的行为级证据。
15. 影子期≥1个月且≥2个完整换仓周期后采集C的证据；正式仓全程零改动。
16. 验收通过后删除统一方案文件，结论并入§13。

回退：关闭α新入场→保留存量管理和通知重试→核查α批次→另行人工批准旧版新入场；不得自动回退或改写已有α批次。

---

## 11. 明确不做事项

- 第二套资金表、批次平台、消息平台或完整调度器；
- 复杂Shadow/Provisional/Formal运行平台；
- 动态SELL、止盈、止损、固定持有期和第二批推荐；
- 新研究框架、年度结算和无入口的通用投资引擎；
- 为证明隔离而复制旧版全部策略和服务；
- 跨断层成交、缺失数据补值、历史交易伪造；
- 分布式锁、Outbox或多实例基础设施；当前单实例按JVM防重入；
- 与α生产入口无关的旧版重构和测试扩张；
- 在正式仓实施多槽相位分散，或把5槽设为目标形态；
- 以影子期收益高低决定只降波动类改动的采纳；
- 让影子通知进入可发送集合或向成员投递；
- 复制第二份排名实现、第二套相位计算或第二套槽位选择；
- 修改已执行的`1.6.1` changeSet。

---

## 13. 已完成实现Review记录

### 13.1 第六批业务Review与一次性整改方案（2026-09-13）

- Review依据：`.ai/knowledge/stocks/vip_stock_alert_business_review_conclusion_one_time.md`
- 当前技术实施依据：`.ai/knowledge/stocks/vip_stock_alert_alpha_review_remediation_technical_plan_one_time.md`
- 第六批Review结论：Alpha核心业务基本实现；`R-ALPHA-PRE-002`仅FORMAL允许`VIP_ALPHA`正式新入场、`R-ALPHA-PRE-003`通知链统一业务时钟均为代码层基本通过项，仅保留直接回归验证；当前唯一开放P1为`R-ALPHA-PRE-001`。
- `R-ALPHA-PRE-001`包含两个直接生产路径：①组级领取释放/租约恢复必须同步两腿`send_status`与`rebalance_group_status`；②异常收敛必须绑定当前领取者所有权，不得覆盖其他流程持有的`SENDING`组。
- 当前实现事实：组级领取、成功/失败回写、payload冻结、单次Bot调用和统一`businessNow`已经存在；但释放/租约恢复仍可能只改腿状态，异常收敛仍存在仅按关联ID覆盖组的路径，因此第六批整改尚未完成。
- 本轮实施范围：仅修改一次性方案列明的通知`notice`/`notice.rebalance`、通知DAO/Mapper及必要测试；不修改Alpha算法、资金、批次、成交事务或Liquibase。既有`rebalance_group_status`和`rebalance_group_error`继续复用，不新增Schema迁移。
- 关闭条件：组级释放/租约恢复、领取者安全收敛的生产调用链、聚焦测试和真实PostgreSQL读回证据全部通过后，由AI复审；复审完成前不得打开Alpha正式新入场。

### 13.2 历史第三批/第五批/第六轮记录（仅作历史证据）

- 早期第三批、第五批和第六轮记录保留为历史审查上下文，不代表第六批当前状态；当前状态以本节和第六批一次性整改方案为准。
- 早期测试计数、提交范围和“P1=0”结论不作为第六批整改完成证据。
### 13.3 早间决策窗口方案（2026-09-18）

- 一次性方案与验收标准：`.ai/knowledge/stocks/vip_stock_alert_alpha_convergence_technical_plan_one_time.md`（原早间决策窗口方案已并入该统一方案）
- 背景：首条α消息（批次`A20260917-9`/CNC）在00:31播报，跟随窗口00:15–01:15，成员无法跟买；根因是α决策锚定在自然日切换后的第一、二根15分钟桶。
- 口径：α决策窗口起点常量08:00（08:00桶08:15:10落决策 → 08:15桶08:30:10成交 → 播报≈08:30）；Tornsy巡检08:45→07:00；股票日报08:30→08:10；先后顺序为巡检→日报→α。
- 兼容：历史决策/批次/通知/快照零改写；窗口只约束新建决策与已结束自然日快照构建；在途PENDING决策仍按原执行桶消费；phase序列不回填；回放与预填入口不受影响。
- 收益依据：早间07:00–13:00为平台（单笔0.51%–0.55%，桶间差异远小于±0.097pp标准误）；08:15执行桶单笔0.542%/单利年化均值16.2%，现状00:15为0.617%/19.7%；不存在比00:15更高的早间点，故以"可跟买"换取约0.06pp/笔。
- 状态：A已并入统一方案，与B/C/D同批待实施；完成统一方案§12验收并留档后关闭。

### 13.4 收敛交付A/B/C/D统一方案（2026-09-18）

- 统一方案：`.ai/knowledge/stocks/vip_stock_alert_alpha_convergence_technical_plan_one_time.md`；业务验收：`.ai/knowledge/stocks/vip_stock_alert_alpha_convergence_acceptance_one_time.md`。
- A：α决策窗口08:00、Tornsy 07:00、日报08:10；窗口判定唯一宿主与两处调用点。
- B：决策时同时落`PREVIOUS_CLOSE`与`LATEST_PRICE`两套口径，观察列只写不读。
- C：仅在影子组合`VIP_ALPHA_SHADOW`（2槽×5B、偏移0/2）观察；正式仓零改动；相位语义唯一宿主。
- D：候选影子、无限资金影子、拒绝观察、旧版信号链与回放链停用并删除；弃用规格入库`vip_stock_strategy_version_history.md`。
- 状态：待实施。完成统一方案§12验收并留档后关闭。

---

## 14. 技术停止条件

- `SHADOW`/`PROVISIONAL`不得创建`VIP_ALPHA`正式批次；只有`FORMAL`在readiness和新入场开关通过后允许Alpha正式新入场；`OFF`和关闭新入场只阻止新入场，不停止已有Alpha批次管理，也不自动恢复旧版新入场。
- 换仓消息必须以完整两腿和`rebalanceAssociationId`为组处理；成功/失败回写实际更新行数必须等于2，部分回写、回写异常或一腿已终态等不可解释状态必须持久化收敛，不得只记录日志或单腿重发。
- 通知租约、冻结和回写使用统一`StockMarketClock`业务时间；关键Mapper时间显式传入，禁止通知链直接调用`LocalDateTime.now()`或未说明地混用`CURRENT_TIMESTAMP`。
- 公式、35支股票、日线、phase、执行bar和Top3通过；
- 原子换仓失败无单侧事实；
- α不触发旧版固定SELL；
- Alpha消息使用已审核新版模板，可识别（α=0.04主策略、20日反转主因子、1日反弹4%、Top1），不进入旧版三类BUY解析器、不展示旧版质量分与五槽语义，且Alpha消息组合不再抛异常、不会阻塞既有PENDING通知链；
- Alpha换仓SELL/BUY两腿共享可读回的换仓决策ID、固定格式换仓关联ID、原仓批次、新仓批次和腿类型；
- α批次从`ENTRY_PENDING`成交为`OPEN`后仍保留Alpha规则身份，公共成交组装未覆盖为旧版默认值；
- 决策事实与执行事实可区分（决策桶、来源bar、执行bar及两侧执行价格）；
- `1.6.1`迁移已执行且`decision_bar_start_time`可真实读回；
- 预填无交易副作用；
- 通知审计、单次发送状态和失败不回滚交易语义可追溯；
- 聚焦测试、必要真实Mapper/事务测试和迁移验证通过；
- α新决策与已结束自然日快照只允许在`DECISION_WINDOW_START`（08:00）起的已结束桶产生；窗口判定仅有`StockAlphaExecutionBarPolicy`一个宿主，且仅被`StockAlphaDecisionService`（新建决策）与`VipStockAlertScheduler`（日线构建）两处调用；
- 窗口为"不早于"语义：切换发版瞬间的在途PENDING决策仍按原执行桶消费，不饿死、不重复消费同一phase；
- Tornsy每日巡检为07:00且仍覆盖昨天完整自然日；股票日报为08:10；
- 历史决策、批次、通知审计与日线快照的**业务事实**零改写；`1.6.5`仅新增B观察列与`phase_track_code`元数据列，新增唯一配置项仅`VIP_STOCK_ALPHA_SHADOW_ENABLED`；
- 相位语义唯一宿主为`StockAlphaPhaseTrack`与轨道注册表；槽位选择唯一宿主为`StockAlphaSlotPolicy`；价格口径唯一宿主为`alert.alpha.basis`包；
- 影子通知状态为`SHADOW_RECORDED`且不在任何可发送查询取值集合内；同刻多槽换仓只生成一条合并审计记录；
- 正式仓`VIP_ALPHA`在交付期间账本、持仓、批次、通知零改动；
- 旧策略连续7天信号、批次、通知产出为0；被删组件无残留引用且全量测试通过；
- 换仓通知审计写入不得依赖`alert.shadow`包（搬迁完成后该包才允许删除）；
- 无未解决P0/P1。

技术完成不等于真实BUY/SELL或业务验收完成；真实业务验收按一次性技术方案和业务验收文档单独确认。
