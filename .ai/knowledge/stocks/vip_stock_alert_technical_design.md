# VIP群股票系统虚拟组合与消息提醒技术方案

## 1. 文档信息

- 文档类型：技术设计与实施依据
- 适用项目：Golden-Eye
- 适用版本：1.2.13及以上
- 适用功能：VIP群股票买入/卖出提醒、系统虚拟组合、影子研究、每日组合摘要
- 业务依据：`.ai/knowledge/stocks/vip_stock_virtual_portfolio_strategy.md`
- 设计状态：长期生产技术基线；未闭环实现差异由一次性验收清单维护
- 当前实现Review基线：`a972f164762b386f44cd437453ec7740321a8cd3`（2026-08-08）
- 技术验收状态：业务Review不通过。核心历史修复仅作为已实现基线；持续轮次生产、月度冷启动重算/确认、双Shadow账本、日报动态SELL研究展示、回放manifest完整性和ENTRY等值边界均未实现或未按冻结口径闭环。本文第22节是当前实现差异的永久技术契约；四个运行开关均保持`false`，规则模式保持`SHADOW`
- 时区：`Asia/Shanghai`
- 维护人：Bai
- 最后修订日期：2026-08-08

本文定义VIP群股票提醒功能的技术架构、数据库结构、状态机、调度流程、消息格式、实施边界和验收标准。策略业务语义以股票知识库为准；本文由AI技术专家负责将业务规则完整映射为可实施、可审计、低侵入的Java/Spring/PostgreSQL方案，并作为普通工程师开发、测试和验收的唯一技术基线。工程师不得在本文未定义或互相冲突时自行猜测，应停止实施并反馈技术专家修订。

最终技术实施方案仅由AI技术专家维护。开发人员只按方案修改代码、Schema和测试；代码Review通过后，由AI技术专家根据已验证实现更新本文的长期技术契约和验收门禁。本文是永久技术基线；`vip_stock_alert_business_acceptance_open_items.md`和`vip_stock_alert_remediation_implementation_plan.md`均为一次性修复/验收文档，可在全部开放项闭环后删除，但删除不得导致本文、业务主方案、月度规则或机器摘要失去发布标准。

> 本文冻结的实施资金口径为5槽、每槽初始20亿、总初始资金100亿。该口径是用户在技术方案审核阶段对原研究口径“每槽4亿”的明确覆盖。历史研究收益基于每槽4亿，正式实施前必须按每槽20亿重新执行整数股数、余款现金和逐bar净值回放。

---

## 2. 目标与边界

### 2.1 建设目标

建立一个不绑定群成员个人持仓的公开系统虚拟组合：

```text
系统识别买入机会
→ 使用紧邻下一连续15分钟桶的最后实际价格建立系统参考仓
→ 向现有VIP赚钱群发布带批次号的中文买入提醒
→ 按系统参考成本、持仓路径和规则版本独立跟踪
→ 满足关闭条件后生成退出信号
→ 使用紧邻下一连续15分钟桶的最后实际价格关闭参考仓
→ 向VIP群发布与原买入批次严格配对的中文卖出提醒
```

### 2.2 产品边界

- 使用现有`project.vipGroupId`发送正式股票消息，不增加股票专用群配置。
- 系统不记录成员是否跟随，不计算成员个人盈亏。
- 卖出提醒只关闭对应的系统公开批次，未跟随原买入的成员无需操作。
- 正式组合最多5个槽位，同一股票最多一个正式活跃批次。
- 影子信号、影子批次和拒绝观察只写数据库，不实时发群消息。
- 每天08:30向`vipGroupId`发送一条组合摘要，摘要同时包含正式组合和影子研究汇总。
- 原有`g#炒股推荐`保留为旧版即时市场建议，不切换为系统组合查询。
- 本期不建设NapCat高可用、ACK对账、自动重试、消息查询或网络层精确一次机制。
- 动态卖出、高风险硬否决和换仓不进入首期正式主链，继续作为影子研究。

### 2.3 非目标

- 不修改Torn股票分钟采集API和既有大额交易提醒语义。
- 不将现有`VipNoticeManager`、`VipNoticeChecker`或`VipNoticeStateDO`复用于公开股票组合。
- 不为股票提醒遍历VIP订阅用户逐个发送消息。
- 不承诺群成员能按系统参考价格成交。
- 不把分钟报价聚合结果描述为官方OHLC、真实成交量或盘口数据。
- 不在本期顺带重构VIP订阅、加群审批、踢群和个人提醒可靠性。
- 不直接使用现有无状态`StockTradeStrategyService`管理正式批次生命周期。

---

## 3. 现状审计与复用边界

### 3.1 现有股票数据链

```text
TornStocksManager.spiderStockData()
→ Torn API /torn?selections=stocks
→ 更新 torn_stocks
→ 插入 torn_stocks_history
→ 发送股票大额交易消息
→ 异步构建现有分钟采样特征
```

主要文件：

- `src/main/java/pn/torn/goldeneye/torn/manager/torn/stocks/TornStocksManager.java`
- `src/main/java/pn/torn/goldeneye/torn/manager/torn/stocks/StockFeatureBuildService.java`
- `src/main/java/pn/torn/goldeneye/torn/manager/torn/stocks/StockRollingFeatureEngine.java`
- `src/main/java/pn/torn/goldeneye/torn/model/torn/stocks/trade/StockRollingState.java`
- `src/main/resources/mapper/torn/stocks/TornStocksHistoryMapper.xml`
- `src/main/resources/mapper/torn/stocks/TornStockStrategyFeatureMapper.xml`

可直接复用：

- `torn_stocks_history`分钟原始事实；
- 股票ID、简称和分钟价格；
- MyBatis Plus DAO/Mapper模式；
- PostgreSQL现有股票+时间索引；
- `GroupMsgHttpBuilder`、`TextQqMsg`、`ImageQqMsg`和`Bot.sendRequest()`；
- `ProjectProperty.getVipGroupId()`；
- 项目现有单实例调度和JVM防重入模式。

### 3.2 现有特征不能直接作为正式组合输入

现有`StockRollingFeatureEngine`把每分钟采样点直接放入滚动窗口，当前RSI也是最近60个采样点。它不等同于知识库研究使用的标准15分钟bar特征，并且当前缺少：

- 15分钟桶数据质量；
- 连续桶判定；
- `return6h`；
- 与指定`bar_time`严格对应的冻结特征；
- 下一连续bar参考成交；
- 特征陈旧门禁。

因此正式组合必须采用独立数据链：

```text
分钟原始事实
→ 标准15分钟市场bar
→ 标准15分钟策略特征
→ 系统虚拟组合
```

现有`torn_stock_strategy_feature`继续服务旧版私聊建议，不与正式组合的新特征混用。

### 3.3 现有策略服务不能直接复用

`StockTradeStrategyService`当前同时计算买入、卖出和持有信号，并通过统一`max(score)`选择结果；它不具备参考成本、批次、槽位、持仓路径、冷却、复位和买卖配对语义。

正式组合采用：

```text
无活跃批次 → 只评估该股票的买入策略
有活跃批次 → 只评估该批次的持有/退出规则
```

旧服务保留，不在本期修改其外部行为。

---

## 4. 总体架构

```text
TornStocksManager 每分钟采集
        ↓
torn_stocks_history
        ↓
Stock15mBarBuildService
        ↓
torn_stock_market_bar_15m
        ↓
Stock15mFeatureBuildService
        ↓
torn_stock_strategy_feature_15m
        ↓
VipStockAlertScheduler
        ↓
StockMarketRoundLoader（事务外批量快照）
        ↓
买入策略 + 资格策略 + 候选排序 + 持仓退出
        ↓
StockRoundTransactionService（短事务）
        ↓
轮次 / 信号 / 批次 / 槽位 / 路径 / 通知审计
        ↓ 事务提交后
StockNoticeSendService
        ↓
GroupMsgHttpBuilder → Bot.sendRequest() → NapCat
```

### 4.1 职责划分

| 组件 | 职责 |
|---|---|
| `Stock15mBarBuildService` | 从分钟历史按时区构建15分钟bar、数据质量和连续性事实 |
| `Stock15mFeatureBuildService` | 基于15分钟bar构建因果特征，不读取未来bar |
| `VipStockAlertScheduler` | 每分钟检查已结束但尚未完成的轮次，按时间顺序补偿，JVM内防重入 |
| `StockMarketRoundLoader` | 事务外批量读取本轮bar、特征、月度状态、信号状态和开放批次 |
| `StockBuySignalService` | 计算三个正式买入策略、质量分和false→true边沿 |
| `StockEligibilityService` | 处理风格、成熟度、风险、趋势保护、冷却、复位、同股和数据门禁 |
| `StockBatchExitService` | 对正式开放批次计算目标、风险、区间和时间退出；同步记录动态卖出影子结果 |
| `StockCandidateRankingPolicy` | 按`qualityScore DESC → stocksId ASC`确定候选顺序 |
| `StockPortfolioService` | 维护5槽正式组合、整数股数、余款现金和槽内复利 |
| `StockShadowService` | 维护原始事件、无限资金影子和拒绝观察，不发即时消息 |
| `StockRoundTransactionService` | 短事务内完成待成交、路径更新、状态流转、槽位和通知审计写入 |
| `StockNoticeComposeService` | 将内部英文编码转换为正式中文消息，执行同轮合并和优先级 |
| `StockNoticeSendService` | 事务提交后使用现有Bot发送一次并更新最小通知审计状态 |
| `StockDailySummaryService` | 每天08:30汇总正式组合和Shadow数据，发送中文摘要 |

### 4.2 设计模式

- 买入规则使用策略模式，避免六类风格复制六套策略。
- 资格判断使用责任分离的Policy组合，不把所有条件堆入一个超大Service。
- 候选排序、成交口径和消息映射使用独立Policy，保证回放与生产共用。
- 状态机使用显式枚举和合法迁移校验，不用自由字符串分支控制。

---

## 5. 时间、bar与特征口径

### 5.1 时区与桶边界

统一使用：

```text
ZoneId = Asia/Shanghai
```

15分钟桶：

```text
B(T) = [T, T+15分钟)
```

`T`必须对齐到每小时的00、15、30、45分。禁止依赖服务器或JVM默认时区。

### 5.2 bar可用标准

```text
sampleCount >= 10
AND lastSampleTime >= bucketEnd - 5分钟
```

含义：

- 一个15分钟桶最多允许缺少5个分钟采样；
- 桶尾最后5分钟内必须至少存在一个实际采样；
- 桶首缺失但后续样本达到标准时允许使用；
- `referencePrice`使用桶内最后一个实际价格；
- 同一股票、同一采集时间出现重复记录时，按`id DESC`确定性保留最后一行，并记录重复数量；
- 不使用插值、前向填充或未来价格补齐缺失分钟。

### 5.3 连续bar

`B1`只有在时间上紧邻`B0`且两者均满足bar可用标准时，才是`B0`的连续下一bar：

```text
B1.bucketStart = B0.bucketStart + 15分钟
```

更晚的可用bar不能替代紧邻下一bar。

### 5.4 参考成交

```text
信号参考价 = 信号bar内最后一个实际价格
买入参考价 = 紧邻下一连续bar内最后一个实际价格
卖出参考价 = 退出信号后紧邻下一连续bar内最后一个实际价格
```

决策与成交必须分离。买入或卖出待成交状态在获得紧邻下一连续bar前不得假定成交。

### 5.5 ENTRY_PENDING过期

```text
expectedEntryBarStart = signalBarStart + 15分钟
expectedEntryBarEnd   = signalBarStart + 30分钟
staleAt               = expectedEntryBarEnd + 5分钟
```

恢复或补算晚于`staleAt`时，即使数据库后来能够补出该桶，也取消候选：

```text
ENTRY_PENDING → CANCELLED
reason = ENTRY_DATA_STALE
```

从信号桶开始最多等待35分钟，不使用更晚桶建立参考仓，也不补发过时买入消息。

### 5.6 ENTRY价格偏离

```text
entryDeviation = entryReferencePrice / signalReferencePrice - 1
```

仅向上偏离触发取消：

```text
entryDeviation > 0.0015
→ CANCELLED / ENTRY_PRICE_DEVIATION
```

- 恰好0.15%不取消；
- 价格相同不取消；
- 价格下跌不因偏离取消；
- 仍须通过数据完整性、同股状态和槽位复核。

### 5.7 15分钟特征

正式特征至少包含：

- `ma1d`、`ma7d`、`ma30d`；
- `zscore1d`、`zscore7d`、`zscore30d`；
- `return6h`、`return1d`、`return7d`、`return14d`；
- `low30d`、`high30d`；
- `width30d`；
- `position30`；
- `pctAbove30dLow`、`pctBelow30dHigh`；
- `strategyReady`和数据质量原因。

特征只使用`bar_time`及以前的可见bar。数据断层后的预热期可继续为开放持仓提供市场报价，但不得产生新买入信号，直到全部策略窗口重新满足`strategyReady`。

---

## 6. 业务规则落地

### 6.1 月度状态

每支股票每月保存三个独立概念：

```text
strategyFitPrior + maturity + riskLevel
```

完整指标、六类阈值、风险投票、NARROW/RANGING迟滞、人工覆盖和确认语义统一以`stock_personality_monthly_calibration.md`的`PERSONALITY_RULE_V1 / RISK_RULE_V1_SHADOW`为准，不得从当前CSV或`sys_setting.STOCK_PERSONALITY`反推。

关键边界：

- 日级趋势、全窗口收益、价格带和最大回撤可使用每日最后一个usable 15分钟bar；但完整自然月`monthMean`、`monthChange`、`negativeMonthRatio`和`negativeMonthStreak`必须直接以月内**全部**usable 15分钟`lastPrice`算术均值计算，禁止先降采样为日末价；
- 成熟度按证据自然日60/120/240/365计算，不按15分钟bar数量的1/7/30天阈值；
- `suggestedPersonality`是机器原始分类应用月度迟滞后的建议；
- `strategyFitPrior`是最终确认值，人工覆盖时不改写机器建议；
- previous只读取最近一个更早生效且`CONFIRMED`的月份；
- 人工确认必须传入真实`confirmedBy`；`SYSTEM`只允许完整性校验通过的自动确认入口；
- 风格、风险或证据不完整时保持DRAFT并fail-closed。

