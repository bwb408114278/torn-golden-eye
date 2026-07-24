# VIP群股票系统虚拟组合与消息提醒技术方案

## 1. 文档信息

- 文档类型：技术设计与实施依据
- 适用项目：Golden-Eye
- 适用版本：1.2.12及以上
- 适用功能：VIP群股票买入/卖出提醒、系统虚拟组合、影子研究、每日组合摘要
- 业务依据：`.ai/knowledge/stocks/vip_stock_virtual_portfolio_strategy.md`
- 设计状态：已审核通过，待专人实施
- 时区：`Asia/Shanghai`
- 维护人：Bai
- 最后确认日期：2026-07-24

本文定义VIP群股票提醒功能的技术架构、数据库结构、状态机、调度流程、消息格式、实施边界和验收标准。策略业务语义以股票知识库为准；本文负责将业务规则映射为可实施、可审计、低侵入的Java/Spring/PostgreSQL方案。

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
AND 通过绝对趋势保护
```

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

### 6.4 false→true边沿

- 原始信号只在买入条件从false变为true时生成；
- 持续为true期间不重复生成高度相关机会；
- 正常、区间、时间或未来动态关闭后冷却24小时；
- 风险关闭后冷却48小时；
- 冷却结束后必须先观察条件回到false，再等待新的false→true边沿；
- 同股已有正式活跃批次时仍记录原始信号和拒绝原因，但不建立第二个正式批次。

### 6.5 正式退出

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

`high30 == low30`时fail-closed，不触发区间退出。

#### 卖出费

```text
netReturn = exitReferencePrice / entryReferencePrice × 0.999 - 1
```

所有群消息、账本、收益统计和关闭判断统一使用扣除0.1%卖出费后的净收益。

### 6.6 影子规则

首期并行记录但不影响正式关闭：

- 动态自然卖出；
- 高风险硬否决观察；
- 满仓拒绝机会；
- 风格拒绝机会；
- 当前Java原始买入对照；
- 可选盈利换仓，默认关闭。

裸连续下跌不得成为独立卖出理由。

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

### 7.3 拒绝观察批次

跟踪因风格、风险观察、趋势保护、同股、冷却、未复位、满仓、数据不连续或价格偏离被拒绝的机会。

- 不占正式槽位；
- 不发正式买入；
- 可以继续跟踪理论路径；
- 不产生需要群消息关闭的正式卖出。

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
  └─ 管理关闭 → ADMIN_CLOSED

EXIT_PENDING
  ├─ 紧邻下一连续bar成交 → CLOSED_TARGET/CLOSED_RANGE/CLOSED_RISK/CLOSED_TIME
  └─ 成交桶不可用 → DATA_STALE_EXIT

DATA_STALE_EXIT
  └─ 恢复后的首个合法处理点按独立灾难处置规则处理
```

首期不得跨缺口伪造卖出参考价格。`DATA_STALE_EXIT`的灾难关闭不是普通策略卖出，不能伪装为止盈消息。

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
| `shadow_batch_id` | BIGINT | 是 | 关联影子批次ID |
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

用途：保存正式组合5个独立资金槽位。

| 字段 | 类型 | 空值 | 说明 |
|---|---|---:|---|
| `id` | BIGINT | 否 | 主键ID |
| `portfolio_code` | VARCHAR(32) | 否 | 组合编码，首期固定`VIP_FORMAL` |
| `slot_no` | INT | 否 | 槽位编号1～5 |
| `initial_cash` | NUMERIC(24,2) | 否 | 槽位初始资金20亿 |
| `available_cash` | NUMERIC(24,2) | 否 | 当前可用现金 |
| `reserved_cash` | NUMERIC(24,2) | 否 | 待买批次已预留现金 |
| `current_batch_id` | BIGINT | 是 | 当前占用该槽的正式批次ID |
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

初始化5行，每行：

```text
initial_cash = 2000000000.00
available_cash = 2000000000.00
reserved_cash = 0.00
slot_status = AVAILABLE
```

### 9.9 `torn_stock_virtual_batch`

用途：统一保存正式、无限资金影子和拒绝观察批次。

