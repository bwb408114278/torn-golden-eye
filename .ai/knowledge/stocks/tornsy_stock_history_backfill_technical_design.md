# Tornsy 股票历史缺口补正实施方案（超管范围指令 + 每日连续性巡检）

> **文档类型：** 开发实施方案（技术基线）  
> **适用项目：** Golden-Eye 1.2.14+  
> **状态：** 已实施且 Review 通过（`e562573`）；本文不构成生产回填执行授权
> **风险等级：** L3（生产历史事实表迁移、批量补数、第三方 HTTP、派生数据重建）  
> **业务时区：** `Asia/Shanghai`  
> **外部数据源：** `https://tornsy.com/api`，只允许使用 `m1` 分钟点接口

---

## 1. 需求与最终决策

### 1.1 目标

当前 `TornStocksManager.spiderStockData()` 每分钟第 5 秒调用 Torn API，写入 `torn_stocks_history`。应用宕机、调度未执行或 Torn API 临时失败都会形成分钟级缺口，继而影响：

```text
torn_stocks_history 缺分钟
→ 15 分钟 bar 的 sampleCount / 尾部新鲜度 / 连续性不足
→ 15m feature 的 30 天连续窗口被打断
→ strategyReady、月度状态证据、回放与后续 Shadow 数据质量下降
```

本期建设一个**补历史缺口**的轻量服务：当本地历史事实缺失已结束分钟时，从 Tornsy m1 读取真实分钟点，补入原有 `torn_stocks_history`；再复用既有 bar/feature 重建链恢复受影响派生数据。

### 1.2 已确认决策

1. 直接补写 `torn_stocks_history`；不新增外部报价表、导入批次表、任务表、Resolver 或报告表。
2. 给 `torn_stocks_history` 新增 `data_source` 字段，区分实时 Torn 采集与 Tornsy 回填。
3. `market_cap`、`investors` 改为允许 `NULL`；未知值**绝不能写 `0`**。
4. 每个 `(stocks_id, 自然分钟)` 仅允许一条有效事实。用户先处理现存自然分钟重复数据，再由 Liquibase 创建自然分钟部分唯一索引。
5. 本期只消费 Tornsy `m1` 数据。`m5/m15/h1/...` OHLC 不写入分钟表，不参与 bar/feature/策略。
6. 回填入口只有两个：**超管机器人指令**人工指定任意历史范围 `[start,end)` 执行一次补数；**每日巡检**（08:45，Asia/Shanghai）检查昨天自然日的分钟连续性，发现缺口才自动补数。不使用 `sys_setting` 开关、启动自动恢复、每小时 AUTO 或 13 个月 EXPERIMENT 环境变量。
7. 实际插入后，定向重建受影响的 15 分钟 bar/feature；不直接操作买卖、资金、Shadow、消息或月度状态。
8. 本期不建自动归档、历史删除、表分区。全量历史补数由超管按小范围 → 较大范围逐步扩大人工执行；容量与性能另行专题设计。
9. 不要求开发人员给出生产全量补数报告。仅保留 `TornStocksHistoryMapperTest` 这一项无法由 mock 证明的最小真实 PostgreSQL Mapper 测试，用于验证每日巡检的自然分钟聚合 SQL；部署/生产后数据验收由 AI 技术负责人使用 MCP 只读查询完成。

---

## 2. 已核验事实与约束

### 2.1 Tornsy m1 API

参考：<https://tornsy.com/api>。页面标记 API 文档版本 `1.2.0`、最后更新时间 `2022-10-18`；上线前仍必须进行小窗口真实探针，不把文档文字当作永久契约。

实测：

```text
GET https://tornsy.com/api/ass?interval=m1&limit=3

{
  "data": [
    [1786520040, "362.07", 15795177397],
    [1786520100, "362.05", 15795177397]
  ]
}
```

m1 数组口径：

| 数组下标 | 字段 | 处理 |
|---:|---|---|
| `0` | Unix epoch second | 必须整分钟；转换为 `Asia/Shanghai` 本地时间 |
| `1` | price | 必须为正 `BigDecimal` |
| `2` | total_shares | 必须为正 `Long` |
| `3`（可选） | market_cap | 存在且正数才写；否则 `NULL` |

Tornsy m1 不提供 `investors`，必须写入 `NULL`。

严禁：