冷启动和跨月补偿必须先补齐证据再计算月度状态：

```text
历史bar/feature补建完成
→ 计算或重算目标月份DRAFT
→ 人工确认或完整性校验后的系统自动确认
→ CONFIRMED状态才进入BUY资格
```

不完整DRAFT不能因“当月记录已存在”而永久跳过重算。重算只允许覆盖尚未确认且无人工覆盖的DRAFT；CONFIRMED、RETIRED和人工覆盖记录必须保持不可覆盖。`autoConfirmDraftStates`必须存在明确生产调用入口，不能只实现无人调用的方法。

- 风格决定策略适配，不是独立买卖信号；
- 成熟度表达历史长度和不确定性；
- 风险等级用于解释、降权和影子观察；
- 风格缺失或过期时暂停正式买入，禁止默认`STEADY`；
- 开仓批次固化当月状态和版本，次月变化不回写旧批次。

### 6.2 正式买入策略

#### 深度均值回归

内部编码：`DEEP_MEAN_REVERSION_BUY`

适用风格：`NARROW / RANGING / STEADY`

```text
距30日最低价 <= 0.3%
AND effectiveZ1 <= -2.0
AND return7d >= -1%
AND MA7 / MA30 - 1 >= -2%
```

`NARROW`风格：

```text
effectiveZ1 = rawZ1 × 0.6
```

#### 区间下沿买入

内部编码：`RANGE_LOWER_BUY`

适用风格：`NARROW / RANGING`

```text
width30 <= 8%
AND position30 <= 10%
AND effectiveZ1 <= -0.5
AND return6h <= 0
AND return7d >= -2%
AND MA7 / MA30 - 1 >= -2%
```

RANGE绝对趋势保护已冻结：两个条件是并列AND，等于-2%通过。`return7d < -2%`使用`ABSOLUTE_TREND_GUARD_FAILED`；`return7d/MA7/MA30`任一缺失时返回数据不足而不是普通策略不命中。该阈值不得替换成DEEP的-1%，也不得因7月留出表现更高而事后收紧。

#### 严格反弹确认

内部编码：`STRICT_REBOUND_CONFIRM_BUY`

仅适用：`WEAK / DECLINER`

```text
距30日最低价 <= 0.5%
AND return1d > 0
AND Z1 >= 0.8
AND 当前价格 <= MA30 × 1.002
```

### 6.3 质量分与候选排序

```text
deepScore = 100
          + max(0, -effectiveZ1) × 10
          + max(0, 0.003 - low30Distance) × 1000

rangeScore = 80
           + max(0, 0.10 - position30) × 100
           + max(0, -effectiveZ1) × 5

reboundScore = 60
             + Z1 × 5
             + max(0, 0.005 - low30Distance) × 1000
```

同一股票多策略命中时：

1. 质量分最高的策略作为`primaryStrategy`；
2. 其他命中策略写入`matchedStrategies`；
3. 只创建一个候选；
4. 策略质量分完全相同时按策略编码升序选择主策略，保证确定性。

不同股票竞争正式槽位：

```text
qualityScore DESC
→ stocksId ASC
```

禁止使用枚举遍历顺序、集合顺序、股票简称或未来收益排序。

### 6.4 正式原因码映射

首期固定退出和HOLD使用以下唯一映射。退出必要输入按**适用规则**判断，不要求所有策略都具备RANGE特征：

```text
所有批次基础输入：
  entryReferencePrice>0, currentPrice>0, entryTime及roundTime非空

非RANGE批次：
  仅评估目标、硬风险、时间
  三项均未命中 → HOLD_NO_EXIT_TRIGGERED
  position30/low30d/high30d不属于必要输入

RANGE_LOWER_BUY批次：
  先评估目标、硬风险、时间
  均未命中后必须评估RANGE恢复
  区间特征完整且未命中 → HOLD_NO_EXIT_TRIGGERED
  区间特征缺失/无效 → DATA_INSUFFICIENT / EXIT_RANGE_FEATURE_MISSING → DATA_STALE
```

正式退出映射：

```text
netReturn >= +0.8%
→ CLOSED_TARGET / SELL_TARGET_REACHED

RANGE且netReturn>0且position30>=0.60
→ CLOSED_RANGE / SELL_RANGE_RECOVERED

netReturn <= -1.5%
→ CLOSED_RISK / SELL_HARD_RISK

持有时间 >= 14天
→ CLOSED_TIME / SELL_MAX_HOLD
```

`SELL_STRUCTURAL_RISK`保留给未来多证据结构风险状态机。输入或该策略必要特征不完整属于不可评估，不得使用`HOLD_NO_EXIT_TRIGGERED`。RANGE批次若已先命中目标、硬风险或时间退出，则区间特征缺失不阻止该确定性退出。

### 6.5 false→true边沿

- 原始信号只在买入条件从false变为true时生成；
- 持续为true期间不重复生成高度相关机会；
- 正常、区间、时间或未来动态关闭后冷却24小时；
- 风险关闭后冷却48小时；
- 冷却结束后必须先观察条件回到false，再等待新的false→true边沿；
- 同股已有正式活跃批次时仍记录原始信号和拒绝原因，但不建立第二个正式批次。

### 6.6 正式退出

#### 目标退出

```text
netReturn >= +0.8%
→ EXIT_PENDING
→ 成交后 CLOSED_TARGET
```

#### 风险退出

```text
netReturn <= -1.5%
→ EXIT_PENDING
→ 成交后 CLOSED_RISK
```

#### 最长持有退出

```text
持有时间 >= 14天
→ EXIT_PENDING
→ 成交后 CLOSED_TIME
```

#### 区间恢复退出

```text
primaryStrategy属于区间下沿策略
AND netReturn > 0
AND position30 >= 0.60
→ EXIT_PENDING
→ 成交后 CLOSED_RANGE
```

```text
position30 = (currentPrice - low30) / (high30 - low30)
```

仅当目标、硬风险和最长持有均未命中后，RANGE批次才评估区间恢复。此时`position30/low30d/high30d`任一缺失，或`high30d <= low30d`，均表示区间规则不可评估：返回`DATA_INSUFFICIENT / EXIT_RANGE_FEATURE_MISSING`，批次转`DATA_STALE`并继续占槽；本轮不得生成`HOLD_NO_EXIT_TRIGGERED`或普通HOLD BatchMark。非RANGE批次不要求区间特征。

#### 卖出费

```text
netReturn = exitReferencePrice / entryReferencePrice × 0.999 - 1
```

所有群消息、账本、收益统计和关闭判断统一使用扣除0.1%卖出费后的净收益。

### 6.7 影子规则

首期并行记录但不影响正式关闭：

- 动态自然卖出研究事实（`RESEARCH_DATA_ONLY`，当前公式未冻结，不产生SELL建议）；
- 高风险硬否决观察；
- 满仓拒绝机会；
- 风格拒绝机会；
- 当前Java原始买入对照；
- 可选盈利换仓，默认关闭。

裸连续下跌不得成为独立卖出理由。

当前动态SELL轨道固定写：

```text
dynamicShadowDecision = NOT_EVALUATED
dynamicShadowReason = DYNAMIC_RULE_NOT_FROZEN
```

它只保存批次路径和候选输入，不得写`HOLD/SELL`动态判断，不得改变`dynamicSellState`为带投资语义的状态，不得进入`EXIT_PENDING/CLOSED_DYNAMIC`，不得计入日报“动态SELL建议数”。隔离回放当前也只输出动态研究输入覆盖率与数据质量，不计算动态SELL交易或收益。只有业务知识库另行冻结公式和新规则版本后，才可实现可执行Shadow。

---

## 7. 四层账本与资金模型

### 7.1 原始信号事件账本

记录所有false→true事件，不受资金和槽位限制，保存：

- 信号时间、股票和策略；
- 特征快照；
- 月度风格、成熟度和风险；
- 资格结果和原因；
- 候选排名和组合决策；
- 后续MFE、MAE和理论结果。

不发即时群消息。

### 7.2 无限资金影子批次

- 不受正式5槽限制；
- 同一股票×策略版本最多一个开放影子批次；
- 完整模拟买入到卖出；
- 用于判断信号本身是否有优势；
- 不发即时群消息。

### 7.3 拒绝观察事件

拒绝观察批次始终保持`REJECTED_OBSERVATION / CANCELLED`，不进入正式或无限资金持仓状态。独立观察器按以下规则回写信号事件：

- 可观察拒绝原因使用信号后的紧邻下一连续bar作为理论入场；
- 理论入场同样受0.15%向上偏离限制；失败不等待更晚bar，立即结算为`NO_THEORETICAL_ENTRY`；
- 成功入场后观察14个自然日，按纯价格路径计算laterMfe/laterMae；
- 同时模拟正式冻结生命周期，但不发BUY/SELL、不占槽位、不预留资金；
- 数据缺口不插值、不延长日历窗口；
- 数据/风格缺失、ENTRY陈旧或价格偏离拒绝不建立理论路径。

完整原因分组、`resolvedAt`和数据不足规则以业务主文档`vip_stock_virtual_portfolio_strategy.md`第4.3节为准。

### 7.4 正式5槽组合

```text
槽位数：5
每槽初始资金：2,000,000,000
总初始资金：10,000,000,000
```

每槽独立记账：

```text
quantity = floor(availableCash / entryReferencePrice)
actualCost = quantity × entryReferencePrice
remainingCash = availableCash - actualCost
sellProceeds = quantity × exitReferencePrice × 0.999
newAvailableCash = remainingCash + sellProceeds
```

规则：

- 股数使用整数；
- 余款保留在原槽；
- 卖出所得回到原槽；
- 后续批次使用槽位实际可用现金，实现槽内复利；
- 槽位之间不自动调拨；
- `ENTRY_PENDING`应预留槽位和预算；
- `EXIT_PENDING`在实际卖出参考成交前继续占槽；
- 数据陈旧状态继续占槽；
- 取消待买候选时释放完整预留资金和槽位。

组合权益：

```text
equity = 全部可用现金
       + 待买预留现金
       + 开放仓位按当前实际价格计算的扣费后市值
```

---

## 8. 状态机

### 8.1 正式批次状态

```text
ENTRY_PENDING
  ├─ 下一连续bar可用且价格偏离通过 → OPEN
  ├─ 下一桶不可用且超过staleAt → CANCELLED
  ├─ 向上偏离>0.15% → CANCELLED
  └─ 同股/槽位复核失败 → CANCELLED

OPEN
  ├─ 正式退出成立 → EXIT_PENDING
  ├─ 数据不可用 → DATA_STALE
  └─ 无退出 → OPEN

DATA_STALE
  ├─ 新的可用桶形成 → OPEN
  └─ 仍无可用数据/必要特征 → 保持DATA_STALE并继续占槽

EXIT_PENDING
  ├─ 紧邻下一连续bar成交 → CLOSED_TARGET/CLOSED_RANGE/CLOSED_RISK/CLOSED_TIME
  └─ 成交桶不可用 → DATA_STALE_EXIT

DATA_STALE_EXIT
  ├─ 无合法恢复价格 → 保持DATA_STALE_EXIT、占槽并告警
  └─ 首个恢复可用bar → ADMIN_CLOSED / SELL_DATA_ADMIN_CLOSE
```

`DATA_STALE_EXIT`灾难处置使用首个恢复可用bar的`lastPrice`作为管理关闭参考价，不声称为原退出策略的准时成交。没有恢复价格时禁止用成本、最后旧价或零价强制结算。

`DATA_STALE`不自动进入`ADMIN_CLOSED`。它尚未形成确定退出决策，数据恢复后回到`OPEN`重新评估；如需人工管理关闭，必须先取得可验证实际价格，并另行记录人工原因后复用本节正式账本校验、原子结算和`ADMIN_CLOSED`审计契约，禁止无价格注销。

#### 8.1.1 恢复bar判定

恢复bar必须同时满足：

```text
bar != null
AND bar.usable = true
AND sampleCount/尾部新鲜度满足正式bar质量
AND buildVersion = 当前正式BUILD_VERSION
AND lastPrice > 0
AND barStartTime/EndTime完整
```

不要求与`expectedExitBarTime`连续。每轮只加载该股票当前轮次的正式bar，因此第一次满足上述条件的轮次就是“首个恢复可用bar”。批次进入`DATA_STALE_EXIT`后，在每个已结束轮次按时间升序处理，禁止跳过更早恢复bar选择更晚价格。

#### 8.1.2 正式账本结算前置条件

正式批次关闭前必须一次性校验：

```text
ledgerType = FORMAL
AND batchStatus = DATA_STALE_EXIT
AND batch.id/slotId/slotNo/stocksId完整
AND quantity > 0
AND entryReferencePrice > 0
AND exitSignalTime != null
AND expectedExitBarTime != null
AND slot存在
AND slot.slotNo = batch.slotNo
AND slot.currentBatchId = batch.id
AND slot.status IN (OCCUPIED, STALE)
AND slot.availableCash/reservedCash非负
AND slot.reservedCash = 0
AND slot.availableCash = batch.remainingCash（按BigDecimal数值比较）
```

任一条件不满足即视为正式账本一致性破坏，抛出`IllegalStateException`使整个轮次事务回滚。不得跳过`settleSlot`后继续写`ADMIN_CLOSED`；不得返回“成交批次”；不得生成通知或冷却状态。错误日志必须包含`batchId/batchNo/slotId/slotNo`，但不得包含敏感配置。

影子批次必须显式区分账本类型：`UNLIMITED_SHADOW`不访问任何槽位、仅计算理论收益；`SHADOW_FORMAL_CANDIDATE`仅访问独立的`VIP_SHADOW_CANDIDATE`五槽，不得访问`VIP_FORMAL`；`REJECTED_OBSERVATION`不进入入场/资金结算。未知或空`ledgerType`同样fail-closed，禁止按“非Shadow即正式”推断。

#### 8.1.3 原子结算与终态

正式灾难关闭与正常正式卖出共用同一个“校验并结算正式槽位”领域方法，避免两套资金逻辑。该方法在`StockRoundTransactionService`短事务内完成：

