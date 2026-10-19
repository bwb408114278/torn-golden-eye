# 股票实时采集中断处置与容错整改技术方案

## 元信息
- 文档类型：技术实施方案（故障整改 + 长期基线对齐）
- 适用项目：Golden-Eye
- 适用版本：1.8.0及以上
- 最后更新：2026.10.19
- 维护人：Bai
- 状态：已实施待验收

---

## 1. 问题与结论

### 1.1 现象

2026-10-19 08:10 的股票日报推送内容为“数据不足（缺失行情：TCT）”，两个 α 组合的唯一持仓 TCT 均未给出估值。

### 1.2 事实时间线（Asia/Shanghai，来自 Loki 与库表核对）

| 时间 | 事实 | 证据 |
| --- | --- | --- |
| 06:56:05 | 采集轮开始，06:59:15 失败（190s） | ERROR `股票实时采集-异常, plannedMinute=2026-10-19T06:56` |
| 07:00:05 | 此后连续 7 轮，每轮开始 05s、结束 09:05，单轮恒为 540s | 8 条同一文案的 ERROR，plannedMinute 依次 06:56/07:00/07:10/.../08:00 |
| 07:03-08:06 | 活跃度采集占用默认调度器线程约 63 分钟 | 350 条 `activity-collect-*` 线程上的 `/faction/{id}/members` 失败，每批 50 条约 9 分钟 |
| 08:09:05 | 最后一轮采集失败，06:56-08:09 共丢失 74 个自然分钟 | 同上 |
| 08:10:00 | 日报触发，08:12:37 完成发送 | 通知审计 `D20261019081237795S` |
| 08:12:37 | 估值窗口内无可用 15m bar，equity fail-closed | bar 07:00/07:15/07:30/07:45 缺失；08:00 bar 仅 3 个样本（可用门限 10） |
| 事后 | 人工 Tornsy 补齐 76 个分钟事实，07:00-07:45 四根 bar 重建为 35/35 可用 | `data_source=TORNSY_BACKFILL` |

### 1.3 根因（三条，缺一都不会造成本次结果）

**R1 单次失败被放大 9 倍。** `/torn` 走 V2 通道，一次逻辑请求 = 换 Key 3 次 x 单 Key 内 HTTP 重试 3 次 = 最多 9 次尝试，且没有任何时间预算，实测每次尝试约 60s，单轮上限 540s。采集 cron 周期是 60s，一轮超时就把整条分钟序列吃掉 9 分钟。

**R2 失败原因与耗时不可观测。** 放弃文案写的是“在因Key失效重试 3 次后仍然失败”，但实际触发条件是任意失败（连接异常、读超时、空响应、错误码）。ERROR 行只有 plannedMinute 与 startedAt，没有 elapsedMs、没有尝试次数、没有最后错误类型，只能靠人工比对 8 条日志的时间差才能算出 540s。

**R3 默认调度器被长任务占用。** `@Scheduled` 默认单线程 `taskScheduler`（poolSize=1）上同时挂着活跃度采集、Tornsy 巡检、日报、VIP 订阅与通知。活跃度采集在方法内 `CompletableFuture.allOf(...).join()`，即整个采集时长都压在调度线程上；本次故障下它独占该线程 63 分钟，同窗口排队的其它定时任务只能等它释放。

### 1.4 结论

日报文案本身没有错：估值在缺失行情时 fail-closed 是正确的。要修的是**采集侧把一次外部依赖故障放大成 74 分钟数据空洞**的能力边界，以及**故障现场不可自解释**的日志契约。

---

## 2. 现状（代码级事实）

### 2.1 请求重试结构

| 层 | 位置 | 次数 | 单次上限 | 触发条件 |
| --- | --- | --- | --- | --- |
| Key 轮换 | `TornApiImpl#executeWithRetry` | `MAX_RETRIES=3` | 无 | 任意 retry 结果 |
| HTTP 重试 | `TornApiImpl#executeWithHttpRetry` | `HTTP_RETRY_COUNT=3`（递归） | 无显式配置 | 任意异常 |
| 请求超时 | `RestClient.builder()` | - | 无显式配置 | 由底层客户端/对端决定 |