- 用 `/api/stocks` 当前价格补历史；
- 用 Tornsy OHLC close 代替 m1；
- 用 OHLC 伪造 15 个分钟样本；
- 将未知 `market_cap`、`investors` 写成 `0`、当前值、前值或插值。

### 2.2 现有代码链路

```text
TornStocksManager.spiderStockData()                 [每分钟第5秒]
  → Torn API
  → torn_stocks（当前行情快照）
  → torn_stocks_history（分钟事实）
  → sendGreatTradeChangeMsg（大额交易消息）
  → calcStockFeature（旧分钟特征）

VIP 独立派生链：
torn_stocks_history
  → Stock15mBarBuildService
  → torn_stock_market_bar_15m
  → Stock15mFeatureBuildService
  → torn_stock_strategy_feature_15m
  → StockHistoryRebuildService
```

Tornsy 补数服务必须绕开 `TornStocksManager`：不得更新 `torn_stocks`，不得调用大额交易消息，不能推进旧分钟特征游标。

### 2.3 当前重复数据预检

已对本地 `golden-eye` 库执行只读检查（2026-08-12）：

```text
有效股票数：35
数据范围：2026-01-26 15:25:02 ～ 2026-08-12 16:32:06
自然分钟重复组：1,540
冗余行数：1,540
受影响股票：35 支全部受影响
重复时间范围：2026-07-14 09:46 ～ 2026-08-07 18:31
```

每支股票均有 44 组两条记录的自然分钟重复。样例：

```text
TSB | 2026-07-14 09:46
  2076845645494226946 @ 09:46:02.469201 = 1192.95
  2076845666365083649 @ 09:46:07.445369 = 1192.95
```

完整明细文件已生成，供用户处理：

MEDIA:C:\WorkSpace\Java\practice\torn-golden-eye\.hermes\output\torn_stocks_history_duplicate_minutes_20260812.psv

格式：

```text
stocks_id|stocks_shortname|minute_time|duplicate_count|id@reg_date_time=price,...
```

> 用户负责清理这 1,540 个自然分钟重复组。开发人员不得在本次功能代码、Liquibase 或测试中删除、合并、逻辑删除任何历史重复记录。

---

## 3. 总体设计

```text
正常实时采集（保持不变）
Torn API → TornStocksManager → torn_stocks_history
                              data_source=TORN_API

缺口补数（新增）
Tornsy m1 → TornsyStockHistoryBackfillService
          → 校验、股票映射、同分钟去重
          → INSERT ... ON CONFLICT DO NOTHING RETURNING 实际插入 slot
          → torn_stocks_history
             data_source=TORNSY_BACKFILL
             investors=NULL
             market_cap=API未提供时NULL
          → 收集实际插入分钟所属的15分钟桶
          → StockHistoryRebuildService.repairBackfilledHistory(...)
          → 15m bar / feature / round 数据修复；非终态 round 写为 REPAIRED_DATA_ONLY
          → 不进入生产策略消费
```

来源字段仅用于审计。运行时没有双数据源选择：数据库自然分钟唯一索引保证同一股票、同一分钟只有一条有效历史事实。

### 3.1 时间规则

```text
Tornsy epochSecond
→ Instant.ofEpochSecond(epochSecond)
→ ZoneId.of("Asia/Shanghai")
→ LocalDateTime（秒与纳秒均为0）
```

所有补数窗口使用左闭右开：

```text
[startInclusive, endExclusive)
```

稳定截止时间（人工历史补数）：

```text
manualStableEndExclusive = floorToMinute(now(Asia/Shanghai) - 30分钟)
人工提交的 endExclusive 必须早于该截止（end >= 截止视为 TOO_RECENT 拒绝）
```

人工历史补数必须保留最近 **30 分钟** 的稳定缓冲；它是人工操作，30 分钟比每日"昨日完整窗口"更适合规避实时采集延迟和当前分钟竞争。判定必须通过 `StockMarketClock.now()` 计算，禁止 `LocalDateTime.now()`。

每日巡检窗口固定为昨天完整自然日 `[昨天00:00, 今天00:00)`，理论分钟数 24 × 60 = 1440，不做"当前 - N 分钟"滚动计算。

---

## 4. Schema 与 Liquibase

### 4.1 文件

新增：

```text
src/main/resources/db/changelog/1.0.1-2.0.0/1.2.14/stocks-history-backfill.yaml
```

修改：