共用范围仅包含批次—槽位绑定、数量/价格/余款校验和资金结算。调用方必须先校验各自来源状态：正常卖出要求`EXIT_PENDING`且成交bar连续；灾难关闭要求`DATA_STALE_EXIT`且恢复bar满足8.1.1。共用方法不得把来源状态统一写死为`DATA_STALE_EXIT`，也不得自行决定最终关闭状态。

```text
sellProceeds = quantity × recoveryBar.lastPrice × 0.999
slot.availableCash = batch.remainingCash + sellProceeds
slot.reservedCash = 0
slot.currentBatchId = null
slot.slotStatus = AVAILABLE

batch.batchStatus = ADMIN_CLOSED
batch.exitReferencePrice = recoveryBar.lastPrice
batch.exitTime = recoveryBar.barEndTime
batch.netReturn = recoveryBar.lastPrice / entryReferencePrice × 0.999 - 1
batch.sellProceeds = sellProceeds
batch.cooldownUntil = recoveryBar.barEndTime + 48小时
batch.resetObserved = false
```

批次、槽位、信号冷却、管理关闭审计、SELL通知审计及轮次状态必须在同一数据库事务提交；任一持久化失败全部回滚。NapCat调用继续位于事务提交后。

#### 8.1.4 管理关闭审计模型

第九轮采用**批次独立字段 + 通知完整payload**方案，不新增管理关闭专表，低侵入补齐现有`TornStockVirtualBatchDO`：

| 字段 | 类型 | 空值 | 写入规则 |
|---|---|---:|---|
| `original_exit_reason` | VARCHAR(64) | 是 | `EXIT_PENDING → DATA_STALE_EXIT`时复制当时`exit_reason`；之后不可覆盖 |
| `admin_close_reason` | VARCHAR(64) | 是 | 灾难关闭固定`DATA_STALE_EXIT_RECOVERY_CLOSE` |
| `recovery_bar_start_time` | TIMESTAMP | 是 | 恢复bar开始时间 |
| `recovery_bar_end_time` | TIMESTAMP | 是 | 恢复bar结束时间，必须等于`exit_time` |
| `stale_exit_duration_seconds` | BIGINT | 是 | `max(0, recoveryBarEnd - (expectedExitBarTime + 15分钟))`秒数；从原预期bar结束到恢复bar结束 |

字段约束：

```text
batch_status = ADMIN_CLOSED
AND admin_close_reason = DATA_STALE_EXIT_RECOVERY_CLOSE
→ original_exit_reason、expected_exit_bar_time、recovery_bar_start_time、
  recovery_bar_end_time、stale_exit_duration_seconds必须非空

recovery_bar_end_time > recovery_bar_start_time
stale_exit_duration_seconds >= 0
exit_time = recovery_bar_end_time
```

`staleExitDuration`的单位由技术方案统一冻结为**秒**；Java字段名使用`staleExitDurationSeconds`，数据库字段使用`stale_exit_duration_seconds`，通知可格式化为“X天Y小时Z分钟”。

`exit_reason`在进入`EXIT_PENDING`后仍保留原策略关闭类型，用于历史兼容和原决策追踪；`admin_close_reason`表达最终管理关闭原因。对外稳定正式原因码由通知payload使用`SELL_DATA_ADMIN_CLOSE`，不能把该编码写入`exit_reason`冒充原策略原因。

`EXIT_PENDING → DATA_STALE_EXIT`迁移必须通过统一方法完成，并在**首次迁移**时执行：

```text
originalExitReason = exitReason
batchStatus = DATA_STALE_EXIT
```

若批次已经是`DATA_STALE_EXIT`，后续轮次不得再次覆盖`originalExitReason`。历史异常数据若`originalExitReason`为空但`exitReason`是合法`CLOSED_*`，迁移脚本允许一次性回填；无法确定原原因时不得自动管理关闭，应保持待人工核对。

> **历史部署断言已废止。** 当前基线确认：**第一批股票功能已部署至生产环境；第二批功能尚未部署，正式库尚未执行第二批相关changeSet。** 本轮第三批第一轮修复建立在第二批未部署基线上：仅第二批新增/改写的Schema可按第22.8节直接改写`stocks-portfolio.yaml`中的第二批首次发布定义；第一批已执行changeSet及其checksum一律不可改写。实施前必须按`databasechangelog`重新确认第一批/第二批的执行边界；若任何兼容环境已执行第二批changeSet，立即停止改写第二批定义，保留旧checksum并改用追加changeSet和升级验证。

#### 8.1.5 长时间未恢复与运维告警

没有合法恢复bar时：

```text
保持DATA_STALE_EXIT
保持槽位占用/STALE
不写退出价/退出时间/管理关闭审计
不生成SELL通知
每轮记录结构化WARN
```

首期不新增告警平台或强制注销期限。“进入运维告警”实现为结构化WARN日志，字段至少包含`batchId/batchNo/stocksId/expectedExitBarTime/staleDurationMinutes`；同一批次每小时最多输出一次WARN，避免15分钟轮次日志噪声。人工关闭也必须取得可验证实际价格并复用相同`ADMIN_CLOSED`结算与审计契约。

### 8.2 轮次状态

```text
PENDING
→ BUILDING_BAR
→ BUILDING_FEATURE
→ READY
→ PROCESSING
→ COMPLETED
```

异常状态：

```text
WAITING_DATA
FAILED_RETRYABLE
FAILED_FINAL
```

当前单实例使用JVM`AtomicBoolean`防止轮次调度重入，数据库`round_time`唯一约束保证重启补偿幂等，不引入分布式锁。

轮次记录必须有持续生产入口，不能只消费数据库中已经存在的轮次：

```text
每分钟第10秒
→ 计算最近已结束的15分钟桶
→ 在存在数据构建、存量管理或拒绝观察义务时，对尚不存在的桶幂等创建PENDING轮次
→ 再按round_time升序处理全部未完成轮次
```

持续轮次生产约束：

- 只创建已结束桶，不创建当前桶或未来桶；
- 同一`round_time`并发或重试只保留一条；
- `ALERT=true`时持续创建；
- `ALERT=false`但存在正式/影子活跃批次或未结算拒绝观察时继续创建管理所需轮次；
- 无任何运行义务时停止创建；
- 首次启用和重启追平后，至少连续观察3个新的真实桶均完成`PENDING → ... → COMPLETED`，才能认定调度链可用。

### 8.3 通知审计状态

本期仅实现最小审计，不建设可靠消息专题：

```text
PENDING
→ 调用现有Bot一次
→ SENT 或 FAILED
```

- 不自动重试；
- 不解析NapCat ACK；
- 不建设`UNKNOWN`或消息对账；
- 不使用`FOR UPDATE SKIP LOCKED`消费；
- 后续NapCat专题可在该表基础上扩展。

---

## 9. 数据库设计

> 第22.4和第22.8节对本节的双Shadow结构、事件关联字段、索引及Liquibase策略具有优先级；以下表定义已同步纳入该契约。

### 9.1 通用约定

- PostgreSQL 17.5。
- Liquibase YAML建表，每张表和每个字段必须包含中文`remarks`。
- Java DO继承`BaseDO`时统一包含`deleted/create_time/update_time`。
- 高频过滤、连接和排序字段使用普通列。
- 特征、规则、原因和消息审计快照使用`JSONB`。
- 状态、策略、风格和原因使用`VARCHAR`保存稳定英文编码，不使用PostgreSQL ENUM，便于规则版本演进。
- 金额使用`NUMERIC/DECIMAL`，Java使用`BigDecimal`。
- 时间使用`TIMESTAMP`并由业务层按`Asia/Shanghai`解释和生成桶边界。
- 所有新表使用逻辑删除字段，但业务唯一索引应按`deleted = 0`设计部分唯一索引。
- 外键可用于新表内部引用；对既有`torn_stocks`仅保存`stocks_id`逻辑引用，避免迁移耦合既有表删除策略。

### 9.2 `torn_stock_market_bar_15m`

用途：保存从分钟行情构建的标准15分钟市场bar和数据质量事实。

| 字段 | 类型 | 空值 | 说明 |
|---|---|---:|---|
| `id` | BIGINT | 否 | 主键ID |
| `stocks_id` | INT | 否 | 股票ID |
| `stocks_shortname` | VARCHAR(8) | 否 | 股票简称快照 |
| `bar_start_time` | TIMESTAMP | 否 | 15分钟桶开始时间，Asia/Shanghai边界 |
| `bar_end_time` | TIMESTAMP | 否 | 15分钟桶结束时间，不含该时点 |
| `first_sample_time` | TIMESTAMP | 否 | 桶内第一条实际分钟采样时间 |
| `last_sample_time` | TIMESTAMP | 否 | 桶内最后一条实际分钟采样时间 |
| `first_price` | DECIMAL(18,6) | 否 | 桶内第一条实际价格 |
| `last_price` | DECIMAL(18,6) | 否 | 桶内最后一条实际价格，也是决策/成交参考价 |
| `low_price` | DECIMAL(18,6) | 否 | 桶内实际采样最低价，仅作聚合事实 |
| `high_price` | DECIMAL(18,6) | 否 | 桶内实际采样最高价，仅作聚合事实 |
| `sample_count` | INT | 否 | 去重后的分钟采样数量 |
| `duplicate_count` | INT | 否 | 构建时发现的重复分钟记录数量 |
| `tail_gap_seconds` | INT | 否 | 桶结束到最后采样的秒数 |
| `usable` | BOOLEAN | 否 | 是否满足正式bar可用标准 |
| `quality_reason` | VARCHAR(64) | 是 | 不可用原因编码 |
| `build_version` | VARCHAR(32) | 否 | bar构建规则版本 |
| `source_max_history_id` | BIGINT | 是 | 本桶使用的最大原始历史ID，用于审计 |
| `deleted` | SMALLINT | 否 | 逻辑删除标识，0有效1删除 |
| `create_time` | TIMESTAMP | 否 | 创建时间 |
| `update_time` | TIMESTAMP | 否 | 更新时间 |

约束与索引：

```text
唯一：(stocks_id, bar_start_time, build_version) WHERE deleted=0
索引：(bar_start_time, usable, build_version)
索引：(stocks_id, bar_start_time DESC) WHERE deleted=0
检查：bar_end_time = bar_start_time + 15分钟
检查：sample_count >= 0，duplicate_count >= 0，tail_gap_seconds >= 0
检查：first_price/last_price/low_price/high_price > 0
```

### 9.3 `torn_stock_strategy_feature_15m`

用途：保存指定15分钟bar时点的正式策略因果特征。

| 字段 | 类型 | 空值 | 说明 |
|---|---|---:|---|
| `id` | BIGINT | 否 | 主键ID |
| `stocks_id` | INT | 否 | 股票ID |
| `stocks_shortname` | VARCHAR(8) | 否 | 股票简称快照 |
| `bar_start_time` | TIMESTAMP | 否 | 对应决策bar开始时间 |
| `reference_price` | DECIMAL(18,6) | 否 | 本bar最后一个实际价格 |
| `ma1d/ma7d/ma30d` | DECIMAL(18,8) | 否 | 1/7/30日bar均价 |
| `zscore1d/zscore7d/zscore30d` | DECIMAL(18,8) | 否 | 1/7/30日标准化偏离 |
| `return6h/return1d/return7d/return14d` | DECIMAL(18,10) | 否 | 对应时间窗口收益率 |
| `low30d/high30d` | DECIMAL(18,6) | 否 | 30日最低/最高实际bar价格 |
| `width30d` | DECIMAL(18,10) | 否 | 30日价格带宽 |
| `position30` | DECIMAL(18,10) | 是 | 当前价格在30日区间的位置，高低价相同则为空 |
| `pct_above_30d_low` | DECIMAL(18,10) | 否 | 距30日低点涨幅 |
| `pct_below_30d_high` | DECIMAL(18,10) | 否 | 距30日高点跌幅 |
| `strategy_ready` | BOOLEAN | 否 | 是否具备产生新买入信号的完整窗口 |
| `data_quality_reason` | VARCHAR(64) | 是 | 特征不可用于买入的原因编码 |
| `feature_version` | VARCHAR(32) | 否 | 特征计算版本 |
| `deleted/create_time/update_time` | 通用字段 | 否 | 逻辑删除和审计字段 |

约束与索引：

```text
唯一：(stocks_id, bar_start_time, feature_version) WHERE deleted=0
索引：(bar_start_time, strategy_ready, feature_version)
索引：(stocks_id, bar_start_time DESC) WHERE deleted=0
检查：reference_price、ma、low30d、high30d > 0
检查：width30d >= 0
检查：position30为空或位于合理数值范围；不使用该检查限制极端审计值时由服务校验
```

### 9.4 `torn_stock_monthly_state`

用途：保存每支股票按月冻结的策略风格、成熟度、风险和证据快照。

| 字段 | 类型 | 空值 | 说明 |
|---|---|---:|---|
| `id` | BIGINT | 否 | 主键ID |
| `stocks_id` | INT | 否 | 股票ID |
| `stocks_shortname` | VARCHAR(8) | 否 | 股票简称快照 |
| `effective_month` | DATE | 否 | 生效月份，固定为当月1日 |
| `strategy_fit_prior` | VARCHAR(32) | 否 | 最终策略适配风格编码 |
| `maturity` | VARCHAR(32) | 否 | 成熟度编码 |
| `risk_level` | VARCHAR(16) | 否 | 风险等级编码 |
| `suggested_personality` | VARCHAR(32) | 否 | 机器建议风格 |
| `previous_personality` | VARCHAR(32) | 是 | 上一期最终风格 |
| `manual_override` | BOOLEAN | 否 | 是否人工覆盖机器建议 |
| `override_reason` | VARCHAR(512) | 是 | 人工覆盖原因 |
| `metric_snapshot` | JSONB | 否 | 分类时完整指标快照 |
| `personality_rule_version` | VARCHAR(32) | 否 | 风格规则版本 |
| `risk_rule_version` | VARCHAR(32) | 否 | 风险规则版本 |
| `evidence_start_time` | TIMESTAMP | 否 | 证据窗口开始时间 |
| `evidence_end_time` | TIMESTAMP | 否 | 证据窗口结束时间 |
| `state_status` | VARCHAR(16) | 否 | `DRAFT/CONFIRMED/RETIRED` |
| `calculated_at` | TIMESTAMP | 否 | 机器计算时间 |
| `confirmed_at` | TIMESTAMP | 是 | 人工确认时间 |
| `confirmed_by` | VARCHAR(64) | 是 | 确认人标识 |
| `deleted/create_time/update_time` | 通用字段 | 否 | 逻辑删除和审计字段 |