`/torn` 由 `TornStocksDTO implements TornReqParamV2` 决定走 `executeV2Request`，因此两层叠加为 9 次尝试；V1 通道只有 Key 轮换一层。两条通道都没有总预算，失败耗时随尝试次数线性增长。

`TornApiKeyConfig#getEnableKey` 在无空闲 Key 时返回 null（不阻塞），`executeWithRetry` 立即放弃——本次不是 Key 池饥饿，日志中没有“无法获取可用的API Key”。

### 2.2 空响应处理

`TornApiImpl` 在网络错误、空 body、错误码这三类情况下都可能返回 null。`TornStocksManager#spiderStockData` 拿到 null 后直接进 `upsertStocksSnapshot`，第一行 `resp.getStocks()` 抛 NPE，异常信息不含 plannedMinute 之外的任何上下文，看起来像代码缺陷而不是外部依赖故障。

### 2.3 调度资源分配

| 任务 | cron | 调度器 | 阻塞调度线程 |
| --- | --- | --- | --- |
| 股票实时采集 `spiderStockData` | `5 * * * * ?` | `realtimeStockScheduler`(1) | 是，正常约 1s，故障 540s |
| VIP 15m 轮次 | `10 * * * * ?` | `vipStockRoundScheduler`(1) | 是 |
| α 年度结算 | `0 5 0 1 1 *` | `stockAnnualSettlementScheduler`(1) | 是 |
| 活跃度采集 `collectActivity` | `0 */15 * * * *` | **默认 `taskScheduler`(1)** | 是，`allOf().join()`，本次 63 分钟 |
| Tornsy 每日巡检 | `0 0 7 * * ?` | 默认 `taskScheduler`(1) | 是 |
| 股票日报 | `0 10 8 * * *` | 默认 `taskScheduler`(1) | 是 |
| VIP 订阅 / VIP 通知 | `0 */5`、`10 */5` | 默认 `taskScheduler`(1) | 视实现 |

### 2.4 日志契约现状

- 采集失败：`股票实时采集-异常, plannedMinute=, startedAt=`，无耗时、无阶段、无尝试次数。
- 请求放弃：`Torn请求 {} 在因Key失效重试 3 次后仍然失败。`，文案把任意失败描述成 Key 失效，把常量 3 写死在文案里。
- 无中间退避日志，无法判断是“9 次都超时”还是“9 次都立即失败”。

---

## 3. 目标与非目标

### 3.1 目标

- **G1** 单轮采集失败的最坏耗时压缩到采集周期以内，任何一次 Torn API 故障最多损失 1 个自然分钟，而不是 9 个。
- **G2** 失败现场自解释：一行 ERROR 即可回答“哪一分钟、卡在哪个阶段、耗时多少、几次尝试、最后什么错误”。
- **G3** 共享调度器不再被任何单次采集任务长期占用，迁移后仍保持“同域任务串行、跨域互不影响”的现状语义。
- **G4** 所有阈值集中为常量，可按现场表现单点调整，不需要改结构。

### 3.2 非目标

- **N1** 不新增告警链路（Bot 群消息、状态机、阈值表）。告警归属 Grafana 邮件规则，本轮不写代码、不改规则。
- **N2** 不新增数据表、迁移脚本、配置开关与业务功能。
- **N3** 不改动 `TornApi` 接口签名、不改动 15m bar/feature/轮次/α 的任何业务口径。
- **N4** 不改动日报 fail-closed 语义与“数据不足（缺失行情：X）”文案。

---

## 4. 设计

### 4.1 HTTP 超时分层

| 参数 | 取值 | 依据 |
| --- | --- | --- |
| 连接超时 | 3s | 本次故障形态是“连不上/连上无响应”，3s 足够区分 TCP 不可达 |
| 响应读取超时 | 12s | 高于实测最慢成功响应 9.24s，留 30% 余量；同时远小于 60s 采集周期 |
| 交换超时 | 12s | 兜住“连接成功但请求装配/响应读取整体卡住”的场景 |

