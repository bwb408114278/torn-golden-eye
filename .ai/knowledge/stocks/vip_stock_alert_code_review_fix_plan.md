# VIP股票提醒第六轮Review遗留问题（一次性）

> Review日期：2026-07-31  
> Review范围：`6c48edd..a328b51`（第五轮修复）  
> 最终基准：`.ai/knowledge/stocks/vip_stock_alert_technical_design.md`  
> 结论：**第五轮核心交易修复已闭环，范围收敛有效。当前部署目标是已执行历史Liquibase的既有环境，因此历史空库顺序问题不构成本次发布P0；日报仍存在陈旧行情与换行错误，本轮Review不通过，正式买卖提醒开关不得开启。**

---

## 1. 最终问题分级

```text
P0 × 0
P1 × 2：日报会输出字面量%n；陈旧历史行情可能被展示为完整权益
P2 × 2：真实数据库发布门禁被过度删除；同轮平仓测试未覆盖事务编排接入
```

---

## 2. 部署基线纠正：历史空库顺序不构成本次发布阻断

当前发布目标不是全新环境。除本次股票功能外，既有Liquibase历史changeSet已经在目标环境执行。

当前master文件确实按以下顺序加载：

```text
0.3.0/oc.yaml
0.3.0/setting.yaml
```

若从零执行，`oc.yaml`会在`group_admin_ids`创建前引用该列，空库验证会失败。但这不等于既有生产环境升级失败。

对当前同步数据库的`databasechangelog`只读核验显示，历史实际执行顺序为：

```text
orderexecuted=32  0.3.0/setting.yaml::split_faction_admin::Bai
orderexecuted=48  0.3.0/oc.yaml::split_faction_admin::Bai
```

说明历史部署时的有效执行顺序是先创建`group_admin_ids`，再执行OC字段迁移。当前master很可能在历史部署后发生过文件顺序调整。

Liquibase按`id + author + filename`识别已执行changeSet。既有环境中的这两条记录不会因当前include顺序重新执行，因此：

- 历史空库顺序问题不构成本次股票增量部署P0；
- 不应为了本次股票功能主动改写已部署的历史0.3.0 changeSet；
- 如果未来需要支持全新空库安装，应另开维护任务修复完整bootstrap链路；
- 本次正确门禁是：从“历史功能已部署、股票changeSet未执行”的生产基线验证股票增量迁移。

当前开发数据库已执行股票changeSet，不能据此判断正式环境也已部署股票功能；正式部署状态以用户确认和正式库`databasechangelog`为准。

---

## 3. P1问题

### P1-1 日报缺行情明细会输出字面量`%n`

**位置**

`StockDailySummaryService.java:649-655`

当前实现：

```java
return "- 缺失行情：" + String.join("、", formal.missingPriceStocks()) + "%n"
        + "- 可用现金及预留资金：" + formal.cashAndReserved().toPlainString() + "%n";
```

该字符串随后作为`String.format`的`%s`参数传入。Java不会再次解释参数内部的`%n`。

实际消息形态：

```text
- 缺失行情：TCC、MUN%n- 可用现金及预留资金：120.00%n- 昨日买入：...
```

主Review使用JShell实证：

```text
String.format("A%sB", "X%nY") -> AX%nYB
```

**最小修复**

- 使用真实换行符，或由外层格式串承载`%n`；
- 增加1个消息文本行为测试；
- 断言文本包含分行明细且不包含字面量`%n`。

### P1-2 任意陈旧历史行情可能被当作完整权益

技术方案`12.5`要求开放仓位必须具有“满足新鲜度要求”的实际行情。

当前`TornStockMarketBar15mMapper.xml:99-123`只限制：

```sql
bar_start_time <= cutoffTime
```

然后选取历史上最近一条可用bar，没有最早允许时间，也没有与当前结束桶的连续性约束。如果行情采集停滞数小时或数天，日报仍可能展示“完整权益”。

同时`StockDailySummaryService.java:279-280`把：

```text
marketClock.currentEndedBucket()
```

写入`priceAsOf`，而不是实际选中bar的时间，会掩盖价格陈旧事实。

**最小修复**

不要建设新行情状态框架，只做最小事实校验：

1. 明确日报允许的行情新鲜度阈值；
2. SQL增加最早允许时间，或服务层验证选中bar时间；
3. 超过阈值的股票进入`missingPriceStocks`并令`equity=null`；
4. `priceAsOf`使用实际参与估值bar中的最早时点，不能使用目标桶冒充实际行情时点；
5. 增加“存在历史可用bar但已过期时权益仍为null”的行为测试。

---

## 4. P2问题

### P2-1 收敛时删除了全部真实数据库发布门禁

删除复制生产SQL、源码/XML/YAML字符串契约测试是正确的，但同时删除了：

- `StockPortfolioDatabaseUpsertIntegrationTest`
- `StockSignalEventObservationResultIntegrationTest`
- `GoldenEyeApplicationTests`
- 其他数据库持久化门禁

当前股票功能没有真实Mapper/PostgreSQL测试，也没有隔离的核心事务门禁。

**最小恢复要求**