约束与索引：

```text
唯一：(stocks_id, effective_month) WHERE deleted=0
索引：(effective_month, state_status)
检查：effective_month为月份首日
检查：evidence_start_time <= evidence_end_time
检查：CONFIRMED状态必须有confirmed_at
```

### 9.5 `torn_stock_market_round`

用途：保存15分钟组合轮次的幂等、补偿和执行状态。

| 字段 | 类型 | 空值 | 说明 |
|---|---|---:|---|
| `id` | BIGINT | 否 | 主键ID |
| `round_time` | TIMESTAMP | 否 | 轮次对应bar开始时间 |
| `round_status` | VARCHAR(32) | 否 | 轮次状态编码 |
| `bar_build_version` | VARCHAR(32) | 否 | 使用的bar构建版本 |
| `feature_version` | VARCHAR(32) | 否 | 使用的特征版本 |
| `buy_rule_version` | VARCHAR(32) | 否 | 买入规则版本 |
| `sell_rule_version` | VARCHAR(32) | 否 | 卖出规则版本 |
| `allocation_rule_version` | VARCHAR(32) | 否 | 资金和排序版本 |
| `message_rule_version` | VARCHAR(32) | 否 | 消息规则版本 |
| `expected_stock_count` | INT | 否 | 本轮预期股票数量 |
| `usable_stock_count` | INT | 否 | 本轮可用于正式决策的股票数量 |
| `started_at` | TIMESTAMP | 是 | 开始处理时间 |
| `completed_at` | TIMESTAMP | 是 | 完成时间 |
| `attempt_count` | INT | 否 | 轮次处理尝试次数 |
| `error_message` | VARCHAR(1000) | 是 | 最近失败摘要，不含敏感数据 |
| `deleted/create_time/update_time` | 通用字段 | 否 | 逻辑删除和审计字段 |

约束与索引：

```text
唯一：(round_time) WHERE deleted=0
索引：(round_status, round_time)
检查：attempt_count >= 0
检查：usable_stock_count <= expected_stock_count
```

### 9.6 `torn_stock_signal_state`

用途：保存每支股票、每个买入策略版本的边沿、冷却和复位状态。

| 字段 | 类型 | 空值 | 说明 |
|---|---|---:|---|
| `id` | BIGINT | 否 | 主键ID |
| `stocks_id` | INT | 否 | 股票ID |
| `strategy_type` | VARCHAR(64) | 否 | 买入策略编码 |
| `buy_rule_version` | VARCHAR(32) | 否 | 买入规则版本 |
| `condition_active` | BOOLEAN | 否 | 上一轮买入条件是否为true |
| `last_evaluated_round_time` | TIMESTAMP | 是 | 最近评估轮次 |
| `last_signal_time` | TIMESTAMP | 是 | 最近false→true信号时间 |
| `cooldown_until` | TIMESTAMP | 是 | 冷却结束时间 |
| `reset_observed` | BOOLEAN | 否 | 关闭后是否已观察到条件恢复false |
| `last_close_type` | VARCHAR(32) | 是 | 最近关闭类型，用于冷却口径 |
| `deleted/create_time/update_time` | 通用字段 | 否 | 逻辑删除和审计字段 |

约束与索引：

```text
唯一：(stocks_id, strategy_type, buy_rule_version) WHERE deleted=0
索引：(cooldown_until, reset_observed)
```

### 9.7 `torn_stock_signal_event`

用途：保存全部原始信号、资格、排名、组合决定和后续研究结果。

| 字段 | 类型 | 空值 | 说明 |
|---|---|---:|---|
| `id` | BIGINT | 否 | 主键ID |
| `event_no` | VARCHAR(40) | 否 | 原始信号事件编号 |
| `round_time` | TIMESTAMP | 否 | 信号轮次 |
| `stocks_id` | INT | 否 | 股票ID |
| `stocks_shortname` | VARCHAR(8) | 否 | 股票简称快照 |
| `strategy_type` | VARCHAR(64) | 否 | 命中的买入策略编码 |
| `quality_score` | DECIMAL(18,8) | 否 | 当时可见信息计算的质量分 |
| `feature_snapshot` | JSONB | 否 | 信号特征快照 |
| `style_snapshot` | JSONB | 否 | 风格、成熟度、风险和版本快照 |
| `eligibility_result` | VARCHAR(16) | 否 | `ALLOWED/REJECTED/OBSERVED` |
| `eligibility_reasons` | JSONB | 否 | 资格原因编码列表 |
| `candidate_rank` | INT | 是 | 同轮候选排名 |
| `portfolio_decision` | VARCHAR(32) | 否 | 正式、影子或拒绝决定 |
| `reject_reason` | VARCHAR(64) | 是 | 主要拒绝原因编码 |
| `formal_batch_id` | BIGINT | 是 | 关联正式批次ID |
| `shadow_candidate_batch_id` | BIGINT | 是 | 关联5槽正式候选影子批次ID；与正式、无限资金影子独立 |
| `shadow_batch_id` | BIGINT | 是 | 仅关联无限资金影子批次ID |
| `later_mfe/later_mae` | DECIMAL(18,10) | 是 | 后续最大有利/不利变动 |
| `resolved_at` | TIMESTAMP | 是 | 研究结果解析完成时间 |
| `deleted/create_time/update_time` | 通用字段 | 否 | 逻辑删除和审计字段 |

约束与索引：

```text
唯一：event_no WHERE deleted=0
唯一：(stocks_id, strategy_type, round_time, buy_rule_version等价版本字段) WHERE deleted=0
索引：(round_time, portfolio_decision)
索引：(stocks_id, round_time DESC)
索引：(reject_reason, round_time)
```

实现时应显式增加`buy_rule_version`列，不能只把版本藏在JSONB中。

### 9.8 `torn_stock_portfolio_slot`

用途：保存带槽位组合的独立资金槽位；包括正式组合和5槽正式候选影子组合。

| 字段 | 类型 | 空值 | 说明 |
|---|---|---:|---|
| `id` | BIGINT | 否 | 主键ID |
| `portfolio_code` | VARCHAR(32) | 否 | 组合编码：`VIP_FORMAL`或`VIP_SHADOW_CANDIDATE` |
| `slot_no` | INT | 否 | 槽位编号1～5 |
| `initial_cash` | NUMERIC(24,2) | 否 | 槽位初始资金20亿 |
| `available_cash` | NUMERIC(24,2) | 否 | 当前可用现金 |
| `reserved_cash` | NUMERIC(24,2) | 否 | 待买批次已预留现金 |
| `current_batch_id` | BIGINT | 是 | 当前占用该槽的同账本批次ID |
| `slot_status` | VARCHAR(16) | 否 | `AVAILABLE/RESERVED/OCCUPIED/STALE` |
| `lock_version` | BIGINT | 否 | 乐观锁版本；组合事务仍使用行锁复核 |
| `deleted/create_time/update_time` | 通用字段 | 否 | 逻辑删除和审计字段 |

约束与索引：

```text
唯一：(portfolio_code, slot_no) WHERE deleted=0
检查：slot_no BETWEEN 1 AND 5
检查：initial_cash/available_cash/reserved_cash >= 0
检查：available_cash + reserved_cash不超过按账本可解释的槽位资产；由服务和对账任务验证
索引：(portfolio_code, slot_status, slot_no)
```

每个带槽位组合初始化5行，即`VIP_FORMAL`和`VIP_SHADOW_CANDIDATE`各5行；每行：

```text
initial_cash = 2000000000.00
available_cash = 2000000000.00
reserved_cash = 0.00
slot_status = AVAILABLE
```

### 9.9 `torn_stock_virtual_batch`

用途：统一保存正式、5槽正式候选影子、无限资金影子和拒绝观察批次。

| 字段 | 类型 | 空值 | 说明 |
|---|---|---:|---|
| `id` | BIGINT | 否 | 主键ID |
| `batch_no` | VARCHAR(40) | 否 | 系统批次编号 |
| `ledger_type` | VARCHAR(32) | 否 | `FORMAL/SHADOW_FORMAL_CANDIDATE/UNLIMITED_SHADOW/REJECTED_OBSERVATION` |
| `stocks_id/stocks_shortname` | INT/VARCHAR(8) | 否 | 股票标识及简称快照 |
| `primary_strategy` | VARCHAR(64) | 否 | 主买入策略编码 |
| `matched_strategies` | JSONB | 否 | 同时命中的策略编码列表 |
| `quality_score` | DECIMAL(18,8) | 否 | 入场候选质量分 |
| `batch_status` | VARCHAR(32) | 否 | 批次状态编码 |
| `signal_event_id` | BIGINT | 否 | 来源原始信号事件ID |
| `slot_id/slot_no` | BIGINT/INT | 是 | `FORMAL`或`SHADOW_FORMAL_CANDIDATE`占用对应组合槽位；无限资金影子和拒绝观察为空 |
| `signal_time` | TIMESTAMP | 否 | 买入信号时间 |
| `signal_reference_price` | DECIMAL(18,6) | 否 | 信号bar最后实际价格 |
| `expected_entry_bar_time` | TIMESTAMP | 否 | 预期紧邻下一bar时间 |
| `entry_stale_at` | TIMESTAMP | 否 | 待买过期时间 |
| `entry_time` | TIMESTAMP | 是 | 实际参考买入时间 |
| `entry_reference_price` | DECIMAL(18,6) | 是 | 参考买入价 |
| `quantity` | BIGINT | 是 | 正式或候选影子组合的整数股数；无限资金影子按其理论单位 |
| `invested_cash` | NUMERIC(24,2) | 是 | 正式或候选影子组合实际投入资金 |
| `remaining_cash` | NUMERIC(24,2) | 是 | 正式或候选影子组合建仓后的槽位余款 |
| `style_prior/style_maturity/risk_level` | VARCHAR | 否 | 开仓时冻结的月度状态 |
| `style_effective_month` | DATE | 否 | 冻结风格生效月份 |
| 六类规则版本 | VARCHAR(32) | 否 | 买入、卖出、风格、风险、分配、消息版本 |
| `follow_until` | TIMESTAMP | 是 | 买入消息发送后60分钟 |
| `follow_max_price` | DECIMAL(18,6) | 是 | 最高建议跟随价 |
| `peak_price/trough_price` | DECIMAL(18,6) | 是 | 持仓路径峰值和谷值 |
| `current_net_return/mfe/mae/peak_drawdown` | DECIMAL(18,10) | 是 | 当前和路径收益指标 |
| `dynamic_sell_state` | VARCHAR(32) | 是 | 动态卖出影子状态 |
| `max_hold_until` | TIMESTAMP | 是 | 最长持有截止时间 |
| `exit_signal_time` | TIMESTAMP | 是 | 退出信号时间 |
| `expected_exit_bar_time` | TIMESTAMP | 是 | 预期卖出参考bar |
| `exit_time` | TIMESTAMP | 是 | 参考卖出时间 |
| `exit_reference_price` | DECIMAL(18,6) | 是 | 参考卖出价 |
| `exit_reason` | VARCHAR(64) | 是 | 原策略退出类型，如`CLOSED_TARGET/CLOSED_RANGE/CLOSED_RISK/CLOSED_TIME`；灾难关闭时保留原值 |
| `original_exit_reason` | VARCHAR(64) | 是 | 进入`DATA_STALE_EXIT`时冻结的原策略退出类型，灾难关闭后不可覆盖 |
| `admin_close_reason` | VARCHAR(64) | 是 | 管理关闭原因；灾难恢复固定`DATA_STALE_EXIT_RECOVERY_CLOSE` |
| `recovery_bar_start_time` | TIMESTAMP | 是 | 灾难关闭所用首个恢复bar开始时间 |
| `recovery_bar_end_time` | TIMESTAMP | 是 | 灾难关闭所用首个恢复bar结束时间，与`exit_time`一致 |
| `stale_exit_duration_seconds` | BIGINT | 是 | 从原预期卖出bar到恢复bar结束的陈旧秒数 |
| `net_return` | DECIMAL(18,10) | 是 | 扣费后批次净收益 |
| `sell_proceeds` | NUMERIC(24,2) | 是 | 扣费后卖出所得 |
| `cooldown_until` | TIMESTAMP | 是 | 关闭后冷却截止时间 |
| `reset_observed` | BOOLEAN | 否 | 是否已观察到买入条件复位 |
| `cancel_reason` | VARCHAR(64) | 是 | 候选取消原因 |
| `deleted/create_time/update_time` | 通用字段 | 否 | 逻辑删除和审计字段 |

约束与索引：

```text
唯一：batch_no WHERE deleted=0
部分唯一：正式组合中(stocks_id)只能存在一个活跃批次，
  条件 ledger_type='FORMAL'
  AND batch_status IN ('ENTRY_PENDING','OPEN','DATA_STALE','EXIT_PENDING','DATA_STALE_EXIT')
  AND deleted=0
部分唯一：正式活跃批次的slot_id唯一
部分唯一：候选影子活跃批次中(stocks_id)只能存在一个，条件 ledger_type='SHADOW_FORMAL_CANDIDATE' AND batch_status IN ('ENTRY_PENDING','OPEN','DATA_STALE','EXIT_PENDING','DATA_STALE_EXIT') AND deleted=0
部分唯一：候选影子活跃批次的slot_id唯一，条件同上且slot_id IS NOT NULL
索引：(ledger_type, batch_status, signal_time)
索引：(stocks_id, ledger_type, batch_status)
索引：(expected_entry_bar_time, batch_status)
索引：(expected_exit_bar_time, batch_status)
检查：价格和资金非负；quantity为空或大于0；已OPEN必须存在entry字段
检查：stale_exit_duration_seconds为空或大于等于0
检查：admin_close_reason='DATA_STALE_EXIT_RECOVERY_CLOSE'时，original_exit_reason、expected_exit_bar_time、
      recovery_bar_start_time、recovery_bar_end_time、stale_exit_duration_seconds、exit_time、exit_reference_price均非空
检查：recovery_bar_end_time为空，或同时满足recovery_bar_start_time非空、end>start且exit_time=end
```