实现方式：在 `CommonConfiguration` 声明一个 `ReactorClientHttpRequestFactory` Bean，显式设置上述三项；`TornApiImpl` 构造时注入该 Bean，v1 与 v2 两个 `RestClient` 共用同一实例。

必须做成 Bean 的理由：`ReactorClientHttpRequestFactory` 实现 `SmartLifecycle`，`start()/stop()` 由容器驱动；手工 `new` 出来的实例不会启动，底层 `HttpClient` 生命周期与线程资源无人回收。

统一口径的理由：两个 `RestClient` 若各自持有 factory，超时值、连接池、优雅停机三件事都会出现两套实现，必须由容器保证唯一来源。

### 4.2 重试预算

| 参数 | 现值 | 调整后 | 说明 |
| --- | --- | --- | --- |
| Key 轮换次数 | 3 | **2** | 多 Key 连续失效本身罕见；真实故障中换 Key 对网络错误无效 |
| 单 Key 内 HTTP 尝试 | 3 | **2** | 与读超时叠加后，短时抖动仍可自愈一次 |
| 退避 | 无 | **200ms** | 连续重试无间隔只会放大对端压力 |
| 总预算 | 无 | **25s** | 一次逻辑请求从入口到放弃的硬上限 |

预算按“一次逻辑请求”计时（`sendRequest` 入口开始，Key 轮换与 HTTP 重试共用同一预算），而不是每层各给一份，否则“层数 x 每层预算”会重新变成乘法放大。

最坏耗时演算（全部尝试读超时）：

~~~text
t=0.0   尝试1 开始
t=12.0  尝试1 超时, 退避 200ms
t=12.2  尝试2 开始
t=24.2  尝试2 超时; 已用 24.2s < 25s, 允许继续
t=24.4  尝试3 开始
t=36.4  尝试3 超时; 已用 36.4s >= 25s, 预算耗尽, 不再发起尝试4
        单轮失败总耗时约 36.4s < 60s 采集周期
~~~

连不通（立即失败）场景下 4 次尝试 + 3 次退避约 1s 结束，下一分钟即可恢复正常采集。

预算取值 25s 的理由：采集周期 60s，需为“本轮 DB 写入 + 下游派发 + 下一轮排队”预留余量；25s 预算 + 12s 在途尝试 = 37s，仍在周期内且留 23s 余量。

### 4.3 失败语义与日志契约

**（a）空响应显式失败。** `spiderStockData` 在 `sendRequest` 返回后立即校验：`resp == null || CollectionUtils.isEmpty(resp.getStocks())` 时抛出 `IllegalStateException`，消息携带 plannedMinute 与 respNull 标记，不再进入 `upsertStocksSnapshot` 触发 NPE。语义与 `handleInsertResult` 的部分冲突分支一致：fail-closed，不写历史、不派发下游。

**（b）阶段标记。** 采集方法内维护 `phase` 变量，取值 `API_REQUEST` / `RESPONSE_INVALID` / `PERSIST`，随流程推进赋值，异常处带入日志。

**（c）日志契约（唯一出口，均为一行）。**

| 场景 | 级别 | 文案 | 必含字段 |
| --- | --- | --- | --- |
| 单轮采集失败 | ERROR | `股票实时采集-异常, plannedMinute={}, startedAt={}, elapsedMs={}, phase={}, 不写半成功结论` | 计划分钟、开始时刻、耗时、阶段 |
| 请求放弃 | ERROR | `Torn请求 {} 重试后仍然失败, keyRotationCount={}, httpAttemptCount={}, elapsedMs={}, lastCause={}` | uri、Key 轮换次数、HTTP 尝试次数、耗时、异常类名 |
| 单轮采集成功 | DEBUG | 保持现有 `股票实时采集-时序` 与窗口摘要 | 不变 |

文案约束：保留 `股票实时采集-异常` 与 `Torn请求 ... 仍然失败` 两个文本锚点，只在尾部追加字段。原因：Grafana 邮件规则已经在线，改文案等于让告警静默失效；去掉的只是“因Key失效”这个与事实不符的定语。