```text
src/main/resources/db/changelog/db.changelog-master.yaml
```

仅追加新 include。不得修改已执行的：

```text
src/main/resources/db/changelog/0.5.0/torn.yaml
```

### 4.2 迁移内容

迁移按如下顺序：

1. `market_cap` 删除 `NOT NULL`；
2. `investors` 删除 `NOT NULL`；
3. 新增 `data_source VARCHAR(32) NOT NULL DEFAULT 'TORN_API'`；
4. 对既有记录填充/保留 `data_source=TORN_API`，不重新采集、不改写原市场数值；
5. 更新三个字段的 PostgreSQL 中文 remarks；
6. 用户先完成自然分钟重复处置、部署前预检为 0 后，创建唯一索引：

```sql
CREATE UNIQUE INDEX uk_torn_stocks_history_stock_minute
ON torn_stocks_history (stocks_id, date_trunc('minute', reg_date_time))
WHERE deleted = 0;
```

7. 增加来源值约束：

```sql
ALTER TABLE torn_stocks_history
ADD CONSTRAINT ck_torn_stocks_history_data_source
CHECK (data_source IN ('TORN_API', 'TORNSY_BACKFILL'));
```

字段最终语义：

| 字段 | 定义 | 语义 |
|---|---|---|
| `market_cap` | `BIGINT NULL` | Torn API 正常写值；Tornsy 未提供则 `NULL` |
| `investors` | `INT NULL` | Torn API 正常写值；Tornsy 固定 `NULL` |
| `data_source` | `VARCHAR(32) NOT NULL DEFAULT 'TORN_API'` | `TORN_API` 或 `TORNSY_BACKFILL` |

### 4.3 部署前自然分钟重复预检

在用户处理重复数据后、Liquibase 部署前，开发人员只执行以下只读 SQL：

```sql
SELECT stocks_id,
       date_trunc('minute', reg_date_time) AS minute_time,
       COUNT(*) AS duplicate_count
FROM torn_stocks_history
WHERE deleted = 0
GROUP BY stocks_id, date_trunc('minute', reg_date_time)
HAVING COUNT(*) > 1
ORDER BY stocks_id, minute_time;
```

- 返回 0 行：允许执行 changelog；
- 非 0 行：停止部署并通知用户处理；不得通过自动删除历史行来让迁移通过。

Liquibase 在目标环境失败时，开发人员立即通知用户；用户按实际执行情况手工回滚/处理后重新部署。本期不建设 Liquibase 自动回滚逻辑，也不编写数据库集成测试来模拟迁移失败。

### 4.4 DO 与实时写入修改

修改：

```text
src/main/java/pn/torn/goldeneye/repository/model/torn/stocks/TornStocksHistoryDO.java
src/main/java/pn/torn/goldeneye/torn/model/torn/stocks/TornStocksDetailVO.java
```

`TornStocksHistoryDO`：

- 新增 `dataSource`，完整中文 Javadoc；
- `marketCap`、`investors` Javadoc 写明“外部补数未提供时允许 `NULL`，禁止以 `0` 代表未知”。

`TornStocksDetailVO.convert2HistoryDO()`：

```text
dataSource = TORN_API
```

必须显式设置，不能只依赖数据库默认值。

---

## 5. 新增/修改文件与职责

### 5.1 新增包

```text
src/main/java/pn/torn/goldeneye/torn/service/stocks/backfill/
```

| 文件 | 责任 | 边界 |
|---|---|---|
| `StockHistoryDataSourceEnum.java` | `TORN_API`、`TORNSY_BACKFILL` 编码与中文说明 | 禁止散落来源字符串 |
| `TornsyMinuteQuote.java` | 不可变 m1 quote record | 包含时间、价格、总股数、可空市值 |
| `TornsyMinuteQuoteParser.java` | JSON 数组解析和行级校验 | 非法行拒绝，不补值 |
| `TornsyStockHistoryClient.java` | Tornsy HTTP 请求、有限重试和分页 | 复用 `RestClient`，不使用 Torn API Key |
| `TornsyStockHistoryBackfillService.java` | 拉取、映射、批量存在性过滤、冲突安全写入、定向重建 | 不调用 `TornStocksManager` |
| `TornsyStockHistoryBackfillScheduler.java` | 超管人工范围回填调度入口 + 每日昨天连续性巡检入口 | JVM `AtomicBoolean` 防重入（人工与每日共用） |
| `TornsyStockHistoryBackfillStrategyImpl.java` | 超管 Bot 指令入口：参数解析与用户反馈，收敛到 Scheduler | 不直接注入回填 Service/Client/DAO/执行器 |

