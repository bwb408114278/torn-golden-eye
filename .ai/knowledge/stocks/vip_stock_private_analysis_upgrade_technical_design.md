# VIP 私聊 Stock分析 升级技术设计（15m 特征链 + 月度风格接入）

> **文档类型：** 技术设计（实施依据）  
> **适用项目：** Golden-Eye；**计划开发版本 2.0.0（已确认）**
> **状态：** 设计已冻结，未开始编码；本方案只服务 VIP 私聊指令 `Stock分析`，不授权改动 α 决策路径  
> **风险等级：** 混合 — 私聊指令切换到 15m 属 **L2**（只读输出，无资金/业务状态写入）；`torn_stock_monthly_state` 表复活与一次性历史回补属 **L3**（Schema 迁移、批量派生物重建）  
> **业务时区：** `Asia/Shanghai`  
> **消费方：** 单一消费方 — `BotCommands.VIP_STOCK_RECOMMEND = "Stock分析"`（`BotCommands.java:239`）  
> **最后更新：** 2026-09-24

---

## 1. 目标与范围

### 1.1 目标

VIP 私聊指令 `Stock分析` 当前消费"每分钟特征链"（`torn_stock_strategy_feature` + 内存态滚动窗口），并要求人工维护 `sys_setting.STOCK_PERSONALITY` 提供风格参数。本期做两件事，**同一个消费方，不拆两份文档**：

```text
第一章：私聊指令 15m 化
  特征源  torn_stock_strategy_feature（分钟）  →  torn_stock_strategy_feature_15m
  RSI     旧列 rsi（滚动窗口写入）              →  指令触发时用 torn_stocks_history 分钟价现算 RSI(60)
  下线    旧分钟特征写入链 + 旧表 + 超管补算指令

第二章：月度风格接入
  风格源  sys_setting.STOCK_PERSONALITY（唯一事实源、无写入入口、缺失静默忽略）
     →  torn_stock_monthly_state（复活精简版，只保留 SYSTEM 自动确认）
  消费    成熟度 → 买入门槛；风险等级 → 买入门槛 + 展示；风格 → 直接替换人格参数
```

### 1.2 范围

**在范围内：**

- `StockTradeStrategyService` 取值链路、阈值口径、理由文案的改造；
- 为 15m 特征表新增"每股最新一行"只读查询（Mapper 方法 + XML，**不加列、不改表**）；
- 指令触发时基于 `torn_stocks_history` 现算 RSI(60) 的新组件；
- 月度状态表复活（新 Liquibase changeSet）、精简版 `alert/monthly/**` 取回与减法、只读风格解析组件；
- 一次性按月正序回补指令（先 bar/feature，再月度状态）；
- `sys_setting.STOCK_PERSONALITY` 代码退役；
- 旧分钟特征链的分阶段下线清单与顺序。

**不在范围内（见第 7 节）：** α 任何路径、15m 特征表列变更、分钟采集与大额交易消息、人工确认/人工覆盖、槽外机会、年报/年度结算、独立消息平台。

### 1.3 术语

| 术语 | 含义 | 代码/表锚点 |
|---|---|---|
| 私聊指令 | VIP 私聊 `Stock分析` 的输出 | `VipStocksStrategyImpl` |
| 旧分钟特征链 | 每分钟写入的分钟级特征 + 内存滚动窗口 + 超管手动补算 | `TornStocksManager.calcStockFeature` → `StockFeatureBuildService` → `StockRollingFeatureEngine` |
| 15m 特征链 | 由 15 分钟 bar 派生的因果特征，供 VIP/α 共用 | `Stock15mBarBuildService` → `Stock15mFeatureBuildService` → `torn_stock_strategy_feature_15m` |
| 月度状态 | 每股每月冻结的 `strategyFitPrior + maturity + riskLevel` | `torn_stock_monthly_state` |

---

## 2. 现状链路与问题

### 2.1 私聊指令现状链路（代码取证）

```text
BotCommands.VIP_STOCK_RECOMMEND = "Stock分析"（BotCommands.java:239）
  → VipStocksStrategyImpl.handle(TornUserDO, String)            （VipStocksStrategyImpl.java:44）
      stockAnalysisService.analyze(LocalDateTime.now(), false)  （VipStocksStrategyImpl.java:45）
  → StockTradeStrategyService.analyze(analysisTime, debug)      （StockTradeStrategyService.java:50）
      settingManager.getStockPersonalities()                    （:51 → SysSettingManager.java:82）
      featureDao.selectLatestFeatures(analysisTime)             （:53）
  → 逐股 analyzeSingleFeature → 5 个信号取分最高者 → toAdvice
  → VipStocksStrategyImpl.buildGptStockAnalyzeMsg → TableImageUtils 6 列表格
```

**特征读取 SQL 事实**（`src/main/resources/mapper/torn/stocks/TornStockStrategyFeatureMapper.xml:26-71`）：

```sql
FROM torn_stocks s
JOIN LATERAL (
    SELECT ... FROM torn_stock_strategy_feature f
    WHERE f.deleted = 0 AND f.stocks_id = s.id AND f.feature_time <= #{analysisTime}
    ORDER BY f.feature_time DESC LIMIT 1) latest ON true
WHERE s.deleted = 0
```

要点：**每股取 `feature_time <= analysisTime` 的最新一行**，内连接 `torn_stocks`，无版本过滤。

**分钟特征写入链**（`TornStocksManager.java`）：

```text
@Scheduled(cron = "5 * * * * ?", scheduler = "realtimeStockScheduler") spiderStockData()  （:87）
  → TornStocksVO → upsertStocksSnapshot()
  → saveStocksHistory(resp, plannedMinute)  insertRealtimeIgnoreConflict  （:152-158）
  → logCollectionTiming(...)
  → handleInsertResult(insertResult, plannedMinute)                          （:108 / :172）
      inserted == expected → 异步 sendGreatTradeChangeMsg + calcStockFeature(plannedMinute)  （:176-177）
      inserted == 0        → INFO，不发消息、不推进游标                    （:178-179）
      0 < inserted < expected → fail-closed 抛 IllegalStateException        （:180-187）
  → calcStockFeature(regDateTime)                                            （:364）
      featureBuildService.buildBetween(KEY_STOCK_FEATURE_LOAD, regDateTime)  （:367）
      updateSetting(KEY_STOCK_FEATURE_LOAD, regDateTime)                     （:368）
```

`SettingConstants.KEY_STOCK_FEATURE_LOAD = "STOCK_FEATURE_CALC_TIME"`（`SettingConstants.java:89`）。

**内存态滚动窗口**：`StockRollingFeatureEngine`（`stateMap: Map<Integer, StockRollingState>`，46 天预热）→ `StockRollingState`（`StockRollingWindow` ×3 + `StockRollingRsiWindow`）→ `StockStrategyFeatureUpsert` → `StockFeatureBuildService.batchUpsertFeatures`。

### 2.2 现状问题