**（d）保留向上抛出。** 采集失败仍 `throw e`，由 Spring 记录调度任务失败，Grafana 可继续以此为告警来源，本轮不新增任何发送动作。

### 4.4 活跃度采集迁出默认调度器

现状：`TornActivityCollectService#collectActivity` 挂在默认单线程调度器上，方法内 `processBatch` 用 `CompletableFuture.allOf(...).join()` 等齐一批 50 个帮派，循环串行处理多批，整个时长都占用调度线程。本次故障下 350 个帮派请求各重试到上限，形成“每批约 9 分钟 x 7 批”的长占用。

处置：新增专用调度器 Bean `activityCollectScheduler`（poolSize=1，线程前缀 `activity-collect-scheduler-`），`@Scheduled` 增加 `scheduler = "activityCollectScheduler"`。采集逻辑、批次大小、并发限额、`collecting` 防重入全部不变，只改“这段阻塞在谁的线程上”。

不入默认调度器池的原因：`activityCollectExecutor` 的 `ConcurrencyThrottle` 在并发到顶时会阻塞提交线程，若把整轮采集异步提交给它，外层占 1 个额度、内层再要 50 个额度，会形成额度自竞争，且把批次耗时藏起来。专用调度器语义直白，与 `realtimeStockScheduler`、`vipStockRoundScheduler` 的既有做法一致。

### 4.5 告警归属

采集中断的告警由 Grafana 对 Loki 的 ERROR 日志做邮件通知，规则已在线，本轮不改。代码侧只承担一件事：保证上述日志契约稳定、低基数、可被规则匹配。是否加“连续 N 分钟”这类窗口判定、是否拆分到运维群，属于 Grafana 规则演进，留待后续单独评估。

---

## 5. 包与文件规划

### 5.1 包结构变化

~~~text
pn.torn.goldeneye
├── configuration/
│   ├── ActivitySchedulingConfiguration.java    新增：活跃度采集专用调度器
│   ├── CommonConfiguration.java                修改：超时工厂 Bean
│   └── TornApiImpl.java                        修改：预算化重试 + 中性文案
├── constants/torn/TornConstants.java           修改：HTTP 治理常量
└── torn/
    ├── manager/torn/stocks/TornStocksManager.java         修改：显式失败 + 阶段与耗时
    └── service/activity/TornActivityCollectService.java   修改：改绑专用调度器
~~~

不新增采集监控子包：既然告警由 Grafana 承担，代码侧只需保证日志契约，状态机、阈值、发送通道都属于重复建设。`configuration` 包 +1 个文件（11 个），未到需要拆分子包的规模。

### 5.2 逐文件改动

**1）`constants/torn/TornConstants.java`（修改，1.6.4 → 1.7.0）**

新增代码块 `====================HTTP治理相关====================`，全部 `public static final`：

| 常量 | 值 | 用途 |
| --- | --- | --- |
| `HTTP_CONNECT_TIMEOUT_MS` | `3_000` | 连接超时 |
| `HTTP_READ_TIMEOUT_MS` | `12_000` | 响应读取超时 |
| `HTTP_EXCHANGE_TIMEOUT_MS` | `12_000` | 交换超时 |
| `HTTP_ATTEMPT_COUNT` | `2` | 单 Key 内 HTTP 尝试次数 |
| `HTTP_KEY_ROTATION_COUNT` | `2` | 换 Key 次数 |
| `HTTP_RETRY_BUDGET_MS` | `25_000` | 单次逻辑请求总预算 |
| `HTTP_RETRY_BACKOFF_MS` | `200` | 重试前退避 |

阈值只有这一个来源，不在 `TornApiImpl` 内再声明私有常量。

**2）`configuration/CommonConfiguration.java`（修改，1.3.7 → 1.4.0）**

- 新增 `@Bean` 方法 `tornClientHttpRequestFactory()`，返回 `ClientHttpRequestFactory`：实例化 `ReactorClientHttpRequestFactory`，依次 `setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS)`、`setReadTimeout(HTTP_READ_TIMEOUT_MS)`、`setExchangeTimeout(HTTP_EXCHANGE_TIMEOUT_MS)`。
- 让容器负责 `SmartLifecycle.start/stop`，不在任何业务类里 `new`。