### 5.2 修改持久层

```text
src/main/java/pn/torn/goldeneye/repository/dao/torn/stocks/TornStocksHistoryDAO.java
src/main/java/pn/torn/goldeneye/repository/mapper/torn/stocks/TornStocksHistoryMapper.java
src/main/resources/mapper/torn/stocks/TornStocksHistoryMapper.xml
```

新增 Mapper 方法：

| 方法 | 用途 |
|---|---|
| `selectExistingMinuteSlots(stocksIds, start, end)` | 批量读取已经占用的 `(stocksId, naturalMinute)`，减少无效写入 |
| `insertBackfillReturningSlots(historyList)` | 用 `INSERT ... ON CONFLICT DO NOTHING RETURNING` 写入，返回实际插入 slot，匹配自然分钟表达式部分唯一索引 |
| `selectLatestHistoryTime()` | 启动/日志的历史进度观察 |

`insertBackfillReturningSlots` 要求：

```sql
INSERT INTO torn_stocks_history (...)
VALUES (...)
ON CONFLICT (stocks_id, date_trunc('minute', reg_date_time)) WHERE deleted = 0
DO NOTHING
RETURNING stocks_id, date_trunc('minute', reg_date_time)
```

必须与 Liquibase 唯一索引表达式和谓词完全一致。

同时修正现有：

```text
src/main/resources/mapper/torn/stocks/TornStocksHistoryMapper.xml
方法：selectHistoryPointsRange
```

增加：

```sql
AND deleted = 0
```

这样 `Stock15mBarBuildService` 构建 bar 时不会读取逻辑删除历史点。此改动直接保证唯一索引、缺口判断与派生数据构建对“有效记录”的语义一致。

### 5.3 禁止修改

- `TornStocksManager.spiderStockData()` 的实时 API、当前股票快照、大额交易消息和旧分钟特征逻辑；
- VIP 买卖、5 槽资金、Shadow、通知、`VIP_STOCK_*` 开关；
- `StockReplayRunner` 的只读回放；
- 15m bar、feature、round、月度状态、批次、通知表 Schema；
- 自动归档、删除、分区、NAS 路径和 Docker 挂载；
- NapCat 可靠性专题。

---

## 6. HTTP、解析与校验

### 6.1 HTTP

`TornsyStockHistoryClient`：

```text
baseUrl = https://tornsy.com/api
Accept = application/json
GET /{stocksShortname}?interval=m1&from={epochSecond}&to={epochSecond}&limit={pageLimit}
```

- 无 API Key；不复用 Torn key 池；
- 连接/响应超时默认 20 秒；
- HTTP 非 2xx、空 body、JSON 解析失败均视为当前股票/时间片失败；
- 单页最多 3 次有限退避重试；
- 日志仅输出股票、范围、页数、状态、耗时、行数；不输出完整响应；
- `from/to/limit` 边界和分页上限以部署前小窗口探针实际结论为准，不把文档示例的 1000 当作固定协议。

### 6.2 行级校验

每条 quote 必须满足：

```text
数组长度为 3 或 4
epochSecond % 60 == 0
转换后的时间属于 [requestStart, requestEnd)
时间早于 stableEndExclusive
price > 0
totalShares > 0
marketCap 缺失 → null；存在时 > 0
acronym 在当前 torn_stocks 中唯一映射
```

以下行拒绝且不入库：

```text
非数组、数组长度错、非数值、非整分钟、未来点、窗口外点、
价格/总股数非正、可选市值非正、股票映射缺失、
同一响应内同股票同分钟的价格或总股数冲突
```

不使用前值、后值、当前值或 OHLC 补偿失败行。

### 6.3 探针

部署前先执行真实小窗口探针：

```text
范围：最近 7 天
股票：3 支
写库：禁止
检查：接口结构、分页边界、m1整分钟、股票映射、
      Tornsy 与本地实时分钟的时间对齐和价格差异
```

探针不通过：停止发送人工范围指令；每日巡检发现缺口时回填失败仅记 ERROR，不产生其他副作用。

---

## 7. 缺口、写入、幂等

### 7.1 缺口定义

