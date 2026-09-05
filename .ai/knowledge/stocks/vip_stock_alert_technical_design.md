# VIP股票 α=0.04 长期技术设计基线

## 1. 文档定位

- 文档类型：长期技术架构与实现边界
- 业务范围：α=0.04 首批股票提醒
- 长期业务基线：`.ai/knowledge/stocks/vip_stock_virtual_portfolio_strategy.md`
- 一次性开发与验收契约：`.ai/knowledge/stocks/vip_stock_alert_technical_implementation_one_time.md`
- 业务验收依据：`.ai/knowledge/stocks/vip_stock_alert_business_acceptance_one_time.md`
- 时区：`Asia/Shanghai`
- 状态：技术方案已按Review收敛，允许进入开发设计；本文件不等同于代码已完成

本文坚持最小改动：α是现有股票提醒系统中的新入场决策分支，不建设第二套股票平台。所有新增Java、Schema和测试必须能映射到本文的生产入口和验收证据；无法映射的扩展不得纳入本次开发。

---

## 2. 长期冻结的技术原则

1. α是当前唯一的新正式入场来源；旧版三类BUY、qualityScore、旧版五槽竞争不再参与新α入场。
2. 旧版已有批次继续按创建时规则收尾，不改写为α，不因α切换而删除或重新解释。
3. 复用现有调度、15分钟bar、批次、槽位、资金结算、通知和审计能力。
4. 只在公共模型无法表达业务边界时增加字段、查询条件或分支。
5. `VIP_ALPHA`为独立10B逻辑资金槽，`slot_no=1`；`VIP_FORMAL`继续使用既有5个2B槽位。
6. α与旧版允许同时持有同一股票，但资金、批次、SELL配对、规则版本和收益统计必须按组合CODE隔离。
7. 日线、排名、phase、执行bar和换仓语义只实现一份，使用不可变规则对象和纯领域计算器复用。
8. 不新增动态SELL、复杂Shadow运行轨道、第二批炒股推荐或研究平台。

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

只允许使用该精确桶、已结束、可用且价格合法的bar；不跨断层、不使用后续bar。初始BUY和换仓共用该策略，执行桶写入决策/批次，重启时只恢复同一桶。

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

---

## 5. 组合CODE和数据边界

### 5.1 组合定义

```text
VIP_FORMAL: 旧版正式组合，slot_no=1..5，2B/slot
VIP_ALPHA : α正式组合，slot_no=1，10B
```

复用`torn_stock_portfolio_slot`，不新增资金槽表。α只锁`VIP_ALPHA/slot_no=1`；旧版只锁`VIP_FORMAL/slot_no=1..5`。

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
```

不得把旧`quality_score`改作`alpha_score`。是否真的需要每个字段，必须在代码追踪后确认，禁止按本文列表机械扩表。

### 5.4 α日线与决策持久化

若现有表无法保存可复核的日线来源和phase决策，新增最小两张表：

```text
torn_stock_alpha_daily_snapshot
(stocks_id, business_date, close_price, source_bar_id, source_bar_start_time,
 stock_universe_version, alpha_rule_version, r20, r1, r20_rank, r1_rank,
 r20_normalized, r1_normalized, alpha_score, rank_position, common_valid)

torn_stock_alpha_decision
(decision_business_date, common_day_index, phase, decision_type,
 current_batch_id, selected_stocks_id, source_snapshot_digest,
 execution_bar_start_time, execution_status, failure_reason, rebalance_batch_id)
```

表名、列名和索引以实际现有Schema核对为准；若现有模型已能无损承载，则不新增表。

---

## 6. 包规划与文件变更边界

包按职责拆分，禁止一个超大AlphaService。

### 6.1 新增业务包

```text
pn.torn.goldeneye.torn.service.stocks.alert.alpha
├── config
│   ├── StockAlphaRuleDefinition.java
│   └── StockAlphaPortfolioDefinition.java
├── market
│   ├── StockAlphaDailyCloseCalculator.java
│   ├── StockAlphaDailyCloseService.java
│   └── StockAlphaReadinessGate.java
├── ranking
│   ├── StockAlphaRankingCalculator.java
│   └── StockAlphaRankingResult.java
├── decision
│   ├── StockAlphaTargetPolicy.java
│   ├── StockAlphaDecisionService.java
│   └── StockAlphaDecisionResult.java
└── execution
    ├── StockAlphaExecutionBarPolicy.java
    ├── StockAlphaExecutionValidator.java
    └── StockAlphaRebalanceService.java