| 编号 | 问题 | 证据 |
|---|---|---|
| P1 | 私聊口径与 α/研究口径不一致：私聊消费分钟特征，α 与派生链消费 15m 特征，两者 Z-Score/收益率窗口定义不同（分钟滚动 1/7/30 天 vs 96/672/2880 根 bar） | `StockTradeStrategyService.java:53` vs `StockMarketRoundLoader.java:87-88` |
| P2 | 旧"窗口数据不足"判定是启发式：`isWindowDataInsufficient` 用 `|z1-z7|<0.0001 && |z7-z30|<0.0001` 反推窗口未分化，而非显式就绪标志 | `StockTradeStrategyService.java:400-405` |
| P3 | 旧链无版本概念，`torn_stock_strategy_feature` 无 `feature_version` 列；15m 链有 `FEATURE_VERSION` 与 readiness 校验 | `TornStockStrategyFeature15mDO.java:125`、`StockDataReadinessQueryDAO.java:247` |
| P4 | 风格唯一事实源是 `sys_setting` 的 key/value 字符串，`getStockPersonalities` 解析失败**静默忽略**（`catch (IllegalArgumentException ignored)`），缺失时返回空 Map | `SysSettingManager.java:82-100` |
| P5 | 风格缺失时 `resolvePersonality` 默认 `STEADY`，等于"缺数据当稳健股" | `StockTradeStrategyService.java:380-385` |
| P6 | 月度状态表与配套实现已在 1.6.5 退役（drop + 删类），规范要求的 maturity/riskLevel 全链路缺失 | `1.6.5/stocks-alpha-dual-basis.yaml:58-63`；`git show 39b41c6` |

### 2.3 15m 特征链现状（本期依赖的既有能力）

```text
torn_stocks_history（分钟事实，TornStocksManager 每分钟写入）
  → Stock15mBarBuildService.buildBars(bucketStartTime)      BUILD_VERSION = "1.0.0"
      alignToBucket / MIN_SAMPLE_COUNT = 10 / TAIL_FRESHNESS_MINUTES = 5
      buildSingleBar: dedupByTime（同一采集时间保留最大 id）
  → torn_stock_market_bar_15m
  → Stock15mFeatureBuildService.buildFeatures(barStartTime) FEATURE_VERSION = "1.0.0"
  → torn_stock_strategy_feature_15m（每股每桶一行，唯一键 (stocks_id, bar_start_time, feature_version)）
```

`torn_stock_strategy_feature_15m` 相关索引（`1.2.0/stocks-portfolio.yaml:340-344`）：

```sql
CREATE UNIQUE INDEX uk_stock_strategy_feature_15m_stock_time_ver
  ON torn_stock_strategy_feature_15m (stocks_id, bar_start_time, feature_version) WHERE deleted = 0;
CREATE INDEX idx_stock_strategy_feature_15m_time_ready_ver
  ON torn_stock_strategy_feature_15m (bar_start_time, strategy_ready, feature_version);
CREATE INDEX idx_stock_strategy_feature_15m_stock_time_desc
  ON torn_stock_strategy_feature_15m (stocks_id, bar_start_time DESC) WHERE deleted = 0;
```

`idx_..._stock_time_desc` 直接支撑"每股最新一行"的 LATERAL 查询，**不需要新增索引**。

---

## 3. 第一章：私聊指令 15m 化

### 3.1 数据映射

#### 3.1.1 行级映射（旧特征点 → 15m DO）

| 旧 `StockStrategyFeaturePoint` | 15m `TornStockStrategyFeature15mDO` | 处理 |
|---|---|---|
| `stocksId` | `stocksId` | 同名直取 |
| `stocksShortname` | `stocksShortname` | 同名直取 |
| `basePrice` | `referencePrice` | **改名**：15m 语义为 bar 参考价（通常收盘价） |
| `featureTime` | `barStartTime` | **改名**：特征锚点由"分钟时刻"变为"15 分钟桶开始时间" |
| `ma1d`/`ma7d`/`ma30d` | 同名（`ma1d`/`ma7d`/`ma30d`） | 窗口定义变为 96/672/2880 根 bar |
| `zScore1d`/`zScore7d`/`zScore30d` | `zscore1d`/`zscore7d`/`zscore30d` | 大写 S → 小写 s，值语义一致 |
| `return1d`/`return7d`/`return14d` | 同名 | 一致 |
| `pctAbove30dLow`/`pctBelow30dHigh` | `pctAbove30dLow`/`pctBelow30dHigh` | 一致 |
| `rsi` | **无对应列** | **不加列**，改为指令触发时现算，见 3.2 |
| —（无） | `return6h`/`low30d`/`high30d`/`width30d`/`position30` | 本期私聊消费不使用（可用于展示） |
| —（无） | `strategyReady`/`dataQualityReason` | 取代旧 `isWindowDataInsufficient`，见 3.1.2 |
| —（无） | `featureVersion` | 查询条件必须等于 `Stock15mFeatureBuildService.FEATURE_VERSION` |

#### 3.1.2 就绪/不足语义映射

| 旧 | 15m | 结论 |
|---|---|---|
| `isWindowDataInsufficient(point)`：`|z1-z7|<0.0001 && |z7-z30|<0.0001` | `strategyReady != true`（含 `dataQualityReason`） | `windowInsufficient := !Boolean.TRUE.equals(feature.getStrategyReady())` |

**唯一允许多问一句的地方（待确认）：** 旧判定是"窗口未分化"的启发式，新判定是构建期显式结论；两者在冷启动期不是严格等价集合。本期以 15m 显式标志为准，接受集合差异（差异必须体现在 3.6 的回归对比清单中）。

#### 3.1.3 必须新增的查询（现状缺口）

`TornStockStrategyFeature15mMapper` 现有方法只有 `selectByBarStartTime`、`selectByStocksAndTimeRange`、`selectByTimeRange`、`upsertFeature`、`upsertFeatures` —— **没有"每股最新一行"查询**。新增：

```java
// TornStockStrategyFeature15mMapper
List<TornStockStrategyFeature15mDO> selectLatestFeatures(@Param("analysisTime") LocalDateTime analysisTime,
                                                         @Param("featureVersion") String featureVersion);
```

XML（与旧 `selectLatestFeatures` 同构，增加版本过滤与就绪列）：

```sql
SELECT latest.*
FROM torn_stocks s
JOIN LATERAL (
    SELECT f.id, f.stocks_id, f.stocks_shortname, f.bar_start_time, f.reference_price,
           f.ma1d, f.ma7d, f.ma30d, f.zscore1d, f.zscore7d, f.zscore30d,
           f.return1d, f.return7d, f.return14d, f.pct_above_30d_low, f.pct_below_30d_high,
           f.strategy_ready, f.data_quality_reason, f.feature_version
    FROM torn_stock_strategy_feature_15m f
    WHERE f.deleted = 0
      AND f.stocks_id = s.id
      AND f.bar_start_time <= #{analysisTime}
      AND f.feature_version = #{featureVersion}
    ORDER BY f.bar_start_time DESC, f.id DESC
    LIMIT 1) latest ON true
WHERE s.deleted = 0
ORDER BY latest.stocks_id ASC
```

上界取 `analysisTime` 本身（`Stock15mBarBuildService.alignToBucket(analysisTime)` 亦等价且更严格）；特征只在已结束桶构建，故不会读到进行中桶的半成品。

### 3.2 RSI 现算（禁止加列）