自然分钟唯一索引建立后，某股票某分钟是否缺失的定义为：

```text
在 torn_stocks_history 中不存在：
  stocks_id = X
  AND deleted = 0
  AND date_trunc('minute', reg_date_time) = minute(T)
```

应用层先按股票集合、时间范围批量读取已存在分钟；数据库索引是最终事实约束。

### 7.2 写入

候选先按 `(stocksId, minuteTime)` 内存去重，然后调用冲突安全 Mapper 批量写入。禁止用普通 `saveBatch()` 作为最终幂等方案。

| 字段 | Tornsy 补数写法 |
|---|---|
| `stocks_id` | 当前 `torn_stocks` 中映射 ID |
| `stocks_name` | 当前股票名称快照 |
| `stocks_shortname` | 当前股票简称 |
| `current_price` | Tornsy m1 price |
| `total_shares` | Tornsy m1 total_shares |
| `market_cap` | 数据源提供且正数则写；否则 `NULL` |
| `investors` | 固定 `NULL` |
| `reg_date_time` | `Asia/Shanghai` 标准分钟 |
| `data_source` | `TORNSY_BACKFILL` |

禁止：

```text
UPDATE 任何已有历史行
market_cap = 0
investors = 0
使用当前行情填历史字段
调用 sendGreatTradeChangeMsg()
调用 TornStocksManager.calcStockFeature()
```

### 7.3 幂等

```text
JVM AtomicBoolean 防本实例重入
+ 人工结束时间 30 分钟稳定截止 / 每日巡检固定昨天完整窗口
+ 批量存在性查询
+ 自然分钟部分唯一索引
+ INSERT ... ON CONFLICT DO NOTHING
+ 单股票 × 单时间片短事务
```

`ON CONFLICT DO NOTHING` 计为“已存在跳过”，不是错误。数据库连接、Mapper 执行、非唯一约束异常必须向上抛出；当前时间片失败，后续扫描重试。

---

## 8. 超管范围指令、每日连续性巡检与派生数据补齐

### 8.1 超管人工范围指令

```text
同步Tornsy股票数据#2026-07-01 00:00:00#2026-07-02 00:00:00
```

- 超管指令（`isNeedSa=true`），消息正文 `start#end`，时区 `Asia/Shanghai`，格式 `yyyy-MM-dd HH:mm:ss`；
- 范围语义 `[startInclusive, endExclusive)`；`end` 必须早于 30 分钟稳定截止，否则回复未受理（TOO_RECENT）；
- 提交结果为 `BackfillSubmission`：`ACCEPTED / NOT_PROD / INVALID_RANGE / TOO_RECENT / ALREADY_PROCESSING / EXECUTOR_REJECTED`；
- `ACCEPTED` 仅表示已投递专用执行器，Bot 立即回复"已受理"，不在消息线程同步执行长范围 HTTP、分钟入库或 feature 重算；
- 不依赖任何 `sys_setting` 开关，不需要重启或"刷新缓存"；
- 小范围验证 → 较大范围验证 → 全量历史补数均通过本入口人工执行。

### 8.2 每日昨天连续性巡检

每天 **08:45（Asia/Shanghai）** 执行一次（`cron = 0 45 8 * * ?`）：

```text
巡检窗口 = [昨天 00:00:00, 今天 00:00:00)，理论分钟数 1440
→ 读取全部有效股票清单（TornStocksDAO.list()）
→ 一条聚合 SQL 统计每支股票在窗口内的 distinct 自然分钟数
  （COUNT(DISTINCT date_trunc('minute', reg_date_time))，不区分 data_source）
→ 任意有效股票 count < 1440 判定缺口（缺失 SQL 行解释为 0）
→ 仅此时投递 stockBackfillExecutor 回填整个昨天窗口
→ 无缺口：直接结束，不请求 Tornsy、不写表、不重建 bar/feature
```

选择 08:45：昨天窗口已稳定结束、避开 08:30 的 ELO 预拉取与股票日报、不与实时采集的每分钟第 5 秒竞争。巡检失败（failedSlices、HTTP 失败、重建异常）只记录 ERROR，不永久关闭；下一日仍会再次巡检。每日巡检只兜底近期缺口，不替代人工范围指令，也不自动回填数月/数年历史。

聚合查询为 `TornStocksHistoryMapper.selectMinuteCountsByStocksAndRange`（单条 SQL、无 N+1），配置属性 `StockHistoryBackfillProperty` 仅保留 `pageLimit` 技术参数。