**3）`configuration/TornApiImpl.java`（修改，1.4.0 → 1.5.0）**

- 构造函数增加 `ClientHttpRequestFactory` 参数，两个 `RestClient.builder()` 都追加 `.requestFactory(factory)`。
- 私有嵌套类型 `RequestBudget`：以 `startNanos` + 预算时长判定 `exhausted()`，并累计 `keyRotationCount`、`httpAttemptCount`、`lastCause`；提供 `recordFailure(Throwable)` 与 `pause()`（按 `HTTP_RETRY_BACKOFF_MS` 退避，退避后仍超预算则返回 false）。放在 `TornApiImpl` 内以保证职责边界，不单独成文件。
- `executeWithHttpRetry`：递归改 for 循环，每次尝试前判预算，每次异常记账；达到 `HTTP_ATTEMPT_COUNT` 或预算耗尽返回 null（保持既有“返回 null 表示失败”的契约）。
- `executeWithRetry`：循环次数改用 `HTTP_KEY_ROTATION_COUNT`；每轮开始前判预算；放弃时输出新文案（含 Key 轮换次数、总尝试次数、耗时、最后错误类名）。
- 预算对象在公开入口（`sendRequest` 各重载）创建并向下传递，私有方法签名相应增加该参数；`TornApi` 接口不动。
- 保留“任意 retry 结果都换 Key”的既有语义，本轮不按错误类型裁剪换 Key 分支（见第 11 节）。

**4）`torn/manager/torn/stocks/TornStocksManager.java`（修改，1.4.0 → 1.5.0）**

- `spiderStockData`：`sendRequest` 之后新增空响应校验（见 4.3a），异常消息携带 plannedMinute。
- `spiderStockData`：新增 `phase` 局部变量并在三处赋值；`catch` 分支的 ERROR 追加 `elapsedMs`、`phase`，保留原前缀与“不写半成功结论”。
- 其余方法（`upsertStocksSnapshot`、`saveStocksHistory`、`handleInsertResult`、`logCollectionTiming`）不改。

**5）`torn/service/activity/TornActivityCollectService.java`（修改，1.5.0 → 1.6.0）**

- `collectActivity` 的 `@Scheduled` 增加 `scheduler = "activityCollectScheduler"`。
- 类 Javadoc 补充一句：采集在专用调度器上串行执行，任何一次采集耗时不得挤占默认调度器。
- 方法体不改。

**6）`configuration/ActivitySchedulingConfiguration.java`（新增，1.0.0）**

- 单个 `@Bean ThreadPoolTaskScheduler activityCollectScheduler()`：poolSize=1、线程前缀 `activity-collect-scheduler-`、`setWaitForTasksToCompleteOnShutdown(false)`、`setAwaitTerminationSeconds(0)`，与既有三个调度器 Bean 口径一致。
- 类 Javadoc 说明“为什么活跃度采集需要独立调度器”，并指明默认 `taskScheduler` 的语义不变。
- 不把该 Bean 放进 `StockSchedulingConfiguration`：那是股票域配置，跨域复用会诱导后续在错误的位置继续加调度器。8 行样板代码的重复成本低于跨域耦合成本。

---

## 6. 数据、配置与接口变更

- 数据库：无 DDL、无 Liquibase changeset、无数据回填。
- 配置：无新增 `application.yml` 项、无新增环境变量、无功能开关；阈值全部为代码常量，调整需发版。
- 接口：`TornApi` 公开签名不变，调用方零改动。
- 运行时：新增 1 个调度器线程（`activity-collect-scheduler-1`），HTTP 连接池由两个 `RestClient` 合并为一份。

---

## 7. 测试计划

只补与改动直接对应的用例，不铺测试矩阵。