**为什么不能加列：** `Stock15mFeatureBuildService.FEATURE_VERSION = "1.0.0"`（`Stock15mFeatureBuildService.java:60`）被以下生产路径用于版本一致性校验：

| 消费者 | 用途 |
|---|---|
| `StockMarketRoundLoader.java:88` | 按版本加载本轮特征快照 |
| `StockHistoryRebuildService.java:448/512/534/574/642` | 重建与"bar/feature 一一对应 + 版本一致"判定 |
| `StockDerivedDataRebuildService.java:340`、`Stock15mFeatureCalculator.java:131`、`StockMarketRoundFactory.java:38`、`VipStockAlertScheduler.java:516` | 回写 round 的 `featureVersion` |
| `StockDataReadinessQueryDAO.java:247` `selectRoundVersionMismatchCount` / `StockDataReadinessReportRunner.java:64/139` | readiness 版本一致性报告与计数 |

加列 ⇒ 必须变更 `FEATURE_VERSION` ⇒ 现有 15m 特征全量失效，需重建（需求方给定规模约 **87 万行**；该行数为需求方口径，**本次未在库上取数核实，待确认**），并产生 α 历史轮次 `roundVersionMismatchCount` 不一致。**成本与收益不成比例，冻结为不加列。**

**现算口径（与旧链路完全一致）：**

```text
输入：torn_stocks_history 中该股票最近 61 个有效分钟点（current_price），按 reg_date_time 升序
算法：复用 pn.torn.goldeneye.torn.model.torn.stocks.trade.StockRollingRsiWindow
      PERIOD = 60，SCALE = 12，EPSILON = 1e-7
      首次 add(price) 仅设置 previousPrice → 需 61 个点才产生 60 个涨跌幅
      不足 60 期（gains.size() < 60）→ 返回 50
      lossSum < EPSILON → 返回 100
      否则 RSI = 100 - 100 / (1 + gainSum / lossSum)
```

因此**阈值无需重新校准**：模型里 `feature.rsi() <= 35D` 加 8 分（`StockTradeStrategyService.java:153-156`）保持不变。

**取数实现（复用既有 DAO，不新增 SQL）：**

- `TornStocksHistoryDAO.selectHistoryPointsRange(since, endTime)`（`TornStocksHistoryDAO.java:64`），SQL 为 `reg_date_time >= since AND reg_date_time < endTime AND deleted = 0 ORDER BY stocks_id, reg_date_time, id`；
- 窗口取 `since = analysisTime.minusMinutes(61)`、`endTime = analysisTime.plusNanos(1)`；一次查询覆盖全部股票（35 支 × 61 ≈ 2,135 行，成本可忽略）；
- 每股按时间升序取最近至多 61 点；**同采集时间去重口径与 bar 构建一致：保留最大 id**（`Stock15mBarBuildService.buildSingleBar` → `dedupByTime`，私有静态方法）。

> **实现细节（二选一，推荐 a）：**
> a) 将 `Stock15mBarBuildService.dedupByTime` 提升为 `public static`（**纯可见性变更，不改算法、不改 `BUILD_VERSION`/`FEATURE_VERSION`**），RSI 现算直接复用，保证去重口径唯一；
> b) 若不同意改动该类，则在 RSI 组件内实现同口径去重。禁止另立第三种口径。

**保留 `StockRollingRsiWindow`：** 它是无状态纯计算类（不依赖 DB、不依赖 `StockRollingState`），本期作为 RSI 现算的实现体保留；随旧链一起删除的是 `StockRollingFeatureEngine`、`StockRollingState`、`StockRollingWindow`。

### 3.3 评分口径（null 安全 + 就绪语义）

15m 表规范明确"窗口指标在窗口不足或不可计算时为 `null`，**绝不填充 0/参考价/前值**"（`TornStockStrategyFeature15mDO.java:11-21`）。因此评分层必须显式分流，禁止把 `null` 当 `0` 参与比较（`z=0` 会命中 `zScore7d <= 0.5`、`zScore7d > 0` 等分支，把"未知"误判为"常态"）：

| 分流 | 条件 | 处理 |
|---|---|---|
| A 正常 | `strategyReady = true` 且 `referencePrice != null` | 按原 5 信号评分逻辑逐字段评分 |
| B 窗口未就绪 | `strategyReady != true` | 等价于旧"窗口数据不足"：**买入门槛 +10**（`WINDOW_INSUFFICIENT_PENALTY = 10`，`StockTradeStrategyService.java:43`）；所有为 `null` 的窗口指标一律视为**该分支不命中**（不加分、不扣分），并用 `dataQualityReason` 生成展示文案 |
| C 行不可用 | `referencePrice == null` | 该股不进入输出，记 WARN（含 `stocksId`、`barStartTime`） |
| D 数据陈旧 | 最新 `barStartTime` 距 `analysisTime` 超过阈值（建议 `48h`，常量 `STALE_FEATURE_MAX_AGE`） | 该股不推荐，理由标注"特征陈旧"；防止停产后静默输出过期建议 |

**门槛叠加（只改门槛，不改分数）**，仅作用于与人格阈值同构的 `SWING_LOW_BUY` 通道（与现状 `windowInsufficient` 完全同构，见 `StockTradeStrategyService.java:160-162`）：

```text
effectiveThreshold = personality.getBuyThreshold()
                   + (windowInsufficient ? WINDOW_INSUFFICIENT_PENALTY : 0)   // 10，现状沿用
                   + (maturity == M1_EARLY ? MONTHLY_MATURITY_PENALTY : 0)    // 新增常量 10
                   + (riskLevel == HIGH ? MONTHLY_RISK_PENALTY : 0)           // 新增常量 10
```

- 两项月度惩罚可叠加，单股买入门槛最多 +20；
- **`SWING_REVERSAL_BUY` 的固定 `BUY_SCORE_THRESHOLD = 50` 本期不动**（与现状 `windowInsufficient` 不影响该通道保持一致），此不一致记入第 5 节观察项；
- 卖出通道（`TAKE_PROFIT_SELL = 55` / `REBOUND_SELL = 45` / `QUICK_PROFIT_SELL = 40`）与 `NARROW_BAND_Z_DISCOUNT = 0.6` 均不改。

---

### 3.4 结构改动（新增/修改文件）

| 动作 | 文件 | 职责 |
|---|---|---|
| 新增 | `repository/mapper/torn/stocks/portfolio/TornStockStrategyFeature15mMapper.java`（+方法） | `selectLatestFeatures` |
| 修改 | `src/main/resources/mapper/torn/stocks/portfolio/TornStockStrategyFeature15mMapper.xml` | 上述 LATERAL SQL（只增不改既有语句） |
| 新增 | `torn/service/stocks/alert/market/Stock15mTradeFeatureProvider.java`（命名待定） | 调 15m DAO，映射为内部特征点，执行 3.3 的 A/B/C/D 分流 |
| 新增 | `torn/service/user/StockMinuteRsiCalculator.java`（命名待定） | 一次性批量取分钟点 → 每股 RSI(60) |
| 新增 | `torn/service/user/StockMonthlyStyleResolver.java`（命名待定） | 读月度状态、选月、留痕、告警，见第二章 |
| 修改 | `torn/service/user/StockTradeStrategyService.java` | 换数据源、null 安全、门槛叠加、理由文案；删除 `resolvePersonality` 的 `sys_setting` 依赖 |
| 修改 | `napcat/strategy/vip/VipStocksStrategyImpl.java` | 理由/表格追加月度风险与沿用留痕；**列数保持 6 列**，不改 `TableImageUtils` 合并配置 |
| 可能修改 | `torn/service/stocks/alert/market/Stock15mBarBuildService.java` | 仅 `dedupByTime` 可见性（见 3.2 选项 a） |