### 8.3 15m bar 如何补齐

这是补数后的必要闭环，流程如下：

```text
1. Tornsy m1 成功插入 torn_stocks_history
2. 每条实际插入记录向下对齐：
   bucketStart = Stock15mBarBuildService.alignToBucket(regDateTime)
3. 对 bucketStart 去重、排序，合并相邻桶为 [start, end) 区间
4. 调用 StockHistoryRebuildService.rebuildHistory(start, end)
5. 对每个桶按完整数据义务判断：
   - bar 缺失/版本不一致：buildBars(bucket)
   - bar 存在但 feature 缺失：buildFeatures(bucket)
   - bar + feature 存在但 round 缺失/可重试：标记 round 为 `REPAIRED_DATA_ONLY`
   - 三者完整且版本一致：跳过
6. 不执行 StockRoundTransactionService；不直接创建交易、Shadow、通知、冷却或月度状态
```

现有实现细节：

- `Stock15mBarBuildService.buildBars(bucketStart)` 从统一 `torn_stocks_history` 读取 `[bucketStart, bucketStart+15m)` 的**实际**分钟点；按同一采样时间去重，计算 first/last/low/high、`sampleCount`、`tailGapSeconds`，然后 UPSERT `torn_stock_market_bar_15m`。
- bar 仍必须满足既有正式标准：`sampleCount >= 10`，且最后实际样本不早于 `barEnd - 5分钟`。补数不会虚构样本；若 Tornsy 也缺数据，bar 仍不可用。
- `Stock15mFeatureBuildService.buildFeatures(bucketStart)` 读取当前 bar 和此前最多 30 天 bar，以当前及过去可见数据计算 MA、Z-score、收益、30日区间等，UPSERT `torn_stock_strategy_feature_15m`。
- 特征的 `strategyReady` 要求完整 30 天、即 2880 个 15 分钟 bar 严格连续且可用。补上局部历史数据能恢复被缺口打断的连续窗口；但若仍有任意 bar 缺失/不可用，就继续 `strategyReady=false`，不放宽规则。
- Tornsy 回填调用 `StockHistoryRebuildService.repairBackfilledHistory(...)`；其目标终态不是 `READY`：`COMPLETED` / `FAILED_FINAL` 保持原终态，其余既有或新建 round 标记为 `REPAIRED_DATA_ONLY`。生产 pending SQL 使用显式可消费状态白名单排除它，`VipStockAlertScheduler` 也防御性跳过它；因此历史补数绝不进入交易、Shadow、通知、资金、持仓或月度状态链。

重建资源限制：

```text
仅重建实际插入分钟影响的桶
相邻桶合并执行
单次最多重建1天桶；超出则切片
不得因为补一条分钟数据执行13个月全量 rebuild
```

### 8.4 后续策略链边界

补数和 bar/feature 重建不自动：

```text
开启 VIP_STOCK_NEW_ENTRY_ENABLED
开启 VIP_STOCK_FORMAL_NOTICE_ENABLED
开启 VIP_STOCK_DAILY_SUMMARY_ENABLED
改变 VIP_STOCK_RULE_MODE
重算/确认月度状态
运行正式回放
发送任何 BUY/SELL/大额交易消息
```

补数完成后，月度 DRAFT 重算、20亿×5槽长窗口回放、Walk-forward/holdout/压力测试和前向 Shadow 仍需要单独授权，并继续遵守 GATE-1 至 GATE-4。

---

## 9. 测试与验收

### 9.1 开发单元测试

新增：

```text
src/test/java/pn/torn/goldeneye/torn/service/stocks/backfill/
```

所有测试类和每个 `@Test` 均使用中文 `@DisplayName`，测试类具有职责 Javadoc。

| 测试 | 验收点 |
|---|---|
| parser | m1 3/4 元数组、可选市值、非法数组、非数值、非整分钟、非正价格/总股数 |
| client | URI 参数、HTTP 非2xx、空 body、解析失败、有限重试 |
| backfill service | 已有分钟跳过；缺失分钟插入；`investors=null`；市值未知为 `null`；来源为 `TORNSY_BACKFILL`；不调用消息/旧特征入口 |
| scheduler | 每日巡检非生产跳过/全连续不请求 Tornsy/缺口投递专用执行器；人工 30 分钟稳定截止与提交结果；人工与每日共用防重入；failedSlices/异常/拒绝后释放 |
| 策略入口 | 指令声明、合法范围提交与已受理反馈、参数错误走既有格式错误、拒绝原因可区分 |
| 分钟计数聚合 Mapper | 真实 PostgreSQL：1440 distinct 计数、重复原始行不虚增、缺失分钟 <1440、`[start,end)` 边界、逻辑删除不计入 |
| 定向重建编排 | 插入分钟映射到正确15分钟桶；只调用受影响区间；不创建交易/通知/月度状态 |