### 9.10 `torn_stock_batch_mark`

用途：保存批次每轮持仓路径，用于状态恢复、对账和动态卖出研究。

| 字段 | 类型 | 空值 | 说明 |
|---|---|---:|---|
| `id` | BIGINT | 否 | 主键ID |
| `batch_id` | BIGINT | 否 | 批次ID |
| `round_time` | TIMESTAMP | 否 | 评估轮次 |
| `reference_price` | DECIMAL(18,6) | 否 | 当前bar最后实际价格 |
| `current_net_return` | DECIMAL(18,10) | 否 | 当前扣费后净收益 |
| `peak_price/trough_price` | DECIMAL(18,6) | 否 | 截至本轮峰值和谷值 |
| `mfe/mae/peak_drawdown` | DECIMAL(18,10) | 否 | 截至本轮路径指标 |
| `formal_decision` | VARCHAR(32) | 否 | 正式持有/退出决定 |
| `formal_reason` | VARCHAR(64) | 是 | 正式决定原因编码 |
| `dynamic_shadow_decision` | VARCHAR(32) | 是 | 动态卖出影子决定 |
| `dynamic_shadow_reason` | VARCHAR(64) | 是 | 动态影子原因编码 |
| `feature_snapshot` | JSONB | 否 | 本轮退出判断特征快照 |
| `deleted/create_time/update_time` | 通用字段 | 否 | 逻辑删除和审计字段 |

约束与索引：

```text
唯一：(batch_id, round_time) WHERE deleted=0
索引：(round_time, formal_decision)
索引：(batch_id, round_time DESC)
```

### 9.11 `torn_stock_notice_audit`

用途：保存正式买卖和每日摘要的中文消息快照及本期一次发送结果。它是最小审计表，不是高可用Outbox实现。

| 字段 | 类型 | 空值 | 说明 |
|---|---|---:|---|
| `id` | BIGINT | 否 | 主键ID |
| `notice_no` | VARCHAR(48) | 否 | 通知编号 |
| `batch_id` | BIGINT | 是 | 买卖通知关联批次；每日摘要为空 |
| `notice_type` | VARCHAR(32) | 否 | `BUY/SELL/DAILY_SUMMARY` |
| `scheduled_round_time` | TIMESTAMP | 是 | 买卖通知来源轮次 |
| `summary_date` | DATE | 是 | 每日摘要对应日期 |
| `group_id` | BIGINT | 否 | 发送目标，使用当前`vipGroupId`快照 |
| `payload_hash` | VARCHAR(64) | 否 | 最终完整payload规范化JSON的SHA-256摘要 |
| `payload_snapshot` | JSONB | 否 | 不可丢失的业务字段、最终中文文本和冻结时间的完整快照 |
| `send_status` | VARCHAR(16) | 否 | `PENDING/SENT/FAILED` |
| `send_attempt_count` | INT | 否 | 本期固定最多1次，保留扩展字段 |
| `attempted_at` | TIMESTAMP | 是 | 调用Bot时间 |
| `sent_at` | TIMESTAMP | 是 | 当前调用判定成功时间 |
| `error_message` | VARCHAR(1000) | 是 | 调用异常摘要，不含敏感信息 |
| `message_rule_version` | VARCHAR(32) | 否 | 中文模板和合并规则版本 |
| `deleted/create_time/update_time` | 通用字段 | 否 | 逻辑删除和审计字段 |

约束与索引：

```text
唯一：notice_no WHERE deleted=0
部分唯一：(batch_id, notice_type) WHERE batch_id IS NOT NULL AND deleted=0
部分唯一：(summary_date, notice_type) WHERE notice_type='DAILY_SUMMARY' AND deleted=0
索引：(send_status, create_time)
检查：send_attempt_count BETWEEN 0 AND 1（首期）
检查：BUY/SELL必须有batch_id；DAILY_SUMMARY必须有summary_date
```

通知payload采用“创建时业务事实 + 发送前最终文本”的单一完整JSON，不拆成两份互相覆盖的快照。

“规范化JSON”冻结为以下确定性算法，避免JSONB重排对象键后无法复核哈希：

```text
1. 将payload解析为JsonNode
2. Object字段按UTF-8字典序递归排序
3. Array保持业务顺序，不排序
4. 所有JSON数值转为`BigDecimal`精确十进制语义，按`stripTrailingZeros()`归一并以plain十进制唯一输出；例如`1.00 → 1`、`1e+2 → 100`；时间字段预先使用ISO-8601字符串
5. 不输出空白和换行
6. SHA-256(canonicalJson UTF-8 bytes)，输出64位小写十六进制
```

`StockNoticePayloadCanonicalizer`统一用于创建payload、发送前合并和审计复核，禁止各处自行拼接JSON。数据库读取`payload_snapshot`后重新规范化，必须得到相同hash。

BUY最少字段：

```text
noticeType/batchId/batchNo/stocksId/stocksShortname
entryReferencePrice/quantity/investedCash/slotNo
buyRuleVersion/messageRuleVersion
messageText/frozenAt
```

普通SELL最少字段：

```text
noticeType/batchId/batchNo/stocksId/stocksShortname
entryReferencePrice/exitReferencePrice/quantity/sellProceeds/netReturn
exitReason/formalReason/exitTime
sellRuleVersion/messageRuleVersion
messageText/frozenAt
```

灾难关闭SELL还必须包含：

```text
formalReason = SELL_DATA_ADMIN_CLOSE
originalExitReason
adminCloseReason = DATA_STALE_EXIT_RECOVERY_CLOSE
expectedExitBarTime
recoveryBarStartTime/recoveryBarEndTime
staleExitDurationSeconds
```

发送前不得用仅含`messageText/frozenAt`的新JSON覆盖原payload。`payloadHash`必须在合并完成后基于最终完整payload计算；同一合并消息中的每条通知保留各自业务字段，但可共享相同`messageText/frozenAt`。

实现细则：

- `StockNoticeSendService`对每个`ComposedMessage.noticeIds`找到对应通知对象，分别解析各自原`payloadSnapshot`；
- 使用Jackson `ObjectNode`或`LinkedHashMap<String,Object>`复制原字段，再写入`messageText/frozenAt`，不得新建空Map后只写两个字段；
- 对每条通知分别生成最终JSON和hash；因此DAO/Mapper改为接收`List<NoticePayloadFinalizeCommand>`逐条批量更新，不能继续用一份payload覆盖一组通知；
- `NoticePayloadFinalizeCommand`至少包含`noticeId/payloadSnapshot/payloadHash/attemptedAt`，可用record并补齐Javadoc；
- 最终payload冻结成功后才调用Bot。冻结UPDATE影响行数必须等于通知数，否则停止本条合并消息发送并将错误上抛/记录为FAILED，禁止发送不可审计消息；
- 若PENDING通知的已持久化payload同时包含有效`messageText/frozenAt`，视为已冻结：重启后按原冻结文本分组直接发送，不重新组合，不调用`finalizePayload`，不得覆盖`payloadSnapshot/payloadHash`或payload内`frozenAt`；
- 本期仍不建设自动重试，`sendAttemptCount`只在实际Bot调用结果落库时增加，不在单纯冻结payload时增加。

### 9.12 表关系

```text
torn_stock_market_bar_15m
    1 → 1 torn_stock_strategy_feature_15m（按股票、bar和版本逻辑关联）

torn_stock_market_round
    1 → N torn_stock_signal_event

torn_stock_signal_event
    1 → 0..1 正式批次
    1 → 0..1 无限资金影子批次
    1 → 0..1 拒绝观察批次

torn_stock_portfolio_slot
    1 → 0..N 历史正式批次
    1 → 0..1 当前正式活跃批次

torn_stock_virtual_batch
    1 → N torn_stock_batch_mark
    1 → 0..2 torn_stock_notice_audit（买入、卖出）
```

### 9.13 数据保留

- 15分钟bar、正式特征、月度状态、信号事件、批次和路径属于长期研究依据，不自动删除。
- 通知审计保留中文消息快照，便于批次核对，不自动删除。
- JSONB快照只保存决策必要字段，禁止保存无界日志、完整分钟序列或敏感配置。
- 如未来数据量要求归档，按自然月分区或离线归档另立专题；首期35支股票无需提前分区。

---

## 10. 单轮处理顺序与事务边界

### 10.1 事务外快照

按轮次一次批量加载：

- 本轮全部股票15分钟bar；
- 本轮全部股票正式特征；
- 当月已确认月度状态；
- 所有正式活跃批次；
- 所有相关信号边沿状态；
- 正式槽位状态。

禁止循环逐股票查询Mapper。

### 10.2 纯计算

事务外完成：

1. 判断bar和特征是否可用于买入；
2. 计算三个买入策略和质量分；
3. 计算资格结果和拒绝原因；
4. 生成false→true候选；
5. 对开放批次计算本轮路径和退出候选；
6. 计算动态卖出、高风险和拒绝机会影子结果；
7. 按冻结规则形成候选排序草案。

### 10.3 短事务

`StockRoundTransactionService`按固定顺序执行：

1. 锁定本轮记录，确认尚未完成；
2. 锁定5个正式槽位和待变化的正式批次；
3. 使用本轮bar处理上一轮待买参考成交；
4. 使用本轮bar处理`EXIT_PENDING`及`DATA_STALE_EXIT`；正式关闭前严格校验批次—槽位绑定、数量、资金和审计输入，成功后原子结算并释放槽位；
5. 更新开放批次峰值、谷值、MFE、MAE、回撤和逐轮mark；
6. 将本轮正式退出候选置为`EXIT_PENDING`；
7. 重新校验同股、冷却、复位、月度状态和槽位；
8. 按`qualityScore DESC → stocksId ASC`接纳正式买入候选并预留槽位；
9. 写入原始信号、无限资金影子和拒绝观察；
10. 为已实际参考成交的正式买入/卖出写入中文通知审计`PENDING`；
11. 更新轮次为`COMPLETED`；
12. 提交事务。

NapCat调用不进入数据库事务。

锁顺序固定为：

```text
marketRound
→ 5个portfolioSlot按slotNo ASC
→ 正式活跃batch按id ASC
→ 影子活跃batch按id ASC
```

正式卖出与灾难关闭不得使用事务外槽位或批次对象完成结算；所有校验、金额计算和状态更新必须基于锁后对象。`IllegalStateException`、唯一约束、通知审计写入失败或批量保存失败均向上抛出，轮次保持可重试失败，不允许提交部分资金状态。

### 10.4 事务提交后

- 查询本次新建的`PENDING`通知；
- 按风险卖出、其他卖出、买入的顺序构建群消息；
- 同轮同类型最多展示3个动作，超过时拆为续报；
- 对每条通知读取创建时`payload_snapshot`，合并最终`messageText/frozenAt`，保留全部业务字段并重新计算完整payload哈希；
- 调用现有`Bot.sendRequest(..., String.class)`一次；
- 正常返回更新`SENT`，异常或返回`null`更新`FAILED`；
- 本期不自动重试。

---

## 11. 调度设计

### 11.1 bar与特征补偿调度

当前实现每分钟第10秒执行；第九轮保持该Cron不变：

```text
检查已经结束但未构建的15分钟桶
→ 按bar_start_time升序构建bar
→ 构建对应特征
→ 标记轮次READY
→ 依次处理READY轮次
```

不固定假设每个15分钟边界时特征已经完成。

### 11.2 防重入

- 单实例使用`AtomicBoolean.compareAndSet(false, true)`；
- `finally`释放防重入标记；
- `torn_stock_market_round.round_time`部分唯一索引提供数据库最终幂等；
- 不引入Redis锁、ShedLock或新依赖。

### 11.3 启动补偿

应用启动后先独立验证5槽完整性。存在轮次构建或拒绝观察义务时，启动入口必须与定时入口复用同一JVM内`AtomicBoolean`，并严格按依赖顺序执行：

```text
compareAndSet(false, true)成功
→ 读取最后一个COMPLETED轮次，从下一15分钟桶补算至当前已经结束桶
→ processPendingRounds按bar、特征、轮次顺序处理非完成轮次
→ 证据补齐后计算/重算当月DRAFT并执行明确确认入口
→ resolveAllDueObservations结算到期拒绝观察
→ finally释放processing
```

禁止在历史bar/feature补建之前先创建不完整月度DRAFT。若部署采用分阶段冷启动，也必须保持`NEW_ENTRY=false`，直到历史补建、月度重算及确认全部完成。

约束：

- 历史重建失败不阻断待处理轮次尝试；单个待处理轮次失败转`FAILED_RETRYABLE`且不阻断后续轮次；
- `ENTRY_PENDING`恢复时重新检查`staleAt`，晚于`staleAt`不补发买入；已完成轮次不重复执行；
- 拒绝观察的`resolvedAt`不可逆；必须在可补理论入场bar已经尝试构建后，才可写`NO_THEORETICAL_ENTRY`；
- 抢占失败说明已有轮次流程在执行，启动补偿不得并发处理轮次或拒绝观察；PENDING通知投递仍可由正式消息开关独立处理。

### 11.4 每日摘要

Cron：

```text
0 30 8 * * *
zone = Asia/Shanghai
```

摘要日期为发送日前一自然日，并附带发送时点的当前正式组合快照。摘要写入通知审计后调用现有Bot一次。`VIP_STOCK_DAILY_SUMMARY_ENABLED`是独立群消息开关，不受正式BUY/SELL消息开关控制，开启即会直接发群，必须单独审批。

动态SELL公式未冻结期间，日报不得显示“动态SELL影子建议：0个/N个”，因为`NOT_EVALUATED`不是规则已评估但未命中。可选择完全不展示，或仅展示：规则未冻结、建议未启用、研究输入覆盖率和缺失率。

---

## 12. 中文消息设计

### 12.1 基本原则

- 数据库和Java枚举保留稳定英文编码；
- 所有面向VIP群的策略、风格、成熟度、风险、状态、关闭类型和原因必须转换为中文；
- 禁止直接输出`DEEP_MEAN_REVERSION_BUY`、`RANGING`、`M2_PROVISIONAL`、`MEDIUM`等编码；
- 中文映射由统一枚举方法或消息字典提供，禁止模板中散落翻译；
- 原因文案解释业务含义，不只翻译代码。

### 12.2 中文映射

#### 买入策略