| 字段 | 类型 | 空值 | 说明 |
|---|---|---:|---|
| `id` | BIGINT | 否 | 主键ID |
| `batch_no` | VARCHAR(40) | 否 | 系统批次编号 |
| `ledger_type` | VARCHAR(32) | 否 | `FORMAL/UNLIMITED_SHADOW/REJECTED_OBSERVATION` |
| `stocks_id/stocks_shortname` | INT/VARCHAR(8) | 否 | 股票标识及简称快照 |
| `primary_strategy` | VARCHAR(64) | 否 | 主买入策略编码 |
| `matched_strategies` | JSONB | 否 | 同时命中的策略编码列表 |
| `quality_score` | DECIMAL(18,8) | 否 | 入场候选质量分 |
| `batch_status` | VARCHAR(32) | 否 | 批次状态编码 |
| `signal_event_id` | BIGINT | 否 | 来源原始信号事件ID |
| `slot_id/slot_no` | BIGINT/INT | 是 | 正式批次占用槽位，影子为空 |
| `signal_time` | TIMESTAMP | 否 | 买入信号时间 |
| `signal_reference_price` | DECIMAL(18,6) | 否 | 信号bar最后实际价格 |
| `expected_entry_bar_time` | TIMESTAMP | 否 | 预期紧邻下一bar时间 |
| `entry_stale_at` | TIMESTAMP | 否 | 待买过期时间 |
| `entry_time` | TIMESTAMP | 是 | 实际参考买入时间 |
| `entry_reference_price` | DECIMAL(18,6) | 是 | 参考买入价 |
| `quantity` | BIGINT | 是 | 正式组合整数股数 |
| `invested_cash` | NUMERIC(24,2) | 是 | 正式批次实际投入资金 |
| `remaining_cash` | NUMERIC(24,2) | 是 | 建仓后槽位余款 |
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
| `exit_reason` | VARCHAR(64) | 是 | 关闭原因编码 |
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
索引：(ledger_type, batch_status, signal_time)
索引：(stocks_id, ledger_type, batch_status)
索引：(expected_entry_bar_time, batch_status)
索引：(expected_exit_bar_time, batch_status)
检查：价格和资金非负；quantity为空或大于0；已OPEN必须存在entry字段
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
| `payload_hash` | VARCHAR(64) | 否 | 中文消息载荷SHA-256摘要 |
| `payload_snapshot` | JSONB | 否 | 消息结构和中文文本快照 |
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
4. 使用本轮bar处理上一轮待卖参考成交，成交后释放槽位；
5. 更新开放批次峰值、谷值、MFE、MAE、回撤和逐轮mark；
6. 将本轮正式退出候选置为`EXIT_PENDING`；
7. 重新校验同股、冷却、复位、月度状态和槽位；
8. 按`qualityScore DESC → stocksId ASC`接纳正式买入候选并预留槽位；
9. 写入原始信号、无限资金影子和拒绝观察；
10. 为已实际参考成交的正式买入/卖出写入中文通知审计`PENDING`；
11. 更新轮次为`COMPLETED`；
12. 提交事务。

NapCat调用不进入数据库事务。

### 10.4 事务提交后

- 查询本次新建的`PENDING`通知；
- 按风险卖出、其他卖出、买入的顺序构建群消息；
- 同轮同类型最多展示3个动作，超过时拆为续报；
- 调用现有`Bot.sendRequest(..., String.class)`一次；
- 正常返回更新`SENT`，异常或返回`null`更新`FAILED`；
- 本期不自动重试。

---

## 11. 调度设计

### 11.1 bar与特征补偿调度

建议每分钟第20秒执行：

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

应用启动后：

1. 读取最后一个`COMPLETED`轮次；
2. 从下一15分钟桶开始补算至当前已经结束的桶；
3. 按bar、特征、轮次顺序处理；
4. `ENTRY_PENDING`恢复时必须重新检查`staleAt`；
5. 晚于`staleAt`的历史买入不补发；
6. 已完成轮次不重复执行。

### 11.4 每日摘要

Cron：

```text
0 30 8 * * *
zone = Asia/Shanghai
```

摘要日期为发送日前一自然日，并附带发送时点的当前正式组合快照。摘要写入通知审计后调用现有Bot一次。

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

### 12.5 每日摘要

每天08:30发送，至少包含：