不扩展真实 PostgreSQL 集成测试矩阵：只保留上述 `TornStocksHistoryMapperTest` 聚合 SQL 契约测试；不为 Liquibase 失败或生产数据补数写模拟测试代码。

### 9.2 部署后 MCP 数据验收（AI 执行）

不要求开发人员提供全量补数生产报告；生产部署/人工范围补数或每日巡检回填执行后，由 AI 技术负责人使用 MCP 或等效只读数据库查询核验：

1. 用户清理后，有效 `(stocks_id, naturalMinute)` 重复组为 0；
2. `market_cap`、`investors` 可空，`data_source` 非空且默认 `TORN_API`；
3. 既有历史行 `data_source=TORN_API`，无未知来源；
4. `uk_torn_stocks_history_stock_minute` 与 `data_source` CHECK 存在；
5. 经明确授权的最小生产补数演练新增行均为 `TORNSY_BACKFILL`；
6. 演练新增行 `investors IS NULL`，缺失 `market_cap` 为 `NULL`，未出现未知值 `0`；
7. 演练范围不存在同股票同自然分钟重复；
8. 插入分钟所属 15m bar、feature、round 已按第 8.3 节恢复；
9. 没有历史大额交易消息、BUY、SELL、批次或月度状态副作用。

Liquibase 在目标环境执行失败时，开发人员立刻通知用户；由用户手工回滚/处理并重新部署。

### 9.3 实际 API 探针

探针只请求 3 支股票最近 7 天，不写库。验收：接口结构、分页边界、时间对齐、股票映射和 Tornsy/本地重叠价格合理性。探针不通过，外部补数开关保持 false。

### 9.4 开发验证命令

```bash
JAVA_HOME="C:\\Program Files\\Java\\jdk-21" mvn.cmd compile -q -DskipTests -Dmaven.compiler.showDeprecation=true
JAVA_HOME="C:\\Program Files\\Java\\jdk-21" mvn.cmd test -Dtest="TornsyMinuteQuoteParserTest,TornsyStockHistoryBackfillStrategyImplTest,TornsyStockHistoryBackfillSchedulerTest,TornsyStockHistoryBackfillServiceTest,TornStocksHistoryMapperTest,StockHistoryRebuildServiceTest,Stock15mFeatureBuildServiceTest,VipStockAlertSchedulerTest,StockSchedulingConfigurationTest"
JAVA_HOME="C:\\Program Files\\Java\\jdk-21" mvn.cmd test -q
git diff --check
```

---

## 10. 运行日志、发布与回滚

### 10.1 运行日志（不生成补数报告文件）

回填为人工指令/每日巡检异步执行，开发人员无法也不应预先给出生产执行报告。因此本期：

- 不新增报告表；
- 不生成 `.hermes/output`、JSON、CSV 或其他补数报告文件；
- 不把本地文件作为生产补数完成依据；
- 不要求开发人员提交生产补数结果。

生产仅输出必要的结构化日志：

```text
trigger = MANUAL / DAILY_INSPECTION
requestedStart / requestedEnd
missingStockCount / minimumMinuteCount（巡检发现缺口时）
stocksShortname / timeSlice
sourceRows / validRows / existedSkippedRows / insertedRows / rejectedRows / failedSlices
affectedBucketCount / rebuiltBucketCount
failureReason（失败时）
```

不得记录完整 HTTP 响应、密钥或敏感配置。实际完成情况以数据库和第 9.2 节 MCP 验收为准。

### 10.2 发布顺序

```text
1. 用户处理自然分钟重复数据
2. 开发部署前执行重复预检，确认0行
3. 部署 Schema / 代码（部署或重启后不因环境变量自动请求 Tornsy）
4. 执行 Tornsy 小窗口探针（不写库）
5. 次日 08:45 观察每日巡检日志：昨天全连续时不请求 Tornsy
6. 用户明确决定后，超管发送最小范围人工指令演练，AI 通过 MCP 核验
7. failedSlices=0 才可逐步扩大人工历史范围
8. 后续月度重算与回放另行授权
```