| 内部编码 | 中文展示 |
|---|---|
| `DEEP_MEAN_REVERSION_BUY` | 深度均值回归 |
| `RANGE_LOWER_BUY` | 区间下沿买入 |
| `STRICT_REBOUND_CONFIRM_BUY` | 严格反弹确认 |

#### 股票风格

| 内部编码 | 中文展示 |
|---|---|
| `DECLINER` | 持续下行 |
| `WEAK` | 弱势 |
| `NARROW` | 窄幅震荡 |
| `RANGING` | 区间震荡 |
| `STEADY` | 稳健 |
| `STRONG` | 强势 |

#### 成熟度

| 内部编码 | 中文展示 |
|---|---|
| `M0_UNMATURE` | 未成熟 |
| `M1_EARLY` | 早期 |
| `M2_PROVISIONAL` | 暂定 |
| `M3_SEASONED` | 较成熟 |
| `M4_MATURE` | 成熟 |

#### 风险等级

| 内部编码 | 中文展示 |
|---|---|
| `NONE` | 暂无明显风险 |
| `MEDIUM` | 中等风险 |
| `HIGH` | 高风险 |

#### 关闭类型

| 内部编码 | 中文展示 |
|---|---|
| `CLOSED_TARGET` | 达到目标收益 |
| `CLOSED_RANGE` | 区间恢复退出 |
| `CLOSED_RISK` | 风险退出 |
| `CLOSED_TIME` | 达到最长持有时间 |
| `CLOSED_DYNAMIC` | 动态收益保护退出 |
| `CLOSED_ROTATION` | 盈利换仓退出 |
| `ADMIN_CLOSED` | 系统管理关闭 |

### 12.3 买入消息

```text
【VIP股票买入｜批次 B20260724-001】

股票：TCC
买入策略：区间下沿买入
系统参考买价：$123.45

股票风格：区间震荡
成熟度：暂定（可用于策略适配，但历史尚不足一年）
风险等级：中等风险
建议跟随截止：2026-07-24 16:30
最高建议跟随价：$123.64
当前组合槽位：3 / 5

本消息属于系统虚拟组合，系统不记录个人持仓。
超过跟随时间或最高建议跟随价后不建议追入。
```

`followUntil`以买入消息实际调用发送时间加60分钟计算；`followMaxPrice = entryReferencePrice × 1.0015`。

### 12.4 卖出消息

```text
【VIP股票卖出｜批次 B20260724-001】

股票：TCC
原买入策略：区间下沿买入
系统参考买价：$123.45
系统参考卖价：$124.56
扣除0.1%卖出费后净收益：+0.80%
系统持有时间：4天8小时
关闭原因：区间恢复退出

本卖出仅对应批次 B20260724-001。
未跟随该批次买入的成员无需操作。
```

要求：

- 风险退出不能称为止盈；
- 时间退出必须说明只是系统结束该批次；
- 管理关闭或研究期末清算不能伪装为普通策略卖出；
- 卖出消息必须携带原买入批次号。

#### 数据异常关闭消息

`ADMIN_CLOSED + SELL_DATA_ADMIN_CLOSE`必须使用独立模板，不进入普通目标/区间/风险/时间文案：

```text
【系统虚拟组合｜数据异常关闭】#B20260724-001

股票：TCC
原买入策略：区间下沿买入
原退出原因：达到目标收益
原预期卖出bar：2026-07-24 16:15
恢复参考bar：2026-07-24 16:45～17:00
数据陈旧持续：30分钟
系统参考买价：$123.45
系统异常关闭参考价：$124.10
扣除0.1%卖出费后净收益：+0.43%

本次为系统风险/管理关闭，不代表原策略在该价格准时成交。
本关闭仅对应批次 B20260724-001；未跟随原BUY者无需操作。
```

消息必须由批次持久化审计字段生成，不允许从日志推断；`formalReason=SELL_DATA_ADMIN_CLOSE`只存在于结构化payload，不直接向用户展示英文编码。

### 12.5 每日摘要

每天08:30发送。日报生成时点记为`summaryGeneratedAt`，开放正式仓位的估值行情最大允许滞后30分钟，按实际bar的`barEndTime`计算：

```text
summaryGeneratedAt - barEndTime <= 30分钟
```

等于30分钟可用，超过30分钟视为缺失。bar还必须可用、价格为正且构建版本匹配。不同股票可使用不同的最新合格bar，`priceAsOf`取实际参与估值bar中最早的`barEndTime`，不得写目标桶时间冒充实际价格时点。

正式组合权益遵循全量可计算原则：任一开放正式仓位缺少满足上述要求的实际行情时，`equity=null`且显示“暂无法计算（行情数据不足）”；同时列出缺失股票和可用现金/预留资金。禁止回退投入成本或把部分权益展示为完整权益。

```text
【VIP股票组合日报｜2026-07-23】

正式组合
- 当前占用槽位：3 / 5
- 当前组合权益：暂无法计算（行情数据不足）
- 缺失行情：TCC、MUN
- 可用现金及预留资金：...
- 昨日买入：2批
- 昨日卖出：1批
- 昨日已实现净收益：...
- 当前开放批次：TCC、MUN、EVL
- 数据陈旧批次：0

影子研究
- 5槽正式候选影子组合：占用3 / 5，昨日买入2批，昨日卖出1批
- 无限资金影子新批次：5个
- 满仓拒绝：2个
- 风格/趋势拒绝：1个
- 动态SELL研究：规则未冻结，建议未启用
- 动态SELL输入覆盖率：98.5%，缺失输入批次：1
- 高风险观察：2个

提示：影子数据仅用于策略研究，不代表正式操作建议。
```

摘要中的策略、风格、风险和关闭原因同样必须中文化。

### 12.6 消息降噪

- 每15分钟评估；
- 同轮买入和卖出分别合并；
- 每条最多展示3个动作；
- 风险卖出优先于其他卖出，卖出优先于买入；
- 超过上限时拆分续报，禁止删除卖出；
- 开放期间不重复播报买入；
- 目标平均实际消息不超过4条/日，P95不超过6条/日。

---

## 13. 包结构与预计文件

建议新增业务包：

```text
src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/
├── VipStockAlertScheduler.java
├── Stock15mBarBuildService.java
├── Stock15mFeatureBuildService.java
├── StockMarketRoundLoader.java
├── StockRoundTransactionService.java
├── StockPortfolioService.java
├── StockBuySignalService.java
├── StockEligibilityService.java
├── StockBatchExitService.java
├── StockShadowService.java
├── StockDailySummaryService.java
├── buy/
│   ├── StockBuyStrategy.java
│   ├── DeepMeanReversionBuyStrategy.java
│   ├── RangeLowerBuyStrategy.java
│   └── StrictReboundConfirmBuyStrategy.java
├── policy/
│   ├── StockCandidateRankingPolicy.java
│   ├── StockExecutionPolicy.java
│   └── StockBarQualityPolicy.java
└── notice/
    ├── StockNoticeComposeService.java
    └── StockNoticeSendService.java
```

持久层：

```text
repository/model/torn/stocks/portfolio/
repository/dao/torn/stocks/portfolio/
repository/mapper/torn/stocks/portfolio/
resources/mapper/torn/stocks/portfolio/
```

> **历史第九轮文件清单和迁移指令（已废止，不得执行）。** 其中“向master追加include”“不得修改`stocks-portfolio.yaml`已执行changeSet”等说法基于当时假定的已部署环境，和当前第22.8节的“第一批已部署、第二批未部署”基线不一致。`stocks-portfolio.yaml`已由master include，开发人员不得重复追加include。当前需要修改的精确文件、测试与Liquibase策略仅以一次性第三批第一轮实施方案和第22节为准。

现有文件原则上只做必要改动：

- `SettingConstants.java`：维护总开关、新买入开关、规则模式、正式消息和日报开关；
- 不增加新群配置，继续读取`ProjectProperty.vipGroupId`；
- 原`StockTradeStrategyService`和`VipStocksStrategyImpl`保持行为不变；
- `TornStocksManager`不接入组合状态机。

---

## 14. 配置设计

使用`sys_setting`增加：

| Key | 默认值 | 含义 |
|---|---|---|
| `VIP_STOCK_ALERT_ENABLED` | `false` | 是否启用轮次数据构建和存量批次生命周期管理；已有活跃批次时不得因该值关闭而停止管理，见兼容规则 |
| `VIP_STOCK_NEW_ENTRY_ENABLED` | `false` | 是否允许创建新的正式/候选影子批次；紧急回滚时关闭此项即可停止新买入 |
| `VIP_STOCK_FORMAL_NOTICE_ENABLED` | `false` | 是否向VIP群发送正式买卖消息 |
| `VIP_STOCK_DAILY_SUMMARY_ENABLED` | `false` | 是否发送08:30日报 |
| `VIP_STOCK_RULE_MODE` | `SHADOW` | `OFF/SHADOW/PROVISIONAL/FORMAL` |

首期部署流程：

```text
先建表和代码，四个布尔开关=false，RULE_MODE=SHADOW
→ 仅开启ALERT，NEW_ENTRY=false
→ 历史bar/feature补建、月度DRAFT重算和明确确认
→ 连续验证至少3个新结束桶持续完成
→ 开启NEW_ENTRY，开始5槽正式候选Shadow + UNLIMITED_SHADOW
→ 正式批次和正式通知保持0，连续验证至少20个自然日
→ 完成长窗口双资金轨道回放、状态/资金/消息门禁
→ 单独审批后切换PROVISIONAL
→ 正式消息链验收后最后单独开启FORMAL_NOTICE
→ 更长期前向证据和再次审批后才考虑FORMAL
```

Shadow模式必须同时维护5槽“正式候选影子组合”和无限资金影子。前者复用正式排序、5槽、每槽20亿、整数股数、余款、ENTRY、SELL、冷却和复位，但使用独立研究账本，不占用正式槽位、不写正式通知；后者完整保留全部可接纳信号。只有无限资金影子不能作为20天Shadow组合证据。

### 14.1 开关判定矩阵

| 场景 | 数据构建 | 管理存量批次 | 新建批次 | 写通知审计 | 发送正式消息 |
|---|---:|---:|---:|---:|---:|
| 无活跃批次，`ALERT=false` | 否 | 不适用 | 否 | 否 | 仅发送历史PENDING时由正式消息开关决定 |
| 有活跃批次，`ALERT=false` | 只构建完成存量管理所需轮次 | 是 | 否 | 是 | 由正式消息开关决定 |
| `ALERT=true, NEW_ENTRY=false` | 是 | 是 | 否 | 是 | 由正式消息开关决定 |
| `ALERT=true, NEW_ENTRY=true, RULE_MODE=SHADOW` | 是 | 是 | 新建5槽正式候选Shadow、无限资金Shadow和拒绝观察；正式批次为0 | 否（正式通知审计为0） | 否 |
| `ALERT=true, NEW_ENTRY=true, RULE_MODE=PROVISIONAL/FORMAL` | 是 | 是 | 经独立审批后创建正式批次，同时保留研究轨道 | 是 | 由正式消息开关决定 |

兼容现有部署：新增`VIP_STOCK_NEW_ENTRY_ENABLED`后，若配置缺失必须按`false`处理，不能从旧总开关推导为true。调度器入口不得只因`VIP_STOCK_ALERT_ENABLED=false`直接返回；应先批量查询是否存在活跃批次或PENDING通知。有存量批次时继续数据构建、退出、恢复、结算和通知审计，但候选评估与新批次接纳必须关闭。

`VIP_STOCK_RULE_MODE=OFF`只禁止买入研究事件、Shadow新批次和正式接纳，不阻断已存在批次的路径更新、退出成交、灾难关闭、冷却和通知审计。

实现采用低侵入方式：

- `TornStockVirtualBatchDAO`新增`existsActiveBatches()`，SQL使用`SELECT EXISTS(...)`同时检查正式和无限资金影子活跃状态，禁止先加载全量列表只为判断存在性；
- `TornStockSignalEventDAO`新增`existsPendingRejectedObservationEvents()`，检查`portfolio_decision='REJECTED' AND resolved_at IS NULL`且存在`REJECTED_OBSERVATION`批次；拒绝观察批次本身是`CANCELLED`，不能依赖活跃批次查询发现；
- `TornStockNoticeAuditDAO`新增`existsPendingNotices()`，SQL使用`SELECT EXISTS(...)`；
- 新增`StockAlertRuntimeGate`纯服务，集中返回`shouldBuildRounds/manageExistingBatches/manageResearchObligations/allowNewEntry/shouldSendPendingNotices`，定时入口与启动补偿必须复用；
- `StockRoundTransactionService`新增`allowNewEntry`参数或上下文；无论值为何都执行ENTRY/EXIT结算和存量路径管理，仅在买入信号评估、事件/影子创建、候选接纳和买入边沿推进阶段应用该开关；
- 不新增分布式锁、不改变现有`AtomicBoolean`单实例防重入。

调度器每次触发时按以下顺序执行，确保历史PENDING通知不被轮次开关阻断：

```text
读取RuntimeGate
→ shouldBuildRounds=true时抢占processing并处理轮次
→ manageResearchObligations=true时结算到期拒绝观察
→ 无论是否构建轮次，只要shouldSendPendingNotices=true就调用sendPendingNotices
```

启动补偿使用相同顺序。只要存在未结算拒绝观察，必须继续构建其14天观察窗口所需bar并执行`resolveAllDueObservations`，即使没有正式/无限资金影子活跃批次且新买入已关闭。通知发送仍受`VIP_STOCK_FORMAL_NOTICE_ENABLED`控制，但不受`VIP_STOCK_ALERT_ENABLED`和`VIP_STOCK_NEW_ENTRY_ENABLED`控制。

---

## 15. 性能设计

### 15.1 数据规模

当前股票35支，每日理论15分钟桶：

```text
35 × 96 = 3,360行/日
```

bar和特征年增量约各123万行，PostgreSQL普通表和复合索引足以支持，首期不需要分区。

### 15.2 查询要求

- 每轮按全市场批量读取，禁止35次单股查询；
- 月度状态一次按`stocks_id IN (...)`查询；
- 活跃批次、信号状态和槽位一次加载；
- 批量写bar、特征、信号事件和mark；
- 高频条件使用普通列，不在高频SQL中解析JSONB；
- 最新特征查询依赖`(stocks_id, bar_start_time DESC)`索引；
- 事务外计算，事务内只做复核和状态写入。