`StockTradeAdvice` 为 record（21 个分量，`StockTradeAdvice.java:39-61`）。**若不新增字段**，月度信息全部走 `reasons` 文案，`VipStocksStrategyImpl` 无需改动构造调用；若确需结构化字段，必须同步 `StockTradeStrategyService.toAdvice`（唯一构造点）。**本期按"不加字段、只加理由文案"执行，避免 record 签名扩散。**

### 3.5 下线清单与迁移顺序

**前置约束：** 旧链在下线前必须一直可用（它是回退路径）；15m bar 依赖分钟数据，故分钟采集与 `torn_stocks_history` 写入**永不停止**。

| 阶段 | 内容 | 门禁 |
|---|---|---|
| 0 | 只读巡检：15m bar/feature 对 `torn_stocks_history` 的覆盖率、每股最新 `bar_start_time` 新鲜度、`strategyReady=true` 占比、`dataQualityReason` 分布 | 35 支股票最新特征行均存在且新鲜度 ≤ 48h |
| 1 | 落地 3.4 的新增/修改，私聊指令切到 15m 特征源 + RSI 现算（风格仍读 `sys_setting`，第二章之前保持可用） | 编译 + 3.6 单测 + 新旧输出对比 |
| 2 | 观察期（建议 1~2 周）：私聊指令输出与旧口径逐支差异清单人工确认 | 无未解释差异 |
| 3 | 第二章上线（月度风格成为风格唯一来源），私聊指令完全脱离 `sys_setting` | 第 4 节验收 |
| 4 | 下线旧分钟特征写入：删除 `TornStocksManager.handleInsertResult` 内的 `calcStockFeature(plannedMinute)` 调用（`:177`）、`calcStockFeature` 方法（`:364-371`）、`StockFeatureBuildService` 注入（`:64`）与 `KEY_STOCK_FEATURE_LOAD` 使用 | `TornStocksManagerTest` 通过；fail-closed 语义不变 |
| 5 | 删除代码：`StockFeatureBuildService`、`StockRollingFeatureEngine`、`StockRollingState`、`StockRollingWindow`、`StocksFeatureBuildStrategyImpl` + `BotCommands.STOCK_FEATURE_SYNC`（`BotCommands.java:201`）、`TornStockStrategyFeatureDAO`、`TornStockStrategyFeatureMapper`、`TornStockStrategyFeatureDO`、`StockStrategyFeaturePoint`、`StockStrategyFeatureUpsert`、`src/main/resources/mapper/torn/stocks/TornStockStrategyFeatureMapper.xml` | 全仓无残留引用（含测试） |
| 6 | **旧表 `torn_stock_strategy_feature` drop：本期不做**（破坏性操作，需独立变更单 + 用户确认 + 至少 30 天数据保留窗口） | 单独立项 |

**必须保留（任何阶段都不得改动）：**

1. 每分钟 Torn API 采集（`spiderStockData`，`cron = "5 * * * * ?"`）；
2. `torn_stocks_history` 分钟写入与 `insertRealtimeIgnoreConflict` 冲突安全语义；
3. 大额交易消息 `sendGreatTradeChangeMsg`（`:176`）；
4. `handleInsertResult` 的 fail-closed 语义：`0 < inserted < expected` 抛异常、不发消息、不推进游标（`:180-187`）。

> 阶段 4 只允许修改方法内**日志文案与 Javadoc**（去掉"不推进旧特征游标"措辞，`:179/:181-183`），**不得改动三分支判定与异常语义**。

### 3.6 测试与回归口径

| 层级 | 用例 | 说明 |
|---|---|---|
| 纯逻辑 | `StockTradeStrategyService` 评分映射：15m DO → 内部特征点（含 `referencePrice`/`barStartTime`/大小写字段） | 主路径 |
| 纯逻辑 | 门槛叠加矩阵：`strategyReady` × `maturity` × `riskLevel` 组合下 `effectiveThreshold` | 覆盖 +10/+20/叠加边界 |
| 纯逻辑 | null 安全：任一窗口指标为 null 时不抛 NPE 且不误命中分支 | 本次明确改变的边界 |
| 纯逻辑 | `isWindowDataInsufficient` 替换为 `strategyReady` 判定 | 直接回归 |
| 纯逻辑 | RSI 现算：61 点序列结果与直接使用 `StockRollingRsiWindow` 一致；<61 点 → 50；`lossSum≈0` → 100；重复自然分钟取最大 id | 口径一致性 |
| 真实 PostgreSQL | 新增 `selectLatestFeatures` Mapper 测试（参照 `TornStockStrategyFeature15mMapperTest`）：每股仅一行、`feature_version` 过滤、`deleted=0` 过滤、`bar_start_time <= analysisTime` 上界 | SQL 只能用真实库验证 |
| 集成 | `TornStocksManagerTest` 保持通过（fail-closed 未变） | 保护 3.5 的"必须保留" |
| 人工 | 同一时点跑新旧两套取值，输出逐支差异清单（行数、动作、score、门槛） | 阶段 2 门禁 |

**不做的测试：** 不测 α（未改动）、不做并发/故障注入（只读路径）、不做 15m 特征全量重建测试。

---

## 4. 第二章：月度风格接入

### 4.1 表复活（Liquibase）

**历史事实：**

| 版本 | 文件 / changeSet | 内容 |
|---|---|---|
| 1.2.0 | `1.0.1-2.0.0/1.2.0/stocks-portfolio.yaml` → `create_table_stock_portfolio_monthly_state`（:352-495） | 创建 `torn_stock_monthly_state` + 唯一索引 + 4 个 CHECK 约束 |
| 1.4.8 | `1.0.1-2.0.0/1.4.8/monthly-state-rule-version-widen.yaml` → `widen_monthly_state_rule_version_columns` | `personality_rule_version`/`risk_rule_version` → `VARCHAR(64)` |
| 1.6.5 | `1.0.1-2.0.0/1.6.5/stocks-alpha-dual-basis.yaml` → `drop_retired_stock_monthly_state_table`（:58-63） | `dropTable: torn_stock_monthly_state` |

**关键结论：不能靠"删除 1.6.5 的 drop"来复活。** 1.2.0 的 create changeSet 早已在 `DATABASECHANGELOG` 中标记执行，Liquibase 不会重跑；即使删掉 drop，空库/新库与已部署库的状态也会分叉。**必须新增 changeSet 重建表**（新版本目录 include 追加到 `db.changelog-master.yaml` 末尾，当前位置最后一项为 `1.6.5/stocks-alpha-dual-basis.yaml`，`db.changelog-master.yaml:111-112`）。

```text
新增：src/main/resources/db/changelog/1.0.1-2.0.0/<新版本目录>/stocks-monthly-state-revive.yaml
修改：src/main/resources/db/changelog/db.changelog-master.yaml   （只追加 include）
```