### 10.3 回滚

| 情况 | 操作 |
|---|---|
| Tornsy 协议/质量异常 | 停止发送人工指令；实时 Torn 采集继续；每日巡检缺口回填失败只记 ERROR，下一日仍巡检 |
| 人工/每日回填资源压力 | 依赖 JVM 防重入与专用执行器隔离；必要时重启进程清空队列（不占用实时/VIP 线程） |
| 回填中断 | 人工按原窗口重新提交；已插入分钟通过唯一约束跳过 |
| 补数数据经人工确认错误 | 单独提交按 `data_source=TORNSY_BACKFILL` + 精确股票/时间范围的清理方案；未经授权不得 DELETE |
| Liquibase 失败 | 立即通知用户，由用户手工回滚/处理后重跑 |

---

## 11. 生命周期与明确不做事项

### 11.1 本期性能限制

```text
缺口查询：全股票批量，禁止 N+1
分钟连续性巡检：单条聚合 SQL 统计全部股票 distinct 自然分钟
HTTP 并发：回填仅运行于 stockBackfillExecutor（单并发 + 队列1）
写入：股票×时间片短事务
每日巡检补数：仅昨天完整自然日窗口
人工补数：任意历史范围，结束时间受 30 分钟稳定截止保护
重建：仅实际插入分钟影响桶，单次最多1天桶
```

外网调用不进入 VIP 轮次事务，也不能阻塞 Torn 正常每分钟采集。

### 11.2 生命周期后续专题

全量历史补数会显著增加 `torn_stocks_history` 数据量，但归档/分区需要基于真实容量、索引大小、补数耗时和回放性能设计。本期只观测：

```text
表与索引大小
按月、按 data_source 行数
补数查询耗时
15m 重建耗时
回放输入加载耗时
```

未单独审批前：**不归档、不删除、不分区、不移动历史分钟数据。**

### 11.3 明确不做

- 不新增外部原始数据表、任务状态表、报告表、Resolver；
- 不覆盖、更新、删除已有有效分钟行；
- 不用 Tornsy OHLC；
- 不把未知值写为 `0`；
- 不修改 Torn 实时采集和当前行情；
- 不发送历史消息；
- 不自动改 `VIP_STOCK_*` 开关；
- 不实现自动归档、物理删除、表分区、NAS 目录；
- 不新增除分钟聚合 Mapper 契约外的真实 PostgreSQL 集成测试，也不要求生产补数报告。

---

## 12. 完成与停止条件

1. 用户处理完成自然分钟重复，部署前预检为 0；
2. Liquibase 可安全加入可空字段、来源字段、自然分钟唯一索引和 CHECK；失败时立即通知用户手工处理；
3. Tornsy m1 只能补缺、不能覆盖；
4. `market_cap/investors` 未知时为 `NULL`，绝无 `0`；
5. 成功插入点可定向恢复 15m bar/feature/round，且保持严格数据质量门禁；
6. 不产生交易、通知或月度状态副作用；
7. 编译和聚焦单元测试通过；
8. 生产执行后通过第 9.2 节 MCP 数据验收；
9. 无本轮 P0/P1。

开发人员提交 diff、编译/单元测试结果和演练前检查清单；AI 技术负责人 Review 后，用户再决定是否执行首次生产人工范围演练。开发人员不得自行执行生产历史补数。

---

## 13. 参考

- Tornsy API：<https://tornsy.com/api>
- `.ai/knowledge/stocks/vip_stock_business_handoff.md`
- `.ai/knowledge/stocks/vip_stock_alert_technical_design.md`
- `src/main/java/pn/torn/goldeneye/torn/manager/torn/stocks/TornStocksManager.java`
- `src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/Stock15mBarBuildService.java`
- `src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/Stock15mFeatureBuildService.java`
- `src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/StockHistoryRebuildService.java`
- `src/main/java/pn/torn/goldeneye/repository/dao/torn/stocks/TornStocksHistoryDAO.java`
- `src/main/resources/mapper/torn/stocks/TornStocksHistoryMapper.xml`
- `src/main/resources/db/changelog/0.5.0/torn.yaml`