### 15.3 并发

- 单实例，不引入分布式锁；
- 调度器JVM内防重入；
- 正式槽位和活跃批次使用数据库行锁；
- 部分唯一索引作为同股和槽位超卖的最终保护；
- 发生唯一冲突时按业务竞争失败处理，记录拒绝原因，禁止吞异常。

---

## 16. 测试方案

### 16.1 bar构建

- Asia/Shanghai桶边界；
- 10个样本可用、9个样本不可用；
- 桶尾5分钟边界；
- 最后采样恰好`bucketEnd-5m`可用；
- 重复分钟记录按最大ID保留；
- 桶首缺失但样本和桶尾满足时可用；
- 不连续桶不能成交；
- 重启补算不要求重新积累30日窗口。

### 16.2 特征

- 只使用当前及历史bar；
- `return6h/1d/7d/14d`边界；
- 30日高低、带宽和位置；
- `high30 == low30`时`position30`为空；
- 缺口后`strategyReady=false`；
- 与冻结历史样本随机抽样复算一致。

### 16.3 买入策略

- 三个策略所有阈值的等于、略高和略低边界；
- `NARROW`正确应用Z值0.6修正；
- `WEAK/DECLINER`只允许严格反弹确认；
- 风格缺失/过期fail-closed；
- 同股多策略只建一个候选；
- 质量分公式准确；
- 排序严格为质量分降序、股票ID升序。

### 16.4 入场

- 仅紧邻下一桶成交；
- 向上偏离恰好0.15%不取消；
- 大于0.15%取消；
- 向下偏离不取消；
- 信号桶开始后35分钟过期；
- 晚恢复不补发买入；
- 取消后完整释放槽位和预留资金。

### 16.5 资金与槽位

- 初始化5槽、每槽20亿；
- 整数股数和余款；
- 卖出费0.1%；
- 卖出所得返回原槽；
- 槽内复利；
- 同时竞争不会超过5槽；
- 同股只能一个正式活跃批次；
- `EXIT_PENDING`和`DATA_STALE`继续占槽。

### 16.6 退出

- 净收益恰好+0.8%触发目标退出；
- 净收益恰好-1.5%触发风险退出；
- 持有恰好14天触发时间退出；
- 区间策略`netReturn > 0`且`position30 >= 0.60`触发；
- `netReturn == 0`不触发区间退出；
- 非RANGE批次缺少区间特征仍可正常评估目标/风险/时间并产生HOLD；
- RANGE批次先评估目标/风险/时间；前三项均未命中后，缺少`position30/low30d/high30d`或`high30d <= low30d`必须返回不可评估、转`DATA_STALE`且不生成HOLD mark；
- 裸连续下跌不触发独立卖出；
- 卖出只能关闭原买入批次。

### 16.7 状态机和幂等

- 轮次重复执行不重复建批次；
- 信号持续为true不重复建事件；
- 冷却结束但未复位不重入；
- 正常关闭冷却24小时；
- 风险关闭冷却48小时；
- 灾难关闭冷却48小时且`resetObserved=false`；
- `DATA_STALE_EXIT`无恢复bar时保持活跃和占槽；首个恢复可用bar才允许`ADMIN_CLOSED`；
- 部分唯一索引阻止同股多活跃批次；
- 批次mark同轮不重复；
- 通知审计同批次同类型不重复。

### 16.8 中文消息

- 所有策略、风格、成熟度、风险和关闭类型输出中文；
- 消息中不存在内部英文枚举编码；
- 买入包含批次、参考价、跟随时间、最高价和槽位；
- 卖出包含原批次、买卖价、扣费后收益、持有时间和关闭原因；
- 风险退出不使用“止盈”；
- 每日摘要包含正式与Shadow；
- 同轮超过3个动作正确拆分；
- 卖出不会因消息预算被删除。

### 16.9 灾难关闭与通知审计

- 完整FORMAL批次、OCCUPIED/STALE槽位和`currentBatchId`绑定成功时，精确断言`quantity × recoveryPrice × 0.999`、最终`availableCash`、槽位解绑和`ADMIN_CLOSED`；
- 正式批次缺少`ledgerType/slotId/slotNo/quantity/entryReferencePrice/expectedExitBarTime`任一字段时抛异常并回滚；
- 槽位不存在、slotNo不一致、`currentBatchId != batch.id`、状态非法、`reservedCash != 0`或余款不一致时抛异常并回滚；
- 影子批次显式`UNLIMITED_SHADOW`，只计算理论收益，不读取/修改正式槽位，不创建正式通知；
- 恢复bar不可用、版本不符、价格非正或时间字段缺失时保持`DATA_STALE_EXIT`；
- 持久化并精确断言`originalExitReason/adminCloseReason/recoveryBarStart/End/staleExitDurationSeconds`；
- 使用真实`StockBatchExitService + StockBatchPathService`验证RANGE缺特征转`DATA_STALE`，禁止mock不可评估结果代替生产链；
- 覆盖`锁后5槽 + DATA_STALE_EXIT → 资金结算 → 冷却 → 批次/槽位保存 → SELL通知审计 → 轮次完成`生产编排链；
- 在通知审计保存、批量Batch保存和轮次完成位置分别注入异常，验证批次、槽位、冷却和通知全部回滚；
- 捕获`finalizePayload`参数并解析JSON，验证创建时业务字段全部保留，新增`messageText/frozenAt`，`payloadHash = sha256(最终完整JSON)`；
- 灾难关闭最终中文文本使用精确快照断言，不得只用`contains`验证局部片段。

### 16.10 数据库

- Liquibase YAML语法；
- 每张表和每列均有中文remarks；
- 部分唯一索引在PostgreSQL 17.5可执行；
- 使用隔离PostgreSQL数据库或隔离schema真实执行changelog；
- 读取`obj_description/col_description`核对注释；
- 执行当前生产Mapper/DAO，验证新增管理关闭列、JSONB最终payload、正式资金回笼和事务回滚；
- 留存当前HEAD、完整命令、`SELECT version()`、测试源码和Surefire XML；执行后按用户要求删除临时数据库/schema；
- 所有DO字段Javadoc和表字段语义一致。

### 16.11 开关与存量生命周期

- `ALERT=false`且无活跃批次时不构建新轮次；
- `ALERT=false`但存在`OPEN/DATA_STALE/EXIT_PENDING/DATA_STALE_EXIT`时继续管理并禁止新批次；
- 没有活跃持仓但存在未结算拒绝观察时，继续构建观察所需bar并按14天日历窗口结算；
- `NEW_ENTRY=false`时不产生新正式/Shadow/拒绝观察批次，但存量批次继续退出和结算；
- `RULE_MODE=OFF`不阻断存量批次管理；
- 启动补偿与定时入口使用相同判定；
- 正式消息关闭只阻止发送，不阻止通知审计落库和存量资金结算；
- 历史PENDING通知不依赖轮次总开关，可由正式消息开关独立发送；
- 0行冷启动开启ALERT后能创建第一条已结束桶轮次，追平后连续3个新桶仍持续创建；
- 月度状态必须在历史补建后重算/确认，不完整DRAFT不得永久阻塞；
- SHADOW必须同时产生独立5槽候选组合和无限资金影子，正式槽位/正式批次/正式通知保持0；
- PROVISIONAL与FORMAL不能只作为同一正式入口的两个名称；若业务要求PROVISIONAL为小规模试运行，必须冻结并实现具体规模限制，否则按同等正式资金风险审批。

### 16.12 回放

回放当前已具备小窗口只读机制，但尚未完成P1-2长窗口发布验收。完成态必须包含两个独立资金组合和五个派生研究轨道：

- `FORMAL_20E`：5槽、每槽20亿生产资金口径；
- `FORMAL_4E`：5槽、每槽4亿历史对照；
- 无限资金影子、拒绝观察、动态SELL研究数据、高风险观察、当前Java原始BUY对照。

动态SELL公式未冻结时仅采集研究输入，不形成建议、交易或收益轨道。回放只读生产输入，`runId/portfolioId`仅存在于内存和产物文件，不写业务表、不新增首期回放表。必须复用正式纯领域策略/Policy/计算器，但不得调用写DAO、发送消息或读取系统当前时间的编排Service。

强制输出：

```text
<runId>-summary.json
<runId>-trades.csv
<runId>-rejections.csv
<runId>-equity-curve.csv
```

不要求持久化每bar事件流水；逐bar净值在内存计算并输出净值曲线。失败/中断不续跑中间状态。只有`COMPLETED`的完整四产物可作为不可覆盖完成标识；FAILED诊断产物不得占用后续同请求从头运行的完成目录。输入加载必须是单一`READ ONLY + REPEATABLE READ`快照，摘要固化输入manifest/hash、版本、行数和时间边界后，才能宣称确定性。

`contentSha256/sourceManifestHash`必须覆盖所有实际影响回放判断、成交、路径、权益、审计或产物的输入字段。bar摘要至少纳入：`barStartTime/barEndTime/firstSampleTime/lastSampleTime/firstPrice/lastPrice/lowPrice/highPrice/sampleCount/duplicateCount/tailGapSeconds/usable/qualityReason/buildVersion/sourceMaxHistoryId`。不能只摘要派生`usable`而遗漏`isUsable()`重新读取的`sampleCount/lastSampleTime/barEndTime`。任一行为字段单独变化时hash必须变化，相同完整输入必须稳定复算一致。

回放和生产共用同一bar质量、特征、买入、排序、成交、资金和退出纯领域实现，禁止维护两套逻辑。

---

## 17. 验收门禁

### 17.1 状态和交易硬门禁

```text
孤儿卖出 = 0
重复买入通知 = 0
重复卖出通知 = 0
重复关闭 = 0
数据断层成交 = 0
未复位重入 = 0
同股多正式活跃批次 = 0
槽位超卖 = 0
卖出因消息合并被删除 = 0
跨越非紧邻bar成交 = 0
晚于staleAt补发买入 = 0
正式批次已关闭但槽位未释放 = 0
正式批次关闭但资金未回笼 = 0
灾难关闭缺失完整审计字段 = 0
关闭新买入后存量批次停止管理 = 0
持续轮次追平后停止创建新桶 = 0
月度不完整DRAFT在证据补齐后仍永久不重算 = 0
SHADOW只有无限资金轨道而缺少5槽候选组合 = 0
动态SELL未评估却在日报展示为建议 = 0
```

### 17.2 数据门禁

```text
bar唯一冲突造成重复计算 = 0
特征未来函数 = 0
风格缺失默认稳健 = 0
旧批次被新月度状态覆盖 = 0
回放与生产规则版本不一致 = 0
影响回放行为的输入字段变化但manifest hash不变 = 0
ENTRY在staleAt等值边界与业务契约不一致 = 0
```

### 17.3 消息门禁

```text
正式消息展示内部英文编码 = 0
卖出缺失原买入批次号 = 0
风险退出被称为止盈 = 0
每日摘要缺失Shadow汇总 = 0
最终payload丢失创建时业务字段 = 0
payload_hash与最终完整payload不一致 = 0
灾难关闭使用普通SELL原因码或止盈文案 = 0
平均消息 <= 4条/日
P95消息 <= 6条/日
```

NapCat本期不建设高可用，因此“永久漏发为0”和网络层精确一次不作为本期可证明门禁；通知审计只证明系统生成了唯一通知并执行了一次发送调用。

### 17.4 Shadow升级门禁

升级到`PROVISIONAL`前至少满足：

- 连续运行不少于20个自然日；
- 规则版本冻结；
- 新增月份方向未明显恶化；
- 状态机和数据库硬门禁通过；
- 消息数量门禁通过；
- 每槽20亿回放完成；
- 正式群发布获得单独审批。

---

## 18. 实施阶段与审批点

### 阶段A：Schema和纯领域能力

- 新增Liquibase；
- 新增DO、DAO、Mapper和枚举；
- 实现bar、特征、策略、资金和状态机纯逻辑；
- 所有开关默认关闭；
- 不发送群消息；
- 不运行历史全量重建。

### 阶段B：历史回放与初始化

- 按15分钟规则重建历史bar和特征；
- 初始化月度状态；
- 初始化5个20亿槽位；
- 执行正式、影子和拒绝观察回放；
- 该阶段涉及大量数据库写入，需单独授权。

### 阶段C：生产Shadow

- 启用真实轮次；
- 正式消息关闭；
- 所有Shadow只写数据库；
- 每日摘要是否发送由独立开关控制；
- 连续观察至少20个自然日。

### 阶段D：PROVISIONAL群提醒

- 单独审批后开启正式买卖消息；
- 使用现有`vipGroupId`；
- 保持动态卖出、高风险硬否决和换仓为Shadow；
- 监控状态机、数据质量和消息数量。

### 阶段E：后续专题

以下不与首期捆绑：

- NapCat ACK、自动重试、消息对账和高可用；
- 动态自然卖出正式化；
- 高风险硬否决；
- 盈利换仓；
- 新趋势策略；
- 数据分区或归档；
- 既有分钟特征游标并发治理；
- VIP订阅和群管理可靠性治理。

---

## 19. 风险与回滚

### 19.1 主要风险

| 风险 | 处理方式 |
|---|---|
| 研究资金为4亿、实施资金改为20亿 | 上线前按20亿重新执行精确现金账本回放 |
| 15分钟口径与旧分钟特征混淆 | 新建独立bar和特征表，旧私聊继续使用旧表 |
| 异步采集延迟 | 每分钟检查已结束轮次并顺序补偿，不假设边界时立即就绪 |
| 原始分钟重复 | bar构建按时间确定性去重并记录duplicate_count |
| 服务重启 | 按最后完成轮次补算；过期ENTRY不补发 |
| 同股或槽位竞争 | 行锁复核+PostgreSQL部分唯一索引 |
| 风格缺失 | fail-closed，只记原始信号，不建立正式候选 |
| 数据缺口 | OPEN转DATA_STALE并继续占槽，不伪造成交 |
| DATA_STALE_EXIT长期无价格 | 保持占槽，按小时限频结构化WARN，不使用成本/旧价/零价注销 |
| 正式槽位或批次绑定损坏 | fail-closed并回滚整个轮次，不允许仅关闭批次 |
| 停止新买入 | 关闭`VIP_STOCK_NEW_ENTRY_ENABLED`，存量持仓管理继续运行 |
| 通知最终冻结 | 合并完整业务payload后追加文本并重算hash，禁止覆盖 |
| NapCat失败 | 本期记录FAILED但不自动重试，后续专题治理 |
| 中文映射遗漏 | 统一消息字典和“无英文编码”测试门禁 |