- changeSet id：`revive_stock_monthly_state_table`（author: Bai），内容 = 1.2.0 原 DDL（含 `uk_stock_monthly_state_stock_month`、`idx_stock_monthly_state_month_status`、`ck_monthly_effective_month`、`ck_monthly_evidence_window`、`ck_monthly_confirmed_at`、`ck_monthly_confirmed_complete`），列宽直接取 1.4.8 后的 `VARCHAR(64)`；
- 加 `preConditions: tableExists` 取反保护（或 `onFail: MARK_RAN`），保证"已存在则不动"的幂等语义；
- **新版本目录名待确认**（当前 `pom.xml` 版本为 `1.6.5`，需按发布版本决定是 `1.6.6` 还是 `1.7.0`）；
- **待确认：** 生产库当前是否存在 `torn_stock_monthly_state`、`DATABASECHANGELOG` 中 1.6.5 的 drop 是否已执行（本次无库访问权限，未核实）。

**精简项（代码层，不是列层）：** 表结构保持与原 DDL 一致（避免 schema 漂移），但

- `manual_override` 恒写 `false`，`override_reason` 恒 `null`，**不提供任何写入入口**；
- 删除 `confirmDraftStates(effectiveMonth, confirmedBy)`；
- 保留 `state_status` 三态 `DRAFT`/`CONFIRMED`/`RETIRED`（`StockMonthlyStateStatusEnum` 仍在代码树中）。

### 4.2 取回 `alert/monthly/**` 与做减法

`git show 39b41c6^` 可完整取回以下文件（提交 `39b41c6` 删除了它们）：

| 取回文件 | 处置 |
|---|---|
| `torn/service/stocks/alert/monthly/StockMonthlyEvidenceComputer.java` | **保留**（证据计算） |
| `.../StockMonthlyEvidenceMetrics.java` | **保留**（指标 record） |
| `.../StockMonthlyStateCalculator.java` | **保留**，见下方减法 |
| `.../StockMonthlyStateDraft.java` | **保留** |
| `.../StockMonthlyPrevious.java` | **保留**，`previous` 改为读"最近一期已生效月份" |
| `.../StockMonthlyStateInitService.java` | **保留**，删除人工入口 |
| `.../StockMonthlyEvidenceExclusionPolicy.java` | **默认不取回（待确认）**：它把规则版本绑定到 `PERSONALITY_RULE_V2_OUTAGE_EXCLUSION` / `RISK_RULE_V2_OUTAGE_EXCLUSION`（停机窗口 `TORN_MARKET_OUTAGE_20260214_0801_1515`）。若取回，规范 §1 的 `PERSONALITY_RULE_V1` / `RISK_RULE_V1_SHADOW` 版本口径必须同步明确，否则冻结版本会漂移 |
| `torn/service/stocks/rebuild/StockMonthlyStateRangeRebuildService.java` | **保留**（`rebuild(startInclusive, endExclusive)`，回补指令复用） |
| `repository/model/torn/stocks/portfolio/TornStockMonthlyStateDO.java` | **保留**（字段与 4.1 表一致） |
| `repository/dao|mapper/.../TornStockMonthlyStateDAO.java` / `Mapper.java` / `TornStockMonthlyStateMapper.xml` | **保留**（含 `autoConfirmDraftStates` 批量更新） |
| 测试 `StockMonthly*Test` / `TornStockMonthlyState*MapperTest` | **保留**并随减法更新 |

**减法清单（冻结）：**

1. 删除 `StockMonthlyStateInitService.confirmDraftStates(LocalDate, String)` 及其测试；
2. `StockMonthlyStateCalculator` 中删除人工覆盖优先级分支（规范 §8.2 的 `manualOverride = true` 路径），`strategyFitPrior := suggestedPersonality` 恒成立；
3. 保留 `maturity`、`riskLevel`、迟滞规则、`previous`、规则版本、证据快照、DRAFT 状态与 `autoConfirmDraftStates`；
4. `StockStrategyFitEnum` / `StockMaturityEnum` / `StockRiskLevelEnum` 三个枚举**已在代码树中**（含 `ALPHA_NOT_EVALUATED`），直接复用，不新增枚举。

### 4.3 只保留 SYSTEM 自动确认

```text
保留：autoConfirmDraftStates(effectiveMonth)   → confirmedBy = SYSTEM
删除：confirmDraftStates(effectiveMonth, confirmedBy)   → 人工入口不存在
```

自动确认条件（规范 §9.2，全部满足才确认，否则保持 `DRAFT`）：

1. 数据完整性通过（`usableBarCoverage >= 95%`、`maxMissingBucketGap <= 2h`、`dailyCloseCount >= 10`，规范 §3.7）；
2. `manual_override = false`（本期恒成立）；
3. 所有必填指标与状态非空；
4. 规则版本等于当前冻结版本；
5. 不存在待人工复核标记；
6. 已生成 `previous` 状态与迟滞结果。

**DRAFT 的语义冻结：** DRAFT = "上月末数据未补齐就先不生效"的**数据质量闸门**，不是等人确认的工作流。证据补齐后由幂等重算（`recalculateMonthDrafts`）重新计算，再走自动确认；**不允许**"创建不完整 DRAFT 后永久跳过重算"。数据库层 `ck_monthly_confirmed_complete` 约束（`state_status = 'CONFIRMED'` 时风格/成熟度/风险/证据窗口必须非空）作为第二道防线。

### 4.4 消费口径（写入 `StockTradeStrategyService`）

| 维度 | 取值 | 对私聊指令的影响 |
|---|---|---|
| `strategyFitPrior` | `StockStrategyFitEnum` 六类之一 | 映射到 `StockPersonalityEnum` 取 `buyThreshold` / `fallingKnifeZThreshold` / `declinePenalty`；`NARROW` 额外启用 `NARROW_BAND_Z_DISCOUNT = 0.6`（`StockTradeStrategyService.java:390-395`） |
| `maturity = M0_UNMATURE` | 历史 < 60 天（规范 §4） | **不推荐**：该股全部信号强制 `HOLD`，debug 模式保留行并标注原因 |
| `maturity = M1_EARLY` | 60 ≤ 历史 < 120 天 | 买入门槛 **+10**（与 `WINDOW_INSUFFICIENT_PENALTY = 10` 同构） |
| `maturity ∈ {M2_PROVISIONAL, M3_SEASONED, M4_MATURE}` | ≥ 120 天 | 无影响 |
| `riskLevel = HIGH` | 规范 §7.3 | 买入门槛 **+10**，并在推荐理由与表格中展示风险 |
| `riskLevel = MEDIUM` | | **仅展示**，不改门槛 |
| `riskLevel = NONE` | | 无影响 |