| 编号 | 测试类 | 用例 | 断言要点 |
| --- | --- | --- | --- |
| T1 | `TornStocksManagerTest` | 响应为 null 时 fail-closed | 抛 `IllegalStateException` 而非 NPE；消息标明 `respNull=true`；不写历史、不派发下游 |
| T2 | `TornStocksManagerTest` | 股票列表为空时 fail-closed | 抛 `IllegalStateException`；消息标明 `respNull=false`；不写历史、不派发下游 |
| T3 | `ActivitySchedulingConfigurationTest`（新增） | 调度器 Bean 口径 | poolSize=1、线程前缀正确、与默认 `taskScheduler` 不是同一实例 |

“正常响应派发下游”“全冲突不派发”“部分冲突 fail-closed”“重入跳过”等既有用例已覆盖对应行为，不重复新增。

明确不做：HTTP 客户端集成测试（验证的是框架行为，不是本方案）、重试次数参数化矩阵（预算逻辑为私有嵌套类型，需要 mock HTTP 才有意义，收益低于维护成本）、文案多场景渲染测试。

预算与时长的正确性由第 9 节 A1/A2 的现场证据承担，而不是靠伪造时序的单测。

---

## 8. 长期方案对齐

### 8.1 必须修改（本次随代码一起提交）

**（1）`.ai/knowledge/stocks/vip_stock_alert_technical_design.md`**

- 第 2 节原则清单追加第 21 条：采集侧 HTTP 超时与重试预算以 `TornConstants` 为唯一来源，一次逻辑请求的最坏耗时必须小于采集周期；任何新增外部请求不得自带无上限重试。
- 第 14 节停止条件追加 2 条：一是“单轮采集失败耗时 >= 采集周期”视为容错设计失效，停止继续叠加功能；二是“可能长时间阻塞的任务挂在默认单线程调度器上”视为调度设计失效，必须迁出。
- 第 13 节评审记录追加一条：记录本次 06:56-08:09 采集中断、根因三条、处置与验证证据，形成同类故障的对照基线。

**（2）`.ai/knowledge/stocks/tornsy_stock_history_backfill_technical_design.md`**

- 第 1.1 节缺口声明补充口径：单轮采集失败时长已由采集侧重试预算收敛到 1 个采集周期内，本方案只负责事后补数与巡检，不承担抑制故障蔓延的职责。
- 第 7.1 节补充：每日 07:00 巡检仍只覆盖昨天，当天缺口不自动补数，需超管指令，该边界不因本次整改而改变。

**（3）`.ai/knowledge/file_location.md`**

- 在 `stocks/` 子树按字母序插入本方案文档行。
- 新增的 `ActivitySchedulingConfiguration.java` 不写入：该目录树是选择性摘要，`configuration/` 当前只列 `DynamicTaskService.java`，逐文件补全会让摘要失去可读性。

### 8.2 已核对、结论为不冲突（不改）

| 文档 | 相关表述 | 结论 |
| --- | --- | --- |
| `vip_stock_alert_technical_design.md` | 15m bar 可用性门限、α 决策窗口与桶重试、`usable_stock_count` 口径 | 本方案不触碰任何可用性判定与轮次逻辑 |
| 同上 | 实时采集与 VIP 轮次使用独立调度器 | 与 4.4 的隔离思路同向，本次只是把遗漏的活跃度采集补齐 |
| `tornsy_stock_history_backfill_technical_design.md` | 30 分钟稳定边界、`maintenanceGate`、人工回填指令格式 | 不改动，补数链路与采集链路互不影响 |
| `vip_stock_private_analysis_upgrade_technical_design.md` | “不停止每分钟采集”的约束 | 本方案让采集更稳定，不改变采集频率 |
| `vip_stock_strategy_version_history.md` | 策略版本与冻结口径 | 完全无关 |

---

## 9. 验收清单