```

职责边界：

- `config`：不可变规则、组合和35支成员映射；不访问数据库。
- `market`：日线收盘、共同有效日和准备度；不创建交易事实。
- `ranking`：纯公式、平均名次和确定性排序；不写库。
- `decision`：phase消费和目标策略；不直接执行旧版BUY。
- `execution`：统一执行bar、初始入场接线和原子换仓；复用公共资金/批次服务。

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
StockBatchPathService.java
StockBatchExitService.java
StockEntrySettlementService.java
StockNoticeComposeService.java
StockNoticeSendService.java
TornStockNoticeAuditDO.java
StockDailySummaryQueryService.java
StockDailySummaryRenderer.java
```

修改原则：

- 调度器只增加α阶段接线，不复制调度器。
- 公共资金服务按组合定义读取槽位，不新增α资金Service。
- 批次查询、锁和唯一键显式带组合CODE。
- 公共退出服务按组合/规则来源分流，α跳过旧版固定SELL。
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
5. 幂等插入`VIP_ALPHA`单槽；
6. 在空库和已有历史批次库分别验证。

每个表和字段必须有remarks，字符串/金额按项目YAML规范加引号。迁移不得产生BUY、SELL、持仓、成交或通知。

### 7.2 配置

复用现有VIP开关；不新增Alpha总开关平台。必须区分：

- α新入场；
- α已有批次管理；
- 通知发送；
- 日报。

关闭α新入场不停止已有α批次；关闭α不自动恢复旧版新入场；回退需人工批准。

---

## 8. 事务、幂等和失败边界

- 快照按股票、业务日、规则版本和股票池版本幂等UPSERT。
- 同一业务日、版本和phase最多一条有效决策。
- 重复调度先读取已有决策，不重复消费phase。
- α/旧版查询、锁和内存Map不能只以`stocksId`作为跨策略唯一键。
- 通知在交易事务提交后发送；失败进入现有PENDING/FAILED重试，不回滚交易。
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
- 关闭α新入场仍管理已有α批次。

### 9.3 必要真实PostgreSQL测试

仅保留Mock无法证明的：

- CODE查询隔离和同股并持；
- `VIP_ALPHA`槽幂等初始化；
- 快照/决策唯一键和重复调度幂等；
- 换仓事务回滚及资金/批次读回；
- 通知唯一约束。

复用项目共享库测试方式，不新建隔离profile/独立库；跨线程数据使用`@AfterEach`精确物理DELETE，禁止`setval`、手工ID和`@Disabled`掩盖失败。

### 9.4 不做

- 不为每个Review finding创建测试类；
- 不测试getter/setter、私有方法和框架行为；
- 不做复杂Shadow全量矩阵；
- 不做第二套平台的端到端测试；
- 不用读取XML/YAML字符串断言代替真实SQL。

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
- 与α生产入口无关的旧版重构和测试扩张。

---

## 12. 技术停止条件

- α唯一新入场调用链可证明；
- `VIP_ALPHA`与`VIP_FORMAL`资金、批次、SELL来源隔离；
- 公式、35支股票、日线、phase、执行bar和Top3通过；
- 原子换仓失败无单侧事实；
- α不触发旧版固定SELL；
- 预填无交易副作用；
- 通知审计和重试可追溯；
- 聚焦测试、必要真实Mapper/事务测试和迁移验证通过；
- 无未解决P0/P1。

技术完成不等于真实BUY/SELL或业务验收完成；真实业务验收按一次性技术方案和业务验收文档单独确认。