- **风格编码映射是 1:1 的**：`StockStrategyFitEnum` 与 `StockPersonalityEnum` 六类业务编码同名（`DECLINER`/`WEAK`/`NARROW`/`RANGING`/`STEADY`/`STRONG`），`ALPHA_NOT_EVALUATED` 是 α 专用伪值，**私聊解析遇到它必须视为"无可用风格"而不是风格**；
- `StockPersonalityEnum` 保留为**评分参数表**（不动其数值，阈值校准是另一专题），但其 Javadoc 中"每月初根据数据库分析结果更新 `sys_setting` 表的 `STOCK_PERSONALITY` 配置"（`StockPersonalityEnum.java:8-9`）必须改写为月度状态表来源；
- **展示落地（不改表格列数）**：在 `reasons` 追加一条月度摘要，例如 `月度：风格=RANGING 成熟度=M3_SEASONED 风险=HIGH（2026-09 生效）`；若 `HIGH` 再追加 `月度风险HIGH：买入门槛+10`；若 `M1_EARLY` 再追加 `成熟度早期：买入门槛+10`。

### 4.5 与日内风险扣分的职责边界（不重复扣分）

**冻结原则：月度管门槛与展示，日内管当下价格结构。两者使用不同手段（+门槛 vs −分数），不得对同一事实重复施加惩罚。**

| 手段 | 归属 | 现状证据 |
|---|---|---|
| `isFallingKnife` → `declinePenalty`（DECLINER −30 / WEAK −20，其余 0） | 日内 | `StockTradeStrategyService.java:330-337`、`StockPersonalityEnum.java:21/25` |
| `isPersistentDecline` → −30 | 日内 | `:338-341`（阈值 `PERSISTENT_DECLINE_14D_THRESHOLD = -0.005`） |
| DECLINER 未反弹（`return1d <= 0`）→ −22 | 日内 | `:343-345` |
| WEAK 未反弹 → −14 | 日内 | `:346-349` |
| `zScore30d <= -3 && return1d < 0` → −10 | 日内 | `:351-354` |
| `maturity = M1_EARLY` → 买入门槛 +10 | 月度 | 本方案新增 |
| `riskLevel = HIGH` → 买入门槛 +10；MEDIUM 仅展示 | 月度 | 本方案新增 |

**唯一交叉点：** 月度 `DECLINER`/`WEAK` 会通过 `StockPersonalityEnum.getDeclinePenalty()` 改变日内飞刀惩罚的**幅度**（−30/−20 是现有机制）。这是既有设计，本期保留；月度 `riskLevel` **不再额外加分/扣分**，只改门槛，从而避免与 `isPersistentDecline`/`isFallingKnife` 对同一"下跌事实"二次惩罚。

### 4.6 沿用与兜底

**选月算法：**

```text
targetMonth = YearMonth.from(analysisTime)
latest = max(effectiveMonth) where stocks_id = X and state_status = 'CONFIRMED' and deleted = 0
```

| 情况 | 行为 |
|---|---|
| `latest == targetMonth` | 正常使用，理由文案标注生效月份 |
| `latest < targetMonth` 且 `monthsBetween(latest, targetMonth) == 1` | **沿用最近已生效月份**，并**必须留痕**：`使用 2026-09 风格（2026-10 未生成）` |
| `monthsBetween(latest, targetMonth) >= 2`（连续 2 个月无新月份） | **停止推荐**（全部 `HOLD`）+ 告警：日志 `ERROR` + 指令回复顶部显式告警文案。目的：防止沿用退化成固定死值 |
| 该股无任何 `CONFIRMED` 行（首次上线 / 新增股票 / 全表为空） | **不推荐** + 告警；**禁止默认 `STEADY`**（修复 `resolvePersonality` 默认值缺陷，`:384`） |
| 该股当月行为 `DRAFT` | 等同"未生成"，不得当作风格来源（规范 §6.1：`previous` 只读 `CONFIRMED`） |

**风格一致性：** 沿用月份的风格必须与 `previousPersonality` 口径一致（`StockMonthlyPrevious` 读取"同一股票、生效月份早于 M 的最近一条 `CONFIRMED`"，规范 §6.1）。工程实现上，选月等价于"取最近一条 `CONFIRMED` 且 ≤ targetMonth"，因此不与月度计算器的 `previous` 逻辑冲突。

**告警不新增平台：** 告警走"指令回复文案 + 应用日志"，不接独立消息通道（见第 7 节）。

### 4.7 一次性回补指令

**指令：** 新增超管指令（命名待确认）`BotCommands.MONTHLY_STYLE_BACKFILL = "回补Stock月度风格"`，参数 `yyyy-MM#yyyy-MM`（起始月#结束月），参考既有超管指令风格（`StocksFeatureBuildStrategyImpl`、`StockDerivedDataRebuildStrategyImpl`）。

**执行顺序（强制"先 bar/feature → 再月度状态"，规范 §2.1 冷启动顺序）：**

```text
for month in [startMonth .. endMonth] 按月正序:
  1. evidenceEnd   = month 开始前最后一个可用 15m bar 时间（规范 §2）
     evidenceStart = max(该股首个可用 bar, evidenceEnd - 365 天)
  2. 派生数据补齐（幂等、按自然日分片、不写业务状态）：
     StockDerivedDataRebuildService.rebuildRange(bucket(evidenceStart), bucket(evidenceEnd) + 15m)
     —— 或引导人工先用既有超管指令「重建Stock派生数据」完成，再由回补指令只做第 3 步
  3. 月度状态：
     StockMonthlyStateInitService.initMonth(effectiveMonth)               // 生成 DRAFT，ignore conflict
     StockMonthlyStateInitService.recalculateMonthDrafts(effectiveMonth)  // 证据补齐后的幂等重算
     StockMonthlyStateInitService.autoConfirmDraftStates(effectiveMonth)  // 仅 SYSTEM，条件不满足保持 DRAFT
  4. 单月失败只记 ERROR 并继续下一月（不中断、可重跑）
```

**幂等与不可覆盖约束：**

- `insertDraftStatesIgnoreConflict` + 唯一键 `uk_stock_monthly_state_stock_month (stocks_id, effective_month) WHERE deleted = 0`；
- 已 `CONFIRMED` 的月份/股票**不覆盖、不重算**（`selectExistingStockIdsByMonth` / `selectConfirmedByMonth` 先过滤）；
- `autoConfirmDraftStates` 只处理 `DRAFT`；
- 回补过程不触碰 `torn_stocks_history`、不调用 `StockRoundTransactionService`、不写 signal_event / virtual_batch / batch_mark / notice_audit / 槽位 / 资金 / 冷却（与 `StockDerivedDataRebuildService` 的既有边界一致，`StockDerivedDataRebuildService.java:36-38`）。

**回补完成后对账（一次性，硬门禁）：**

```text
对账源：sys_setting 中 key = 'STOCK_PERSONALITY'（SettingConstants.KEY_STOCK_PERSONALITY）
        → SysSettingManager.getStockPersonalities() 解析出的 (shortname → StockPersonalityEnum)
对账对象：最近一期已生效月份的 strategy_fit_prior（35 支）
输出：逐支 (stocksShortname, 旧风格, 新风格, 是否一致)；差异逐支人工判定
```

对账结果只落日志与指令回复文本，**不建对账表**。**待确认：** 35 支股票的实际差异清单与旧配置行内容，本次未取数。

### 4.8 `sys_setting.STOCK_PERSONALITY` 退役