### 19.2 回滚原则

- 所有新表为新增表，不改动现有股票采集和旧建议表结构，回滚侵入性低。
- 功能回滚优先关闭`VIP_STOCK_NEW_ENTRY_ENABLED`和正式消息开关，不删除数据；只在确认不存在活跃批次和PENDING通知后，才允许关闭`VIP_STOCK_ALERT_ENABLED`停止轮次构建。
- 已执行Liquibase changeSet不得修改；修正使用追加changeSet。
- 未经单独确认不删除bar、特征、批次、Shadow或失败构建数据。
- 已发布批次即使关闭新买入，也必须继续管理其卖出生命周期；`VIP_STOCK_NEW_ENTRY_ENABLED=false`是标准回滚入口，`VIP_STOCK_ALERT_ENABLED`不得作为遗弃存量批次的快捷开关。

---

## 20. 历史修复记录与当前基线说明

本节只保留历史修复的可追溯背景，不再把旧编号当作当前开放项或发布结论。历史记录所称“已关闭”只表示当时对应代码差异已处理；当前业务Review新增或重新定性的差异，以第22节和一次性实施方案为准。

- 已实施基线包括：ENTRY使用实际处理时间而不是历史roundTime；拒绝观察结算顺序、正常SELL/灾难关闭账实原子性、通知payload合并与只读回放基础机制。
- 这些已实施基线不等于持续轮次生产、冷启动重算、双Shadow、日报语义、完整manifest或ENTRY等值边界已经闭环。
- 历史测试计数、旧commit和原P编号仅可作为历史辅助证据；不得替代当前HEAD编译、聚焦测试、真实PostgreSQL迁移/事务验证或未来授权后的运行证据。

## 21. 参考资料

- `.ai/knowledge/stocks/vip_stock_virtual_portfolio_strategy.md`
- `.ai/knowledge/stocks/vip_stock_alert_strategy_background.md`
- `.ai/knowledge/stocks/virtual_portfolio_research_evidence.md`
- `.ai/knowledge/stocks/stock_personality_monthly_calibration.md`
- `.ai/knowledge/stocks/stock_personality_full_history_2026_07.md`
- `.ai/knowledge/stocks/data/virtual_portfolio_validation_summary.json`
- `src/main/java/pn/torn/goldeneye/torn/manager/torn/stocks/TornStocksManager.java`
- `src/main/java/pn/torn/goldeneye/torn/manager/torn/stocks/StockFeatureBuildService.java`
- `src/main/java/pn/torn/goldeneye/torn/manager/torn/stocks/StockRollingFeatureEngine.java`
- `src/main/java/pn/torn/goldeneye/torn/service/user/StockTradeStrategyService.java`
- `src/main/java/pn/torn/goldeneye/napcat/strategy/vip/VipStocksStrategyImpl.java`
- `src/main/java/pn/torn/goldeneye/configuration/BotImpl.java`
- `src/main/java/pn/torn/goldeneye/napcat/send/msg/GroupMsgHttpBuilder.java`
- `src/main/java/pn/torn/goldeneye/configuration/property/ProjectProperty.java`

本文是第三批第一轮修复前的永久技术实施基线。本文的设计契约可以完成，但代码、Schema和测试未实施前，相关finding不得关闭。普通工程师必须按`vip_stock_alert_remediation_implementation_plan.md`实施并取得真实验证；在第22节所有P0/P1关闭、长窗口与前向Shadow门禁完成、业务人员单独批准前，不得开启任何股票提醒开关，不得创建正式资金批次或发送正式买卖消息。

---

## 22. 第三批第一轮业务Review后的永久修订契约（2026-08-08）

> 本节覆盖本文中与当前实现矛盾的历史“已具备/仅回归”描述，并作为后续实施的唯一技术基线。一次性任务拆分、命令和具体测试文件见 `vip_stock_alert_remediation_implementation_plan.md`；本节只冻结长期架构、数据、事务、状态与发布契约。

### 22.1 当前差异状态与边界

| 编号 | 当前实现事实 | 永久技术结论 | 状态 |
|---|---|---|---|
| P0-1 | 调度器只查询已有未完成round，历史重建和事务内按需创建均不是持续生产者 | 每个已结束桶必须先幂等生产PENDING再处理 | 未实现 |
| P0-2 | 启动先初始化DRAFT，后重建历史；已有DRAFT会被初始化跳过；自动确认没有入口 | 证据补齐→重算DRAFT→确认的依赖顺序必须落地 | 未实现 |
| P1-1 | 日报按`exitReason contains DYNAMIC`展示“动态卖出影子建议” | 未冻结动态SELL只能表达研究未启用和输入质量 | 未实现 |
| P1-2 | 仅有`UNLIMITED_SHADOW`；SHADOW不做候选接纳 | 5槽候选影子与无限资金影子必须同时维护、物理隔离 | 未实现 |
| P1-3 | bar摘要遗漏质量原始字段 | manifest必须覆盖全部实际行为输入 | 未实现 |
| P2-1 | `actualProcessingTime >= entryStaleAt`取消 | 只有严格晚于才取消 | 未实现 |

### 22.2 持续轮次生产与调度时序

调度使用 `Asia/Shanghai`，每分钟第10秒。`currentEndedBucket` 是当前业务时刻对齐后回退15分钟得到的**最近已结束桶的开始时间**。禁止生产当前桶和未来桶。

```text
runtime gate判定存在构建义务
→ JVM AtomicBoolean compareAndSet(false,true)
→ INSERT PENDING(round_time=currentEndedBucket) ON CONFLICT DO NOTHING
→ SELECT 未完成round（round_time ASC）
→ 对每个round：bar → feature → READY → 短事务编排 → COMPLETED
→ 结算到期拒绝观察
→ 投递已有PENDING通知（独立门禁）
→ finally processing=false
```

`shouldBuildRounds` 必须为：`ALERT=true OR 存在FORMAL/SHADOW_FORMAL_CANDIDATE/UNLIMITED_SHADOW活跃批次 OR 存在未结算拒绝观察`。因此关闭新入场或总开关不能遗弃存量资金、候选影子或观察义务；三者均不存在时不得创建无意义轮次。

轮次唯一性由 `torn_stock_market_round` 的 `(round_time) WHERE deleted=0` 唯一索引保障。插入必须使用 `ON CONFLICT DO NOTHING`，不能把“先SELECT再INSERT”作为并发保护。单实例仅使用JVM防重入，不引入分布式锁。启动补偿只负责从最后COMPLETED之后补齐历史到当前已结束桶；补齐后持续生产必须由定时入口承担。

### 22.3 月度冷启动、重算与确认

启动与跨月补偿固定顺序：

```text
verifyAndInitSlots
→ 获取与定时入口共享的processing标记
→ rebuildFromLastCompleted(currentEndedBucket)
→ processPendingRounds(当前门禁的allowNewEntry；首次启用保持false)
→ calculate/recalculate current-month DRAFT
→ autoConfirmDraftStates(effectiveMonth)
→ resolveAllDueObservations
→ finally release processing
```

- 不能在bar/feature补建前调用普通初始化并把空DRAFT固化。
- 需要区分“缺失记录初始化”和“已有DRAFT重算”两个公共操作；重算必须批量加载证据、前序CONFIRMED状态，禁止N+1。
- 仅可更新 `DRAFT AND manual_override=false`。`CONFIRMED`、`RETIRED`、人工覆盖DRAFT均不可覆盖、不可降级，`confirmedBy/confirmedAt`不可被重算改变。
- 证据仍不完整时DRAFT继续保持空风格/风险并fail-closed；不得默认STEADY/NONE。
- `autoConfirmDraftStates` 必须由生产编排入口调用；仅完整、版本匹配、非人工覆盖状态可写 `confirmedBy=SYSTEM`。人工确认仍须保存真实操作者。
- 若补建失败，自动确认不得发生；已有正式/候选影子存量仍可按各自安全路径管理，但新入场继续关闭。

### 22.4 双Shadow账本

`SHADOW` 不是“不维护组合”，而是禁止正式资金与正式通知。它必须并行维护下列互不污染账本：

| 账本 | ledgerType / portfolioCode | 资金与生命周期 | 禁止事项 |
|---|---|---|---|
| 5槽正式候选影子 | `SHADOW_FORMAL_CANDIDATE` / `VIP_SHADOW_CANDIDATE` | 5槽×20亿；正式排序、接纳、整股数、余款、ENTRY、EXIT、冷却、复位、0.999卖出费 | 不访问`VIP_FORMAL`槽位；不写正式批次/正式通知 |
| 无限资金影子 | `UNLIMITED_SHADOW` / 无slot | 每个可接纳信号一条独立理论路径，不受5槽、同股候选容量限制 | 不用于替代5槽收益、满仓或资金守恒证据 |
| 拒绝观察 | `REJECTED_OBSERVATION` / 无slot | 保留拒绝事实和冻结观察口径 | 不建立正式仓、资金或通知 |

候选影子新增独立五行槽位、独立批次ledger type、同股活跃唯一索引、slot活跃唯一索引，以及 `torn_stock_signal_event.shadow_candidate_batch_id`。现有`shadow_batch_id`继续专属于无限资金影子，不能复用或覆盖。候选影子与正式槽位、现金、批次和通知必须在SQL和领域分支层面隔离。

同轮先执行信号评估和候选排序一次（`qualityScore DESC → stocksId ASC`），然后将同一排序输入两条影子轨道：前5个可接纳候选进入候选影子；其余候选事件写`NO_AVAILABLE_SLOT`，但无限资金影子仍保留全部可接纳信号。不得用PROVISIONAL正式批次收集Shadow证据。

带槽位的两个组合在同一轮事务内锁定顺序固定为 `VIP_FORMAL → VIP_SHADOW_CANDIDATE`，随后锁定各ledger活跃批次。任一事件、批次、槽位、mark写入失败，整轮回滚；不能留下事件引用已写而槽位未预留的半状态。未知/null ledger type fail-closed。

### 22.5 日报与动态SELL研究口径

动态SELL公式冻结前，`dynamicShadowDecision=NOT_EVALUATED` 与 `dynamicShadowReason=DYNAMIC_RULE_NOT_FROZEN` 是“未进行投资判断”，不是“已评估零命中”。日报必须输出：

```text
动态SELL研究：规则未冻结，建议未启用
输入覆盖率：xx%
缺失输入批次数：N
```

覆盖率只能从`TornStockBatchMarkDO`的`dynamicShadowDecision/dynamicShadowReason`计算：摘要日内研究mark中，两字段任一非空者为分母；`NOT_EVALUATED + DYNAMIC_RULE_NOT_FROZEN`为完整输入；其余为缺失输入；分母为0显示“无研究输入”而不是0%。不得由影子批次数、`exitReason`字符串或历史预留编码推导。日报应分别展示候选影子组合的槽位/权益/当日动作和无限资金影子新增路径，禁止相加为单一“Shadow收益”。候选影子权益的行情不足处理与正式组合完全相同：任一开放仓缺少新鲜价格，完整权益为不可用，不得回退成本价。

`VIP_STOCK_DAILY_SUMMARY_ENABLED` 独立于正式BUY/SELL消息开关；开关为true即会向群发送日报，始终需要独立业务审批。

### 22.6 回放输入摘要

`contentSha256` 和 `sourceManifestHash` 是输入代际与产物可归属契约。每条bar按稳定顺序至少摘要：

```text
stocksId, barStartTime, barEndTime,
firstSampleTime, lastSampleTime,
firstPrice, lastPrice, lowPrice, highPrice,
sampleCount, duplicateCount, tailGapSeconds,
usable, qualityReason, buildVersion, sourceMaxHistoryId
```

任何影响`isUsable()`、策略、成交、路径、权益、审计或产物的新增输入字段，必须同步加入摘要与“只改该字段hash变化”的测试。固定字段顺序、稳定null表示和UTF-8编码；不能用不受控JSON或对象`toString()`代替。失败attempt和COMPLETED目录占用模型保持不变。

### 22.7 ENTRY精确过期边界

`actualProcessingTime` 是唯一过期判断时钟，不可退回历史`roundTime`。规则为：

```text
actualProcessingTime <= entryStaleAt → 继续检查紧邻目标bar、可用性、连续性和价格偏离
actualProcessingTime >  entryStaleAt → CANCELLED / ENTRY_DATA_STALE
```

生产结算和隔离回放必须复用该严格比较语义。等于边界并不保证成交，仅说明不得因过期原因取消。

### 22.8 Schema、迁移与验证约束

当前确认股票功能未部署、正式库未执行相关changeSet时，双Shadow所需的表/列/索引可直接改写 `stocks-portfolio.yaml` 首次发布基线；实现前必须重新确认。若任何正式或共享兼容环境已执行，则旧changeSet不可改写，改用追加迁移并验证旧checksum升级路径。

必须完成：空PostgreSQL完整Liquibase迁移、真实Mapper首次写入和冲突更新、轮次并发插入、候选影子同股/同槽唯一约束、整轮事务回滚。独立线程或独立事务测试产生的数据，使用`@AfterEach`按测试ID精确物理DELETE清理，不改用`@Rollback`。

### 22.9 发布门禁与实施状态

当前所有修订均为**待实施**。P0/P1代码完成并不授权任何开关。后续顺序固定为：部署代码/Schema且四开关false → 经业务授权仅开启ALERT且NEW_ENTRY=false → 历史补齐、月度重算确认、连续3个新桶验证 → 经授权开启NEW_ENTRY并运行两类Shadow至少20自然日 → 长窗口隔离回放与资金/状态/消息门禁 → 单独审批PROVISIONAL → 单独审批正式通知 → 更长期证据后再考虑FORMAL。

第22节与一次性实施方案均须在每次修复进入HEAD后同步更新基线、实现状态、测试计数和真实数据库证据；不得把“设计已补齐”写成“代码已闭环”。