只恢复2个隔离数据库门禁，不恢复旧测试膨胀：

1. **真实Mapper UPSERT**
   - 调用生产`barMapper.upsertBar()`和`featureMapper.upsertFeature()`；
   - SQL只用于查询结果/Schema，不复制生产UPSERT；
   - 验证首次INSERT自动生成ID、相同唯一键UPDATE保留ID。

2. **核心事务**
   - 隔离测试库或Schema；
   - 禁用NapCat、Lark、WebSocket和调度；
   - 验证一个`event → batch → slot → notice`事务和一个异常回滚场景。

本次应验证“既有历史基线 → 股票changeSet”的增量迁移。全新空库bootstrap属于独立维护门禁，不作为本次股票发布阻断。

### P2-2 同轮平仓测试只覆盖过滤工具函数

`StockRoundExitGuardTest`证明过滤算法正确，但没有覆盖：

```text
executeRound → excludeFormalExitStocks → acceptCandidates → batch持久化
```

生产代码人工审查确认调用接入正确，因此原业务P0已闭环；但建议增加1个轻量编排行为测试，禁止恢复源码字符串顺序测试。

---

## 5. 已正确闭环

### 5.1 同轮正式平仓禁止重新开仓

`StockRoundTransactionService.java:164-179`在候选接纳前调用`StockRoundExitGuard`。过滤器只收集本轮实际成交且`ledgerType=FORMAL`的股票，Shadow平仓不影响正式候选。

### 5.2 日报结构事实已补齐

已增加：

- `cashAndReserved`
- `missingPriceStocks`
- `priceAsOf`
- 无开放仓位时权益等于现金
- 缺失股票按stocksId排序
- 禁止用投入成本替代缺失行情

但P1-1和P1-2仍需修复。

### 5.3 月度状态API已收敛

已删除：

```text
confirmDraftStates(LocalDate)
autoConfirmDraftStates(LocalDate)
```

只保留需要真实确认人的`confirmDraftStates(LocalDate, String)`。该API目前仍无生产调用者，属于后续维护问题，不作为本轮发布阻断。

### 5.4 回放与契约测试已收敛

- 完整删除无入口`alert/replay`生产包及测试；
- HEAD生产代码无回放引用；
- `ContractTest.java`为0；
- 未发现`Files.readString()`或DOM解析Mapper的源码/XML契约测试。

### 5.5 收敛规模

```text
505 insertions
4101 deletions
生产源码：646 → 630
测试源码：78 → 50
@Test：428 → 362（源码统计）
```

收敛方向正确，没有继续扩展研究能力。

---

## 6. 验证结果

### 6.1 JDK 21编译

```text
mvn.cmd clean compile -DskipTests
630 source files
BUILD SUCCESS
```

### 6.2 股票聚焦测试

```text
Tests run: 186
Failures: 0
Errors: 0
BUILD SUCCESS
```

### 6.3 全量测试

主Review在排除并发Maven进程对`target`目录的干扰后重新执行：

```text
mvn.cmd clean test
Tests run: 368
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

子Agent曾在并发Maven污染`target`期间得到33个`NoClassDefFoundError`；该结果不可作为最终门禁。干净重跑已通过。

不过全量测试仍连接现有开发数据库、NapCat和Lark，因此它不是合格的隔离CI门禁。

### 6.4 PostgreSQL核验

现有开发数据库列定义：

```text
torn_stock_market_bar_15m.id
= nextval('torn_stock_market_bar_15m_id_seq'::regclass)

torn_stock_strategy_feature_15m.id
= nextval('torn_stock_strategy_feature_15m_id_seq'::regclass)
```

新增日报行情查询使用索引：

```text
idx_stock_market_bar_15m_stock_time_desc
```

但开发库股票业务表均为0行，不能证明真实首次UPSERT和核心事务。

### 6.5 部署基线核验

```text
历史实际执行顺序：
0.3.0/setting.yaml::split_faction_admin  orderexecuted=32
0.3.0/oc.yaml::split_faction_admin       orderexecuted=48
```

从零执行当前master会暴露历史顺序缺陷，但目标环境不是空库，历史changeSet不会重跑。该缺陷降级为独立维护事项，不计入本次P0。

本次发布前仍应在生产基线副本或等价数据库上验证：只执行尚未部署的股票changeSet，并确认既有表和数据不受影响。

### 6.6 静态检查

```text
git diff --check 6c48edd..HEAD
通过
```

本Review只更新一次性清单，未修改Java、XML或Liquibase代码。

---

## 7. 最小下一步

只处理以下事项，不再扩展功能：

1. **P1**：修复日报字面量`%n`；
2. **P1**：实现最小行情新鲜度与真实`priceAsOf`；
3. **P2**：恢复2个隔离数据库门禁；
4. **P2**：增加1个同轮平仓编排行为测试；
5. **部署验证**：在历史功能已部署、股票changeSet未执行的基线副本上验证股票增量迁移。

在两项P1完成前，正式买卖提醒开关不得开启。Shadow阶段可继续保持所有开关受控。

本文件仍为一次性修复清单。全部问题闭环并通过下一轮Review后删除，不恢复到`file_location.md`索引。