| 动作 | 目标 |
|---|---|
| 删除读取入口 | `SysSettingManager.getStockPersonalities()`（`SysSettingManager.java:82-100`） |
| 删除常量 | `SettingConstants.KEY_STOCK_PERSONALITY`（`SettingConstants.java:94`） |
| 删除默认值 | `StockTradeStrategyService.resolvePersonality` 的 `return StockPersonalityEnum.STEADY`（`:384`）改为"无可用风格 → 不推荐 + 告警" |
| 保留数据行 | `sys_setting` 中 `key = 'STOCK_PERSONALITY'` 的行**本期不删除**，仅停止读取；保留为只读历史，后续是否清理由用户单独决策 |

**修复的三个具体缺陷：** ①唯一事实源、无写入入口（只能手工改库）；②缺失时 `getStockPersonalities` 返回空 Map 且解析失败被静默忽略（`SysSettingManager.java:94-96`）；③`resolvePersonality` 默认 `STEADY`。

### 4.9 禁止进入 α

**冻结：月度风格只服务私聊指令。**

- α 路径现状已明确"不使用风格"：`StockAlphaEntryService.java:307-309` 与 `StockAlphaRebalanceService.java:273-275` 显式写入 `stylePrior = StockStrategyFitEnum.ALPHA_NOT_EVALUATED`、`styleMaturity = StockMaturityEnum.ALPHA_NOT_EVALUATED`、`riskLevel = StockRiskLevelEnum.ALPHA_NOT_EVALUATED`；
- 本方案**不得**修改 `StockAlphaEntryService`、`StockAlphaRebalanceService`、`StockAlphaDecisionService`、`VipStockAlertScheduler` 轮次决策、`StockMarketRoundLoader` 的任何判定；
- 月度风格解析组件只被 `StockTradeStrategyService` 注入，**不得**被 `torn/service/stocks/alert/alpha/**`、`torn/service/stocks/alert/market/**` 引用（需在 Code Review 与验收中显式检查导入）；
- α 的变量只有相位（phase），本方案不改变这一点。

---

## 5. 风险与回退

| 编号 | 风险 | 影响 | 缓解 | 回退 |
|---|---|---|---|---|
| R1 | 15m 特征覆盖率/新鲜度不足，私聊输出骤减 | 指令可用性下降 | 阶段 0 只读巡检门禁；3.3 的 D 分流显式标注陈旧 | 阶段 1~3 期间旧链仍写入，回滚代码即回到旧口径 |
| R2 | `null` 窗口指标被误当 0 参与评分 | 误判买卖信号 | 3.3 分流 A/B/C/D + 单测覆盖 null 安全 | 回滚 `StockTradeStrategyService` |
| R3 | 新旧"数据不足"判定集合不完全等价 | 冷启动期信号数量差异 | 阶段 2 逐支差异清单人工确认 | 差异不可接受则暂缓阶段 4 |
| R4 | 月度状态表复活与 1.6.5 drop 顺序/幂等冲突 | Liquibase 迁移失败或表缺失 | 新 changeSet + preConditions；空库与已部署库双场景验证 | 手工按实际执行情况处置（不建自动回滚逻辑） |
| R5 | 自动确认误确认不完整月份 | 错误风格生效一整月 | 规范 §9.2 六条件 + `ck_monthly_confirmed_complete` 约束 | 将误确认月份置回 `DRAFT` 并重算（人工运维动作） |
| R6 | 沿用退化为固定死值 | 长期使用过期风格 | `monthsBetween >= 2` 停推 + 告警 + 留痕文案 | 停推即为安全态，无需回退 |
| R7 | 回补范围过大导致长时间占用 | 数据库/应用压力 | 按月正序、单月失败不中断、幂等可重跑、复用按日分片 | 中断指令，已完成月份保留 |
| R8 | RSI 现算多一次分钟查询 | 指令延迟 | 单次查询全部股票（≈2,135 行），成本可忽略 | 无需回退；若实测放大再议 |
| R9 | 去重口径不一致（同采集时间多条） | RSI/bar 口径分歧 | 复用 `dedupByTime` 同口径（3.2 选项 a） | 统一到 bar 构建口径 |
| R10 | 风格解析被 α 误引用 | 违反冻结边界 | 4.9 的包引用检查 + 验收项 | 移除引用 |

**总回退策略：** 第一章是只读输出改造，回退 = 回滚代码（旧表在阶段 6 之前不 drop）；第二章的回退 = 停止读取月度状态并回到阶段 1 的 `sys_setting` 读取（该行数据在 4.8 中保留，具备回退能力），**但不得长期停留**（退役是目标态）。

---

## 6. 验收

### 6.1 功能验收（第一章）

- [ ] 私聊指令 `Stock分析` 的输出完全来自 `torn_stock_strategy_feature_15m`（无 `torn_stock_strategy_feature` 查询）；
- [ ] 查询过滤 `feature_version = Stock15mFeatureBuildService.FEATURE_VERSION` 且 `deleted = 0`；`FEATURE_VERSION` 值未被修改；
- [ ] RSI 由 `torn_stocks_history.current_price` 现算，61 点/60 期、不足返回 50、`lossSum≈0` 返回 100，与 `StockRollingRsiWindow` 同口径；
- [ ] 15m 特征表未新增任何列（DDL diff 为空）；
- [ ] `TornStocksManager` 每分钟采集、`torn_stocks_history` 写入、`sendGreatTradeChangeMsg`、`handleInsertResult` 三分支语义未变；
- [ ] 阈值：`strategyReady=false`、`M1_EARLY`、`riskLevel=HIGH` 各自 +10，仅作用于买入门槛；卖出阈值与 `NARROW` Z×0.6 未变；
- [ ] 月度风险 HIGH/MEDIUM 的展示出现在指令输出的理由列中。

### 6.2 功能验收（第二章）

- [ ] 新 changeSet 在空库与"已执行 1.6.5"库上均可成功执行，且重复执行幂等；
- [ ] 代码中不存在 `confirmDraftStates(LocalDate, String)`；`manual_override` 恒 `false`、`override_reason` 恒 `null`；
- [ ] `autoConfirmDraftStates` 仅在规范 §9.2 六条件全满足时把 `DRAFT` 置 `CONFIRMED`，`confirmedBy = 'SYSTEM'`；
- [ ] 不完整证据保持 `DRAFT`（`MONTHLY_EVIDENCE_INCOMPLETE`），补齐后幂等重算并可自动确认；
- [ ] 成熟度按 60/120/240/365 天（规范 §4），不按 bar 数；
- [ ] 六类风格按 `DECLINER → WEAK → NARROW → RANGING → STRONG → STEADY` 首次命中（规范 §5）；
- [ ] 风险投票为 HIGH 4 票（H1~H4）/ MEDIUM 6 票（M1~M6），含迟滞（规范 §7）；
- [ ] `previous` 只读"最近一期已生效（`CONFIRMED`）月份"；
- [ ] 无当月生效行时沿用最近已生效月并留痕 `使用 YYYY-MM 风格（YYYY-MM 未生成）`；
- [ ] `monthsBetween >= 2` 时停止推荐并告警；
- [ ] 无可用风格（无行 / `ALPHA_NOT_EVALUATED`）时不默认 `STEADY`；
- [ ] `sys_setting.STOCK_PERSONALITY` 无任何代码读取；数据行仍存在；
- [ ] `torn/service/stocks/alert/alpha/**`、`alert/market/**` 无对月度风格解析组件的引用。