```text
【VIP股票组合日报｜2026-07-23】

正式组合
- 当前占用槽位：3 / 5
- 当前组合权益：...
- 昨日买入：2批
- 昨日卖出：1批
- 昨日已实现净收益：...
- 当前开放批次：TCC、MUN、EVL
- 数据陈旧批次：0

影子研究
- 原始买入信号：8个
- 无限资金影子新批次：5个
- 满仓拒绝：2个
- 风格/趋势拒绝：1个
- 动态卖出影子建议：3个
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

枚举建议集中在：

```text
constants/torn/enums/stocks/portfolio/
```

Liquibase建议新增：

```text
src/main/resources/db/changelog/1.0.1-2.0.0/1.2.0/stocks-portfolio.yaml
```

并在：

```text
src/main/resources/db/changelog/db.changelog-master.yaml
```

追加一次include。实施时不得修改已执行changeSet。

现有文件原则上只做必要改动：

- `SettingConstants.java`：增加总开关、模式和日报开关；
- 不增加新群配置，继续读取`ProjectProperty.vipGroupId`；
- 原`StockTradeStrategyService`和`VipStocksStrategyImpl`保持行为不变；
- `TornStocksManager`不接入组合状态机。

---

## 14. 配置建议

使用`sys_setting`增加：

| Key | 默认值 | 含义 |
|---|---|---|
| `VIP_STOCK_ALERT_ENABLED` | `false` | 是否启用股票组合轮次处理 |
| `VIP_STOCK_FORMAL_NOTICE_ENABLED` | `false` | 是否向VIP群发送正式买卖消息 |
| `VIP_STOCK_DAILY_SUMMARY_ENABLED` | `false` | 是否发送08:30日报 |
| `VIP_STOCK_RULE_MODE` | `SHADOW` | `OFF/SHADOW/PROVISIONAL/FORMAL` |

首期部署流程：

```text
先建表和代码
→ 仅启用SHADOW轮次
→ 正式消息开关保持false
→ 验证至少20个自然日
→ 单独审批后切换PROVISIONAL并开启正式消息
```

Shadow模式也维护一套5槽“正式候选影子组合”，但不发即时买卖消息；日报可按用户确认包含Shadow汇总。

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
- `high30 == low30`不触发区间退出；
- 裸连续下跌不触发独立卖出；
- 卖出只能关闭原买入批次。

### 16.7 状态机和幂等

- 轮次重复执行不重复建批次；
- 信号持续为true不重复建事件；
- 冷却结束但未复位不重入；
- 正常关闭冷却24小时；
- 风险关闭冷却48小时；
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

### 16.9 数据库

- Liquibase YAML语法；
- 每张表和每列均有中文remarks；
- 部分唯一索引在PostgreSQL 17.5可执行；
- 使用隔离schema真实执行changelog；
- 读取`obj_description/col_description`核对注释；
- 执行后删除隔离schema；
- 所有DO字段Javadoc和表字段语义一致。

### 16.10 回放

必须使用每槽20亿重跑：

- 5槽正式组合；
- 无限资金影子；
- 拒绝观察；
- 动态卖出影子；
- 高风险观察；
- 当前Java原始买入对照。

回放和生产共用同一bar质量、特征、买入、排序、成交、资金和退出实现，禁止维护两套逻辑。

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
```

### 17.2 数据门禁

```text
bar唯一冲突造成重复计算 = 0
特征未来函数 = 0
风格缺失默认稳健 = 0
旧批次被新月度状态覆盖 = 0
回放与生产规则版本不一致 = 0
```

### 17.3 消息门禁

```text
正式消息展示内部英文编码 = 0
卖出缺失原买入批次号 = 0
风险退出被称为止盈 = 0
每日摘要缺失Shadow汇总 = 0
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
| NapCat失败 | 本期记录FAILED但不自动重试，后续专题治理 |
| 中文映射遗漏 | 统一消息字典和“无英文编码”测试门禁 |

### 19.2 回滚原则

- 所有新表为新增表，不改动现有股票采集和旧建议表结构，回滚侵入性低。
- 功能回滚优先关闭`VIP_STOCK_ALERT_ENABLED`和正式消息开关，不删除数据。
- 已执行Liquibase changeSet不得修改；修正使用追加changeSet。
- 未经单独确认不删除bar、特征、批次、Shadow或失败构建数据。
- 已发布批次即使关闭新买入，也必须继续管理其卖出生命周期；禁止通过关闭总开关遗弃开放正式批次。实现时应将“停止新买入”和“停止持仓管理”分为不同开关或明确保证总开关只停止新轮次中的买入。

---

## 20. 实施者检查清单

- [ ] 先读取本文件和全部股票业务知识库。
- [ ] 不直接复用现有分钟特征作为正式输入。
- [ ] 不修改`StockTradeStrategyService`旧指令行为。
- [ ] 所有正式用户消息使用中文映射。
- [ ] 5槽每槽初始化20亿。
- [ ] 仅使用紧邻下一连续bar的最后实际价格成交。
- [ ] bar允许最多缺5个分钟采样，并满足桶尾5分钟新鲜度。
- [ ] ENTRY从信号桶开始最多等待35分钟。
- [ ] ENTRY只取消向上偏离大于0.15%的情况。
- [ ] 候选排序为质量分降序、股票ID升序。
- [ ] RANGE退出要求净收益大于0且位置不低于0.60。
- [ ] DATA_STALE继续占槽。
- [ ] Shadow只写库，即时群消息只来自正式批次。
- [ ] 每天08:30摘要包含正式和Shadow数据。
- [ ] 使用现有`vipGroupId`，不新增专用群配置。
- [ ] NapCat本期保持现有调用，不实现自动重试和ACK对账。
- [ ] 数据库表和字段全部有准确中文remarks。
- [ ] Java DO字段、record组件和公共API满足项目Javadoc规范。
- [ ] 数据查询和写入无N+1。
- [ ] 事务内不调用NapCat。
- [ ] 编译、聚焦测试、Liquibase隔离schema验证和回放全部通过。

---

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

本文为最终技术方案，不代表已执行代码、Liquibase、历史重建、Shadow启动或正式群发布。后续实施必须按阶段获得相应授权，并在每个阶段完成真实验证。