| 编号 | 验收项 | 通过标准 | 证据形式 |
| --- | --- | --- | --- |
| A1 | 单轮失败耗时 | ERROR 行 `elapsedMs` <= 40000，且下一自然分钟采集成功 | Loki 日志 |
| A2 | 空响应语义 | 响应为 null 时抛 `IllegalStateException`（非 NPE），`phase=RESPONSE_INVALID`，该分钟无 `torn_stocks_history` 行 | 单测 + 库表 count |
| A3 | 重试上限 | 单轮总尝试次数 <= 4（2 Key x 2 HTTP），日志无“因Key失效”旧定语 | Loki 日志 |
| A4 | 调度隔离 | 活跃度采集日志线程名变为 `activity-collect-scheduler-1`；默认调度器线程不再出现采集阻塞 | Loki 日志 |
| A5 | 无功能回归 | 15m bar/feature/轮次/日报逻辑零改动，最近一个交易日产物与整改前一致 | 库表对比 |
| A6 | 无结构变更 | 无新增表/迁移/配置项（`git status` 无 Liquibase、`application.yml` 无差异） | 代码审查 |
| A7 | 告警可用 | Grafana 现有规则仍能匹配 `股票实时采集-异常` 与 `Torn请求 ... 仍然失败` | 规则核对（人工） |
| A8 | 测试 | 受影响的 3 个测试类通过（15 个用例）；全量 `mvn test` 需在具备 DB/Redis 的环境执行 | 构建输出 |
| A9 | 文档 | 本方案 + 8.1 三处基线同步提交，无“文档待补”项 | 提交记录 |

---

## 10. 风险、回滚与灰度

| 风险 | 影响 | 处置 |
| --- | --- | --- |
| 12s 读超时误杀慢响应 | 极端网络抖动下多一次失败尝试 | 实测最慢成功响应 9.24s，余量 30%；如现场出现误杀，只调 `HTTP_READ_TIMEOUT_MS` |
| Key 轮换 3 改 2 | 多 Key 连续失效时兜底少一轮 | 该类场景本身罕见，且预算内仍有 4 次尝试；回滚只改 `HTTP_KEY_ROTATION_COUNT` |
| 合并连接池 | 两条通道共享连接数 | reactor-netty 默认池足够，监控 A1 指标即可 |
| 活跃度采集迁出后自身延迟不再外溢到其它任务 | 采集自身超时更难被“顺带”发现 | 采集方法已有 INFO 汇总（帮派数/成功/失败/耗时），后续按需在 Grafana 侧细化 |

回滚方式：全部阈值是常量，改值即回滚；调度器迁出可单独回退（删 `scheduler` 属性）。无数据迁移、无不可逆动作。

灰度：本人环境（`project.env=dev`）不触发采集，直接在生产首发；上线后首个交易日观察 A1/A4 两项。

---

## 11. 不在本轮范围

| 项 | 原因 |
| --- | --- |
| 按错误类型裁剪换 Key 分支（网络错误不必换 Key） | 会改变既有重试语义，影响面超出本次故障；预算已把最坏耗时压到周期内，收益有限 |
| 当天缺口的自动补数 | 涉及 Tornsy 巡检窗口与稳定边界，属补数方案范畴，另行评估 |
| `TornApiKeyConfig#returnKey` 每次请求一次 UPDATE | 高频写库可优化为批量/异步，与本次故障无因果关系 |
| 08:15/08:30 轮次 `usable_stock_count=0` 但存在 35 条特征行 | 疑似轮次统计口径问题，与本方案无关，单独排查 |
| Grafana 告警规则细化（连续 N 分钟、分群、静默期） | 归运维侧，本轮只保证日志可匹配 |
| `TravelNoticeChecker` 的判空缺口 | 独立缺陷，单独处理 |

---

## 12. 变更记录

| 版本 | 日期 | 变更内容 |
| --- | --- | --- |
| 1.0.0 | 2026.10.19 | 初稿：超时分层、重试预算、失败语义、采集中断告警状态机 |
| 1.1.0 | 2026.10.19 | 按评审意见调整：删除代码内告警链路（改由 Grafana 邮件承担，仅保留日志契约）；新增活跃度采集迁出默认调度器；确认超时与重试数值；测试收敛为 3 个用例 |
| 1.2.0 | 2026.10.19 | 代码与基线文档同步落地：HTTP 超时工厂 Bean、预算化重试、空响应显式失败与阶段耗时日志、活跃度采集改绑专用调度器；受影响测试类 15 个用例通过 |