### 6.3 数据验收（只读 SQL，不建报告表）

```sql
-- 15m 特征最新行覆盖与新鲜度
SELECT s.id, s.stocks_shortname, max(f.bar_start_time) AS latest_bar,
       count(*) FILTER (WHERE f.strategy_ready) AS ready_rows
FROM torn_stocks s
LEFT JOIN torn_stock_strategy_feature_15m f
       ON f.stocks_id = s.id AND f.deleted = 0 AND f.feature_version = '1.0.0'
WHERE s.deleted = 0
GROUP BY s.id, s.stocks_shortname
ORDER BY latest_bar NULLS FIRST;

-- 月度状态分布
SELECT effective_month, state_status, count(*)
FROM torn_stock_monthly_state
WHERE deleted = 0
GROUP BY effective_month, state_status
ORDER BY effective_month DESC, state_status;

-- 最近已生效月份的风格清单（供与旧 sys_setting 逐支对账）
SELECT stocks_shortname, strategy_fit_prior, maturity, risk_level, confirmed_by, confirmed_at
FROM torn_stock_monthly_state
WHERE deleted = 0 AND state_status = 'CONFIRMED'
  AND effective_month = (SELECT max(effective_month) FROM torn_stock_monthly_state
                         WHERE deleted = 0 AND state_status = 'CONFIRMED')
ORDER BY stocks_shortname;
```

### 6.4 停止条件

编译通过 + 6.1/6.2 的功能验收全绿 + 6.3 数据验收无异常 + 无未闭环 P0/P1。

---

## 7. 明确不做事项

1. **不在 `torn_stock_strategy_feature_15m` 新增 RSI 列**（RSI 由指令触发时现算）；
2. **不修改 `Stock15mFeatureBuildService.FEATURE_VERSION`**（也不修改 `Stock15mBarBuildService.BUILD_VERSION`）；
3. **不停止每分钟 Torn API 采集**、不停止 `torn_stocks_history` 分钟写入、不停用大额交易消息 `sendGreatTradeChangeMsg`；
4. **不做人工确认与人工覆盖**：不保留 `confirmDraftStates(effectiveMonth, confirmedBy)` 入口，`manualOverride` 恒 `false`，不提供人工改风格入口；
5. **不把月度风格接进 α 任何决策路径**（α 不变量只有相位）；
6. **不做槽外机会**；
7. **不做年报 / 年度结算**；
8. **不新增独立消息平台**（告警走指令文案 + 应用日志）；
9. 不 drop 旧表 `torn_stock_strategy_feature`（破坏性操作，需独立变更单与用户确认）；
10. 不删除 `sys_setting` 中 `STOCK_PERSONALITY` 的数据行（仅代码退役）；
11. 不做 15m 特征全量重建、不做 15m 表列/索引变更、不做月度证据排除策略的规则版本升级（除非 4.2 的"待确认"项另行决策）；
12. 不调整既有评分参数与阈值数值（`BUY_SCORE_THRESHOLD`、`TAKE_PROFIT_SELL_SCORE_THRESHOLD`、`REBOUND_SELL_SCORE_THRESHOLD`、`QUICK_PROFIT_SELL_SCORE_THRESHOLD`、`NARROW_BAND_Z_DISCOUNT`、各 `StockPersonalityEnum` 数值）；
13. 不为私聊指令引入缓存层、不新增独立调度、不做管理后台 / 编辑 UI；
14. 不新增消息类型、报表表、对账表、审计表。

---

## 8. 关联文档

| 文档 | 关系 |
|---|---|
| `.ai/knowledge/stocks/stock_personality_monthly_calibration.md` | 月度风格规范（成熟度 60/120/240/365 天、六类判定顺序、风险投票与迟滞、证据窗口 365 天、§2.1 冷启动顺序、§9.2 自动确认条件） |
| `.ai/knowledge/stocks/vip_stock_alert_technical_design.md` | VIP 提醒与 15m bar/feature/round 主链路 |
| `.ai/knowledge/stocks/tornsy_stock_history_backfill_technical_design.md` | 分钟事实补数与派生数据重建（bar/feature 依赖链、`rebuildRange` 边界） |
| `.ai/knowledge/stocks/vip_stock_strategy_version_history.md` | 策略阈值与版本演进 |
| `.ai/knowledge/stocks/vip_stock_strategy_research_monthly_2026_09.md` | 月度研究口径来源 |
| `.ai/knowledge/stocks/vip_stock_virtual_portfolio_strategy.md` | 组合槽位与批次（本期不涉及） |
| `.ai/knowledge/stocks/vip_stock_alert_technical_design.md` §13.4 / §13.5 | A/B/C/D 交付与修复验收结论（原一次性验收文档已按生命周期删除） |
| `.ai/knowledge/stocks/stock_personality_full_history_2026_07.md` | 风格分类历史依据 |
| `.ai/prompts/system_prompt.md` | 工作流程与范围控制原则 |

---

## 9. 变更记录

| 版本 | 日期 | 变更 | 作者 |
|---|---|---|---|
| 1.0.0 | 2026-09-24 | 初稿：冻结"私聊指令 15m 化 + 月度风格接入"两章设计（数据映射、RSI 现算、下线清单与迁移顺序、月度表复活与自动确认、消费口径、回补指令、sys_setting 退役） | Bai |

---

## 附录 A：待确认事项

| 编号 | 待确认内容 | 影响范围 |
|---|---|---|
| Q1 | 15m 特征表当前行数（需求方口径约 87 万行）未在库上取数核实 | 3.2 成本论证的量化依据 |
| Q2 | 生产库是否存在 `torn_stock_monthly_state`，`DATABASECHANGELOG` 中 1.6.5 的 `drop_retired_stock_monthly_state_table` 是否已执行 | 4.1 迁移策略与验证场景 |
| Q3 | ~~新 Liquibase 版本目录名~~ **已确认：计划开发版本 2.0.0**（目录按发布时版本段确定） | 已确认（2026-09-24） |
| Q4 | ~~是否取回 `StockMonthlyEvidenceExclusionPolicy`~~ **已确认：不取回**；数据不完整月份一律保持 DRAFT、不生效，靠 §4.6 沿用规则过渡 | 已确认（2026-09-24） |
| Q5 | `torn_stocks_history` 自然分钟重复数据是否已按 `tornsy_stock_history_backfill_technical_design.md` §2.3 处置完毕 | RSI 现算与 bar 构建的去重语义 |
| Q6 | `torn_stock_strategy_feature` 现存量与保留窗口 | 3.5 阶段 6（drop 表）的独立变更单 |
| Q7 | 旧口径与 15m 口径在 35 支股票上的逐支差异清单 | 3.5 阶段 2 门禁 |
| Q8 | 回补指令命名与参数格式（`yyyy-MM#yyyy-MM`） | 4.7 |
| Q9 | 旧 `sys_setting.STOCK_PERSONALITY` 的 35 支实际内容 | 4.7 逐支对账 |
| Q10 | ~~`SWING_REVERSAL_BUY` 是否纳入月度门槛调整~~ **已确认：不纳入**，固定 50 分；月度 +10 只作用于 `SWING_LOW_BUY` | 已确认（2026-09-24） |
