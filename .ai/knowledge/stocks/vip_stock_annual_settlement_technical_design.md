# VIP股票 α 正式仓年度结算与年报技术方案

> 文档类型：技术设计（实施基线；本轮只输出方案，不含代码实施）
> 适用项目：Golden-Eye（JDK21 / SpringBoot 3.5 / PostgreSQL 17 / Liquibase / MyBatis-Plus）
本轮迁移目录 **`1.0.1-2.0.0/1.6.6`（已确认，计划开发版本 1.6.6）**；见 §3.5
> 状态：设计草案，业务规则已冻结（见 §1.3），尚未实施；功能开关默认 `false`
> 风险等级：L3（金额、资金守恒、数据迁移、幂等、调度与生产发布切换）
> 时区：`Asia/Shanghai`（所有自然日、边界时点、调度 cron 均按该时区）
> 关联文档：见 §11
> 最后更新：待评审后填写（YYYY.MM.DD）

---

## 1 目标与范围

### 1.1 目标

为正式 α 组合 `VIP_ALPHA` 建立**只读事实 + 追加式台账**的年度结算与年报能力：

1. 在 `Asia/Shanghai` 每年 1 月 1 日 00:00 结算上一自然年，产出该年度的**年末边界权益**与**本年提取额**；
2. 沿用既有"边界权益盯市"语义：**不强制平仓**，跨年持仓继续按原规则持有；
3. 新增"累计提取利润"科目，使"初始资金 / 累计提取 / 年末边界权益"三者始终可对账；
4. 复用既有通知审计、payload 冻结、数据库级领取与幂等发送链，产出年度年报消息；
5. 全程**不改动任何资金、持仓、批次与历史事实**（见 §10）。

### 1.2 本轮范围

- 只作用于正式仓 `StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE`（`"VIP_ALPHA"`，1 槽 × `VIP_ALPHA_INITIAL_CASH` = 10,000,000,000.00 = 10.00b）。
- 只新增表、索引、通知类型、渲染分支、调度入口与开关；对既有表只做**增量 DDL**（`torn_stock_notice_audit` 一个部分唯一索引，可选一个 CHECK 约束扩展）。
- 本轮只新增本文件；本文档描述的实现尚未实施。

### 1.3 已冻结业务规则（原样固化，不得改写）

| # | 规则 |
|---:|---|
| R1 | 结算时点：`Asia/Shanghai` 每年 1 月 1 日 00:00 结算上一自然年 |
| R2 | 期末权益 = 可用现金 ＋ 预留资金 ＋ 持仓可变现市值；持仓估值扣 0.1% 卖出费 |
| R3 | 缺少合法边界行情时不得伪造结算（沿用既有降级语义） |
| R4 | 采用"方案 C"：**不强制平仓**；新增"**累计提取利润**"科目；报表基准固定为初始资金（正式仓 `VIP_ALPHA` = 10B）；年末提取额口径 = 年末边界权益 − 累计已提取 − 初始资金 |
| R5 | **绝对禁止**：修改 `availableCash` 伪造提取、修改持仓/股数/成本/批次/冷却、把槽位现金重置回初始值、合并多槽为共享现金池、改写历史事实 |
| R6 | 本轮只作用于正式仓 `VIP_ALPHA`；影子组合 `VIP_ALPHA_SHADOW` 不在本轮范围（待业务专家确认后另行处理） |
| R7 | 首个不完整年度：同时展示实际区间收益与年化折算，并标注"试运行" |
| R8 | 金额统一展示 `X.XXb` |
| R9 | 年报消息复用既有通知发送/审计/payload 冻结/幂等链，禁止建设第二套消息平台；需要新增通知类型（参考 `StockNoticeTypeEnum` 现有取值）与渲染分支 |

### 1.4 不在本轮范围

影子组合年度结算、槽外机会、通用投资引擎/新研究平台、第二套消息平台、自动调参、跨窗口自动补结、任何资金划转（详见 §10）。

### 1.5 待确认事项（不阻塞本设计，但实施前需业务/运维确认）

| # | 待确认点 | 本文拟定口径 |
|---:|---|---|
| Q1 | ~~年化折算的日数惯例~~ **已确认：ACT/365** | `(1+R_interval)^(365/N) − 1` | 已确认（2026-09-24） |
| Q2 | ~~首个不完整年度的区间起点~~ **已确认：首笔 α 批次入场时间**（若无法取到则回退槽位 `create_time`，起点权益取初始资金 `I`） | 已确认（2026-09-24） |
| Q3 | 失去可证窗口（§4.2 Gate B 不通过）后的人工补结动作方式与授权流程 | 本轮只落 `MANUAL_REVIEW` 状态 + 告警，人工补结入口待确认，不在本轮交付 |
| Q4 | 上一年度未结算时是否允许跳年结算 | 拟定 fail-closed：`BLOCKED_PRIOR_YEAR`，不允许断链 |
| Q5 | ~~年报投递是否受 `KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED` 约束~~ **已确认：不做约束**，结算服务在事务提交后直接领取发送，残留 PENDING 由既有发送链兜底 | 已确认（2026-09-24） |
| Q6 | ~~年报文案是否 @ 成员~~ **已确认：不 @ 成员**，纯文本（与 `DAILY_SUMMARY` 一致）；行文格式按 §6.4 模板 | 已确认（2026-09-24） |
| Q7 | 影子组合 `VIP_ALPHA_SHADOW` 的结算归属 | 待业务专家确认，本轮明确不做 |

---

## 2 现状与可复用能力

本节所有结论均来自代码核实，未核实处标注"待确认"。

### 2.1 现有入口与调度

| 能力 | 现有实现（已核实） | 关键事实 |
|---|---|---|
| 15 分钟轮次调度 | `alert/market/round/VipStockAlertScheduler#executeRound` | `@Scheduled(cron = "10 * * * * ?", zone = "Asia/Shanghai", scheduler = "vipStockRoundScheduler")`：每分钟第 10 秒触发；`currentEndedBucket = marketClock.currentEndedBucket()`；固定顺序为"幂等建 PENDING 轮次 → `processPendingRounds` → `noticeSendService.sendPendingNotices()`" |
| 轮次状态 | `StockRoundStatusEnum` | 取值 `PENDING/BUILDING_BAR/BUILDING_FEATURE/READY/PROCESSING/COMPLETED/WAITING_DATA/FAILED_RETRYABLE/FAILED_FINAL/REPAIRED_DATA_ONLY`；`TornStockMarketRoundMapper#selectByRoundTime` 可按 `round_time` 无锁读取单条轮次 |
| α 决策窗口 | `alert/alpha/config/StockAlphaRuleDefinition.DECISION_WINDOW_START = LocalTime.of(8, 0)` | α 新决策与已结束自然日快照只允许在 08:00 起的已结束桶内产生 |
| 入场过期 | `StockPortfolioService.calculateEntryStaleAt(signalBarStart)`，`ENTRY_STALE_GRACE_MINUTES = 35` | `entry_stale_at` 为批次非空列；08:00 决策 + 35 分钟宽限 ≈ 09:35 前是资源敏感区（见 §8.2） |
| 每日摘要 | `alert/summary/StockDailySummaryService` | `@Scheduled(cron = "0 10 8 * * *", zone = "Asia/Shanghai")`：每天 08:10；受 `KEY_VIP_STOCK_DAILY_SUMMARY_ENABLED` 控制 |
| Tornsy 回填巡检 | `backfill/TornsyStockHistoryBackfillScheduler` | `@Scheduled(cron = "0 0 7 * * ?", zone = "Asia/Shanghai")`：每天 07:00 |
| 调度资源隔离 | `configuration/StockSchedulingConfiguration` | 已有 `realtimeStockScheduler`、`vipStockRoundScheduler`（均单线程）与默认 `taskScheduler`；`stockBackfillExecutor` 为受限并发执行器 |
| 市场时钟 | `alert/market/StockMarketClock` | `now()/today()/lastEndedNaturalDay()/summaryDate()/currentEndedBucket()`；领域服务禁止直接调用 `LocalDateTime.now()` |
| 自然日最后桶 | `StockAlphaDailyCloseService.LAST_DAY_BUCKET_START = LocalTime.of(23, 45)`；`Stock15mBarBuildService.BUCKET_MINUTES = 15`、`BUILD_VERSION = "1.0.0"` | 15 分钟桶由 `bar_start_time` 标识，`bar_end_time = bar_start_time + 15min`；自然日最后一个桶起点为 23:45，其 `bar_end_time` 为次日 00:00 |

### 2.2 权益口径与缺行情降级（PortfolioEquityCalculator）

`alert/portfolio/PortfolioEquityCalculator` 是**纯计算组件**（不访问 DAO、不读时钟）：

- `MAX_PRICE_AGE_MINUTES = 30L`（`public static final`）；
- `calculateEquity(slots, activeBatches, latestBarByStock, generatedAt)` 返回 `EquityResult(equity, cashAndReserved, missingPriceStocks, priceAsOf)`：
  - `equity = cashAndReserved + Σ(quantity × lastPrice × SELL_FEE_RATE)`，其中 `cashAndReserved` 委托 `StockPortfolioService.calculateCashAndReserved(slots)`（Σ(availableCash + reservedCash)）；
  - 单批次市值 `calculateBatchMarketValue` 要求 `bar.usable = true` 且 `lastPrice > 0`，否则返回 `null`；
  - `isFreshPrice` 要求 `generatedAt − 30min ≤ bar.barEndTime ≤ generatedAt`；
  - **任一开放仓位缺 bar / 价格非法 / 行情过期 → `equity = null` 并按股票 ID 升序返回 `missingPriceStocks`，绝不回退到投入资金近似**（即 R3 的既有降级语义）；
  - 开放持仓口径 `extractOpenPositionBatches`：`batchStatus ∈ {OPEN, DATA_STALE, EXIT_PENDING, DATA_STALE_EXIT}` 且 `slotId/quantity` 非空。
- `StockPortfolioService.calculateEquity(slots, batchMarketValues)` 与 `calculateCashAndReserved(slots)` 是权益公式的唯一实现；`SELL_FEE_RATE = new BigDecimal("0.999")`（即扣 0.1% 卖出费）为唯一费率常量。

> 结论：年度边界权益**必须**复用 `PortfolioEquityCalculator.calculateEquity`，不得另写一套公式或另一份费率常量。

### 2.3 槽位与资金回归（StockPortfolioService / TornStockPortfolioSlotDO）

- `StockPortfolioService` 关键常量（已核实）：`PORTFOLIO_CODE="VIP_FORMAL"`、`VIP_ALPHA_PORTFOLIO_CODE="VIP_ALPHA"`、`VIP_ALPHA_SHADOW_PORTFOLIO_CODE="VIP_ALPHA_SHADOW"`、`SLOT_COUNT=5`、`VIP_ALPHA_SLOT_COUNT=1`、`VIP_ALPHA_SHADOW_SLOT_COUNT=2`、`INITIAL_CASH=2000000000.00`、`VIP_ALPHA_INITIAL_CASH=10000000000.00`、`VIP_ALPHA_SHADOW_SLOT_CASH=5000000000.00`、`SELL_FEE_RATE=0.999`、`ENTRY_DEVIATION_THRESHOLD=0.0015`、`MATH_SCALE=18`。
- 资金回归语义（已核实）：`releaseSlot`（预留退回可用）、`settleSlot` / `settleSlotBacked`（`sellProceeds = quantity × exitReferencePrice × 0.999`，`slot.availableCash = batch.remainingCash + sellProceeds`，槽位回 `AVAILABLE` 并解绑 `currentBatchId`）。**卖出资金回到原槽继续复利，不存在"资金回归/基准重置"型写回**。
- `TornStockPortfolioSlotDO`（表 `torn_stock_portfolio_slot`）字段清单：`id / portfolioCode / slotNo / initialCash / availableCash / reservedCash / currentBatchId / slotStatus / lockVersion`。**已核实：该表与实体均不存在 `baseline`、`closingEquity`、`extracted*` 等字段** → 年度基准与累计提取**不得**塞进槽位行，必须新建台账（§3.1）。
- 表级约束（已核实，`stocks-portfolio.yaml`）：`chk_portfolio_slot_no CHECK (slot_no BETWEEN 1 AND 5)`、`ck_slot_cash_non_negative CHECK (initial_cash >= 0 AND available_cash >= 0 AND reserved_cash >= 0)`、`ck_slot_lock_version CHECK (lock_version >= 0)`、唯一索引 `uk_stock_portfolio_slot_code_no (portfolio_code, slot_no) WHERE deleted = 0`。
- `StockPortfolioInitService.verifyAndInitSlots()`：**只补建缺失槽位**，类注释明确"已存在且状态正确的槽位不会被修改"；启动期该步骤失败会强制关闭新买入。→ 直接证明"把槽位现金重置回初始值"既非既有行为，也不得由本轮引入。

### 2.4 通知链可复用能力

| 能力 | 现有实现（已核实） | 复用方式 |
|---|---|---|
| 通知类型 | `StockNoticeTypeEnum` 现有取值：`BUY / SELL / ALPHA_REBALANCE / DAILY_SUMMARY`（`fromCode` 严格解析，未知编码抛异常） | 新增一个取值（§6.1） |
| 通知状态 | `StockNoticeStatusEnum`：`PENDING/SENDING/SENT/FAILED_RETRYABLE/FAILED_FINAL/（FAILED 历史）/SHADOW_RECORDED`，`isClaimable` 只放行 `PENDING/FAILED_RETRYABLE` | 原样复用 |
| 通知审计表 | `torn_stock_notice_audit`（`TornStockNoticeAuditDO`）：`noticeNo/batchId/noticeType/scheduledRoundTime/summaryDate/groupId/payloadHash/payloadSnapshot/sendStatus/sendAttemptCount/claimToken/claimTime/attemptedAt/sentAt/errorMessage/messageRuleVersion/rebalanceGroupStatus/rebalanceGroupError` | 原样复用；年报用 `summaryDate = 被结算年度最后一日` |
| 唯一索引 | `uk_stock_notice_audit_no (notice_no)`、`uk_stock_notice_audit_batch_type (batch_id, notice_type)`、`uk_stock_notice_audit_summary_date_type (summary_date, notice_type) WHERE notice_type='DAILY_SUMMARY'` | 追加同风格部分唯一索引（§3.3） |
| CHECK 约束 | `ck_notice_attempt_count CHECK (send_attempt_count BETWEEN 0 AND 3)`、`ck_notice_batch_id`（仅 BUY/SELL 要求批次）、`ck_notice_summary_date CHECK (notice_type != 'DAILY_SUMMARY' OR summary_date IS NOT NULL)` | 可选扩展 `ck_notice_summary_date` 覆盖新类型（§3.3） |
| 数据库级领取 | `TornStockNoticeAuditMapper#claimByIds`：仅 `PENDING/FAILED_RETRYABLE`、尝试次数 < 3 可领取，领取即 `send_status='SENDING'` + 写 `claim_token/claim_time/attempted_at` + 次数 +1 | 原样复用（天然防重复发送） |
| 租约与终态 | `StockNoticeSendService.CLAIM_LEASE_MINUTES = 5`；`markSentByIds` 只回写本领取者；`markSendFailedByIds` 未达上限 `FAILED_RETRYABLE`、达上限 `FAILED_FINAL` | 原样复用 |
| 发送编排 | `StockNoticeSendService#sendPendingNotices()`：受 `KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED` 控制；先恢复超时租约，再 `selectSendableNotices`（无类型过滤，`send_status IN ('PENDING','FAILED_RETRYABLE')` 且次数 < 3） | 复用为兜底重发路径 |
| payload 冻结 | `StockNoticePayloadCanonicalizer.sha256(...)` 配合 `StockNoticeSendRecorder#claim/freezePayload/newClaimToken` | 原样复用 |
| 摘要式通知范式 | `StockDailySummaryNoticeService#savePendingNotice` / `sendAndUpdateNotice`：自建 `TornStockNoticeAuditDO`（`noticeNo` 前缀 `D` + `yyyyMMddHHmmssSSS` + 后缀 `S`）、写 `PENDING`、事务外领取 → 冻结 → `sendSingleMessageResult` → 回写终态 | 年报照此模式实现（自有渲染服务，不进 `StockNoticeComposeService`） |
| 合并边界 | `StockNoticeComposeService` 仅支持 `BUY/SELL`（类注释明确 `DAILY_SUMMARY` 不参与合并，由调用方单独处理） | 年报同样不参与合并 |
| 文案格式工具 | `StockNoticeTextFormat`：`formatPrice`（2 位小数）、`formatNetReturn`（±X.XX%）、`formatHoldDuration`、`formatFollowUntil`、`nullSafeText` | 复用百分比；金额需按 R8 新增 b 单位格式化（§6.5） |

### 2.5 数据现状与迁移风格

- 迁移根：`src/main/resources/db/changelog/db.changelog-master.yaml`，按版本段追加 `- include: file: db/changelog/<段>/<版本>/<文件>.yaml`；当前末尾为 `1.0.1-2.0.0/1.6.5/stocks-alpha-dual-basis.yaml`。
- 股票相关 changeset 文件命名（已核实）：`stocks-portfolio.yaml`、`stocks-history-backfill.yaml`、`stocks-history-identity.yaml`、`stocks-feature-investors.yaml`、`stocks-alpha-persistence.yaml`、`stocks-alpha-decision-bar.yaml`、`stocks-notice-claim-retry.yaml`、`stocks-notice-rebalance-group.yaml`、`stocks-alpha-dual-basis.yaml`。
- changeSet 命名风格：`create_table_*`、`add_*_columns_to_*`、`add_*_index_to_*`、`insert_*_setting`；`author: Bai`；中文 `remarks`；唯一索引统一手写 SQL 并带 `WHERE deleted = 0`；新表列一律带 `deleted / create_time / update_time`。
- 重要约定：`stocks-notice-claim-retry.yaml` 头部注释确立"只扩展既有表，不新增表/消息平台/独立发送服务"的口径；本方案延续该风格。

### 2.6 现状缺口（本轮必须新增的最小集合）

| 缺口 | 现状 | 本轮处理 |
|---|---|---|
| 年度边界权益与提取台账 | 不存在任何年度结算表/字段（`baseline` 在股票域无实现） | 新表（§3.1） |
| 年度开户基准 | 槽位无基准字段，且禁止写入槽位 | 由台账行推导（§5） |
| 年报通知类型与渲染 | `StockNoticeTypeEnum` 无年度类型；`StockNoticeComposeService` 只认 BUY/SELL | 新增枚举值 + 自有渲染服务（§6） |
| 无批次通知的发送放行 | `StockNoticeSendService#requiresBatch` 仅对 `DAILY_SUMMARY` 返回 `false`，其余（含未知类型）一律要求有效批次，缺批次会被 `markFinal` 置 `FAILED_FINAL` | **必须**扩展白名单（§6.2，P0） |
| 年报调度 | 无 | 新增独立调度器（§8.1） |
| 年度边界行情取数 | 既有 `selectLatestUsableByStocks(stocksIds, cutoffTime, minBarEndTime, buildVersion)` 在 00:00 时点的 `cutoffTime` 会排除 23:45 桶 | 改用按 `bar_start_time` 精确取桶的既有查询（§4.2 Gate C） |

---

## 3 数据模型与 Liquibase 迁移

### 3.1 新表：torn_stock_portfolio_annual_settlement

一行 = 一个 `(portfolio_code, settle_year, rule_version)` 的年度结算事实。**追加式台账**，同时承载"累计提取利润"科目（见 §3.2）。

| 列 | 类型 | 约束 | 含义 |
|---|---|---|---|
| `id` | BIGINT | PK, autoIncrement | 主键 |
| `portfolio_code` | VARCHAR(32) | NOT NULL | 组合编码；本轮只允许 `'VIP_ALPHA'` |
| `settle_year` | INT | NOT NULL | 被结算的自然年（如 2026） |
| `boundary_time` | TIMESTAMP | NOT NULL | 边界时点 = 次年 1 月 1 日 00:00（`Asia/Shanghai`） |
| `boundary_bar_start_time` | TIMESTAMP | NULL | 边界行情桶起点 = 被结算年 12-31 23:45 |
| `boundary_bar_digest` | VARCHAR(128) | NULL | 边界行情证明摘要：按 `stocks_id` 升序拼 `stocksId:barId:lastPrice` 后 SHA-256（与 `StockNoticePayloadCanonicalizer.sha256` 同实现） |
| `initial_cash` | DECIMAL(18,2) | NULL | 初始资金快照（= `VIP_ALPHA_INITIAL_CASH`，10.00b） |
| `opening_equity` | DECIMAL(18,2) | NULL | 本年度基准 `B_y`（§5） |
| `closing_cash` | DECIMAL(18,2) | NULL | 边界可用现金（`availableCash` 快照，只读） |
| `closing_reserved` | DECIMAL(18,2) | NULL | 边界预留资金（`reservedCash` 快照，只读） |
| `closing_market_value` | DECIMAL(18,2) | NULL | 边界持仓可变现市值（已扣 0.1% 卖出费） |
| `closing_equity` | DECIMAL(18,2) | NULL | 年末边界权益 `E_y` |
| `cumulative_extracted_before` | DECIMAL(18,2) | NULL | 本次结算前累计已提取 `C_{y-1}` |
| `extracted_amount` | DECIMAL(18,2) | NULL | 本年提取额 `W_y`（可为负） |
| `cumulative_extracted_after` | DECIMAL(18,2) | NULL | 累计已提取 `C_y` |
| `year_return` | DECIMAL(18,10) | NULL | 年度收益率 `R_y = E_y / B_y − 1` |
| `coverage_days` | INT | NULL | 区间自然日数 `N` |
| `partial_year` | BOOLEAN | NOT NULL, default false | 是否不完整年度（试运行） |
| `annualized_return` | DECIMAL(18,10) | NULL | 年化折算（仅展示；不适用时 NULL） |
| `open_position_count` | INT | NULL | 边界开放持仓批次数 |
| `settlement_status` | VARCHAR(32) | NOT NULL | 见 §3.1.1 |
| `degrade_reason` | VARCHAR(512) | NULL | 降级/阻断原因（可解释） |
| `notice_id` | BIGINT | NULL | 关联的 `torn_stock_notice_audit.id` |
| `notice_status` | VARCHAR(32) | NULL | 通知状态快照（`StockNoticeStatusEnum`） |
| `rule_version` | VARCHAR(32) | NOT NULL | 结算口径版本 |
| `last_attempt_at` | TIMESTAMP | NULL | 最近一次结算尝试时点 |
| `deleted` / `create_time` / `update_time` | — | 与既有表一致 | 审计列 |

索引与约束：

```sql
CREATE UNIQUE INDEX uk_stock_annual_settlement_business
    ON torn_stock_portfolio_annual_settlement (portfolio_code, settle_year, rule_version)
    WHERE deleted = 0;

-- SETTLED 行必须字段齐全（fail-closed 在数据库层）
ALTER TABLE torn_stock_portfolio_annual_settlement
    ADD CONSTRAINT ck_annual_settlement_settled_fields CHECK (
        settlement_status <> 'SETTLED' OR (
            boundary_bar_start_time IS NOT NULL AND boundary_bar_digest IS NOT NULL
            AND initial_cash IS NOT NULL AND opening_equity IS NOT NULL
            AND closing_cash IS NOT NULL AND closing_reserved IS NOT NULL
            AND closing_market_value IS NOT NULL AND closing_equity IS NOT NULL
            AND cumulative_extracted_before IS NOT NULL AND extracted_amount IS NOT NULL
            AND cumulative_extracted_after IS NOT NULL AND year_return IS NOT NULL
            AND coverage_days IS NOT NULL AND open_position_count IS NOT NULL));

-- 提取恒等式（§5）在数据库层兜底
ALTER TABLE torn_stock_portfolio_annual_settlement
    ADD CONSTRAINT ck_annual_settlement_extraction_identity CHECK (
        settlement_status <> 'SETTLED' OR (
            extracted_amount = closing_equity - cumulative_extracted_before - initial_cash
            AND cumulative_extracted_after = cumulative_extracted_before + extracted_amount
            AND opening_equity = initial_cash + cumulative_extracted_before
            AND closing_equity = closing_cash + closing_reserved + closing_market_value));
```

#### 3.1.1 结算状态枚举（新增 StockAnnualSettlementStatusEnum）

| code | 中文 | 触发条件 | 是否终态 |
|---|---|---|---|
| `PENDING_BOUNDARY` | 待边界就绪 | 已进入结算尝试，但 Gate A/B/C 未全部通过 | 否，可在可证窗口内重试 |
| `SETTLED` | 已结算 | 全部门禁通过且金额落库 | 是 |
| `DEGRADED_PRICE_MISSING` | 边界行情缺失 | 任一开放持仓缺合法边界行情（`missingPriceStocks` 非空） | 否（窗口内可重试） |
| `DEGRADED_NOT_PROVABLE` | 边界状态不可证 | 边界后已发生资金变动（Gate B 不通过） | 是（转人工） |
| `BLOCKED_PRIOR_YEAR` | 上一年度未结算 | 上一年度无 `SETTLED` 行 | 是（转人工/待上一年度结算） |
| `MANUAL_REVIEW` | 人工核验 | 窗口内多次尝试仍不可结算，或人工升级 | 是 |

### 3.2 "累计提取利润"科目的载体

**唯一载体 = `torn_stock_portfolio_annual_settlement` 的 `extracted_amount` / `cumulative_extracted_after` 列**（同一 `portfolio_code` 下按 `settle_year` 递增的追加式台账）。

- 科目余额 `C_y` = 该组合最新 `SETTLED` 行的 `cumulative_extracted_after` = `SUM(extracted_amount)`（两者必须相等，作为验收项 §9.2）。
- **不新增独立余额表**，**不修改 `torn_stock_portfolio_slot`**：提取是账面科目，不触碰 `availableCash` / `reservedCash` / `initialCash`（R5）。槽内资金继续复利，不受提取影响。
- 台账连续性：`settle_year` 必须连续（`C_{y-1}` 来自 `settle_year = y−1` 的 `SETTLED` 行）；上一年度未结算时 fail-closed 为 `BLOCKED_PRIOR_YEAR`，不得跳年。

### 3.3 torn_stock_notice_audit 增量（仅索引/约束，不加列）

```sql
-- 年报幂等键：同一被结算年度只允许一条年报审计行
CREATE UNIQUE INDEX uk_stock_notice_audit_annual_settlement
    ON torn_stock_notice_audit (summary_date, notice_type)
    WHERE notice_type = 'ANNUAL_SETTLEMENT' AND deleted = 0;
```

可选（同风格 DROP + ADD）：

```sql
ALTER TABLE torn_stock_notice_audit DROP CONSTRAINT ck_notice_summary_date;
ALTER TABLE torn_stock_notice_audit ADD CONSTRAINT ck_notice_summary_date
    CHECK (notice_type NOT IN ('DAILY_SUMMARY', 'ANNUAL_SETTLEMENT') OR summary_date IS NOT NULL);
```

`ck_notice_batch_id` 只约束 `BUY/SELL`，年报无需批次，**不改**。

### 3.4 功能开关 seed

`sys_setting` 追加一行（沿用 `stocks-portfolio.yaml` 中"单独 changeSet 追加，避免改写已执行 changeSet"的做法）：

```yaml
- changeSet:
    id: insert_stock_annual_settlement_setting
    author: Bai
    changes:
      - insert:
          tableName: sys_setting
          columns:
            - column: { name: setting_key,   value: "VIP_STOCK_ANNUAL_SETTLEMENT_ENABLED" }
            - column: { name: setting_value, value: "false" }
```

对应常量：`SettingConstants.KEY_VIP_STOCK_ANNUAL_SETTLEMENT_ENABLED`（与 `KEY_VIP_STOCK_DAILY_SUMMARY_ENABLED` 并列）。

### 3.5 Liquibase 文件与命名

| 项 | 值 |
|---|---|
| 新文件 | `src/main/resources/db/changelog/1.0.1-2.0.0/1.6.6/stocks-annual-settlement.yaml` |
| changeSet id | `create_table_stock_portfolio_annual_settlement`、`add_annual_settlement_constraints`、`add_annual_settlement_notice_unique_index`、`widen_notice_summary_date_check`（可选）、`insert_stock_annual_settlement_setting` |
| master 追加 | `db.changelog-master.yaml` 末尾新增 `- include: { file: db/changelog/1.0.1-2.0.0/1.6.6/stocks-annual-settlement.yaml }` |
**计划开发版本 1.6.6（已确认）**；目录 `1.0.1-2.0.0/1.6.6`

**禁止**改写任何已执行 changeset；新对象一律新增文件 + 末尾 include。

### 3.6 明确不做的 schema 变更

- 不给 `torn_stock_portfolio_slot` 加任何列（含基准/提取字段），不改其数据；
- 不改 `torn_stock_virtual_batch`（批次、股数、成本、状态、冷却全部零改动）；
- 不改 `torn_stock_market_bar_15m`、`torn_stock_market_round`、`torn_stock_alpha_*` 任何结构；
- 不新增"影子结算"表、不新增第二套通知表。

---

## 4 年度结算流程与幂等

### 4.1 边界定义

```text
settleYear        = 被结算的自然年（例：2026）
boundaryTime      = LocalDateTime.of(settleYear + 1, 1, 1, 0, 0)      // R1：每年 1 月 1 日 00:00
boundaryBarStart  = boundaryTime.minusMinutes(BUCKET_MINUTES)          // = settleYear-12-31 23:45
```

- `boundaryBarStart` 与被结算年"最后一个 15 分钟桶"一致（`StockAlphaDailyCloseService.LAST_DAY_BUCKET_START = 23:45`），其 `bar_end_time = boundaryTime`。
- 边界之后的任何事实（`entry_time/exit_time ≥ boundaryTime`，即新年度 00:00 桶及其后）**不计入本年度**；跨年持仓继续持有（方案 C，不平仓）。

### 4.2 三道门禁（全部通过才允许结算）

| 门禁 | 判定（全部使用既有查询） | 不通过后果 |
|---|---|---|
| **Gate A：边界桶轮次已完成** | `TornStockMarketRoundDAO.selectByRoundTime(boundaryBarStart)` 存在且 `round_status = StockRoundStatusEnum.COMPLETED` | 该年度最后一桶的策略事实可能尚未全部落库（例如 23:45 桶触发的 SELL 结算发生在次日 00:00:10 的轮次）→ 不结算，`PENDING_BOUNDARY`，窗口内重试 |
| **Gate B：边界后零资金变动（可证性）** | `TornStockVirtualBatchDAO.selectAlphaActionBatches(VIP_ALPHA, boundaryTime, boundaryTime.plusDays(1))` 为空（该 SQL 覆盖 `entry_time` 或 `exit_time` 落在区间内的批次），且该组合槽位行 `update_time < boundaryTime`（无边界后写入） | 读到的槽位/批次状态已不是边界状态 → **不得用当前权益冒充**，`DEGRADED_NOT_PROVABLE` + 告警，转人工（Q3） |
| **Gate C：边界行情齐备** | 对该组合全部开放持仓股票，加载 `bar_start_time = boundaryBarStart`、`build_version = Stock15mBarBuildService.BUILD_VERSION`、`deleted = 0` 的 bar（既有 `selectByBarStartTime` / `selectUsableByStocksAndTimeRange`），过滤 `usable = true` 且 `lastPrice > 0` | 沿用 `PortfolioEquityCalculator` 降级：`equity = null` 且 `missingPriceStocks` 非空 → **绝不伪造结算**（R3），`DEGRADED_PRICE_MISSING`，窗口内重试 |

> Gate C 必须显式"按桶取数"，不能复用 `selectLatestUsableByStocks`：该方法以 `bar_start_time <= cutoffTime` 为上界，在 00:00 后的调用点，`cutoffTime`（= `currentEndedBucket − 15min`）会排除 23:45 桶。这是已核实的取数差异，本方案必须显式处理。
>
> 权益计算仍调用 `PortfolioEquityCalculator.calculateEquity(slots, activeBatches, boundaryBarByStock, boundaryTime)`，`generatedAt` 传入 `boundaryTime`，使新鲜度窗口恰好为 `[boundaryTime − 30min, boundaryTime]`：23:45 桶（`barEndTime = boundaryTime`）被接受，边界后的 bar 被拒绝。

### 4.3 结算主流程（单一短事务，前置只读门禁）

```text
1) 解析入参：portfolioCode = StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE（硬编码唯一；不得由调用方传入任意组合）
2) 计算 settleYear / boundaryTime / boundaryBarStart
3) 幂等短路：已存在 (portfolioCode, settleYear, ruleVersion) 且 settlement_status = 'SETTLED' → 直接返回，不重算、不重复提取、不重复建通知
4) Gate A → Gate B → Gate C（任一不通过 → upsert 降级状态行 + degrade_reason，结束并告警；不写任何金额）
5) 读取只读事实：
   - slots          = TornStockPortfolioSlotDAO.selectAllByPortfolioCode('VIP_ALPHA')
   - activeBatches  = TornStockVirtualBatchDAO.selectActiveAlphaBatches('VIP_ALPHA')
   - boundaryBarByStock = Gate C 结果（按 stocks_id 索引）
6) 计算权益：PortfolioEquityCalculator.calculateEquity(slots, activeBatches, boundaryBarByStock, boundaryTime)
   - equity = null → 回退到 4) 的 DEGRADED_PRICE_MISSING 分支
   - closingCash/closingReserved = Σ(availableCash) / Σ(reservedCash)（只读快照）
   - closingMarketValue = closingEquity − closingCash − closingReserved（恒等式，§9.1 校验）
7) 读取累计提取：priorRow = 最近一个 settle_year < settleYear 且 SETTLED 的台账行
   - 不存在且 settleYear > 已知首年 → settlement_status = 'BLOCKED_PRIOR_YEAR'，结束
   - 存在 → cumulativeBefore = priorRow.cumulative_extracted_after；否则 0.00
8) 计算基准与提取（§5）：openingEquity、extractedAmount、cumulativeAfter、yearReturn、coverageDays、partialYear、annualizedReturn
9) 幂等落库：INSERT ... ON CONFLICT (portfolio_code, settle_year, rule_version) WHERE deleted = 0 DO NOTHING
   - 影响行数 = 0 → 已存在（并发或重复触发）：不再重复提取，只做状态回读与日志
   - 影响行数 = 1 → 同一事务内 UPDATE 该行为金额齐全的 SETTLED 行
10) 同一事务内创建年报 PENDING 通知审计行（§6.3，幂等由唯一索引兜底），回填 notice_id
11) 事务提交后（事务外）领取 → 冻结 payload → 发送 → 回写终态（复用既有链）
```

要点：

- 第 5–8 步**全部只读**；唯一写入是第 9–10 步本公司的新表/通知表。**任何 UPDATE 都不指向 `torn_stock_portfolio_slot` / `torn_stock_virtual_batch` / `torn_stock_market_bar_15m`。**
- 权益缺行情时**不落任何金额**（列保持 NULL），数据库层 `ck_annual_settlement_settled_fields` 无法被绕过。

### 4.4 幂等与重复执行

| 触发场景 | 结论 |
|---|---|
| 同一窗口内每分钟重试（§8.1） | 第一轮成功后，后续轮次在第 3 步幂等短路，不重算不重复提取 |
| 双实例/并发触发 | 唯一索引 `uk_stock_annual_settlement_business` + `ON CONFLICT DO NOTHING`：只有一行，另一实例影响行数为 0 后放弃写入 |
| 通知重复 | 唯一索引 `uk_stock_notice_audit_annual_settlement`（partial）；即使并发建行也只有一行；发送侧再由 `claimByIds`（PENDING→SENDING 原子领取）保证"只调用 Bot 一次" |
| 跨进程/重启后重跑 | 同上；`SETTLED` 行存在即视为已结算，不因重启重复提取 |
| 降级后重试 | 仅 `PENDING_BOUNDARY` / `DEGRADED_PRICE_MISSING` 允许在可证窗口内重试；重试走同一幂等键，成功后更新为 `SETTLED` 并补齐金额 |

### 4.5 事务与并发

- 结算写库使用**一个短事务**（台账行 + 通知 PENDING 行），与 `VipStockAlertScheduler` 的轮次事务**不共享线程池**（§8.1），避免与 15 分钟轮次争用 `vipStockRoundScheduler`。
- 不获取槽位行锁（无需写槽位）；`selectAllByPortfolioCodeForUpdate` **不用于**本流程，避免与轮次事务形成无谓锁等待。
- 发送阶段在事务外执行，与 `StockDailySummaryNoticeService.sendAndUpdateNotice` 同构（领取失败即跳过，由后续调度或兜底路径重发）。

---

## 5 累计提取利润与报表基准

### 5.1 符号

| 符号 | 含义 | 精度 |
|---|---|---|
| `I` | 初始资金 = `StockPortfolioService.VIP_ALPHA_INITIAL_CASH` = `10000000000.00`（展示 `10.00b`） | `scale = 2` |
| `B_y` | 第 y 年度本年度基准 | `scale = 2` |
| `E_y` | 第 y 年度年末边界权益 | `scale = 2` |
| `C_y` | 截至第 y 年末的累计提取利润 | `scale = 2` |
| `W_y` | 第 y 年度本年提取额 | `scale = 2` |
| `R_y` | 第 y 年度收益率 | `scale = 18`，`HALF_UP` |
| `C_0` | 结算起始前累计提取 | `0.00` |

金额一律 `BigDecimal`、`setScale(2, RoundingMode.HALF_UP)`；比率一律 `scale = 18`、`HALF_UP`（与 `StockPortfolioService.MATH_SCALE` 同量纲）。

### 5.2 公式（冻结口径的严格表达）

```text
(1) 年末边界权益（R2，沿用既有权益口径）
    E_y = Σ(availableCash) + Σ(reservedCash) + Σ(quantity × boundaryLastPrice × SELL_FEE_RATE)
        其中 SELL_FEE_RATE = 0.999（扣 0.1% 卖出费）

(2) 本年度基准
    B_y = I + C_{y-1}

(3) 本年提取额（冻结口径：年末边界权益 − 累计已提取 − 初始资金）
    W_y = E_y − C_{y-1} − I

(4) 累计提取利润
    C_y = C_{y-1} + W_y
```

由 (2)(3)(4) 可直接推出（**必须同时成立，作为验收恒等式**）：

```text
(a) C_y = E_y − I                      // 累计提取 = 边界权益 − 初始资金
(b) W_y = E_y − E_{y-1}                // 本年提取 = 年度权益变动额（y = 1 时 E_0 = I）
(c) E_y = I + C_y = B_y + W_y
(d) B_y = I + C_{y-1} = E_{y-1}
(e) R_y = E_y / B_y − 1 = W_y / B_y
```

**与本年度基准、累计提取的关系（必须写清，避免重复计息）**

1. **报表基准固定 = `I`（10.00b），永久不变**：它是唯一的"本金锚"，任何年度都不得用修改槽位现金（`availableCash` / `reservedCash` / `initialCash`）或写入槽位的方式"重置"它（R5）。
2. **本年度基准 `B_y = I + C_{y-1}`**：即"初始资金 + 此前累计已提取"。它数值上等于上一年末边界权益（式 d），但由台账推导得出，不是把权益写回槽位得到的。
3. **累计提取 `C_{y-1}` 必须先扣除**：因为提取是账面科目、资金仍留在槽内继续复利（方案 C 不平仓、不动 `availableCash`），若不由 (3) 扣除，历史利润会被重复计入本年度提取额。
4. **提取是账面科目，不是资金划转**：本轮**没有任何** `availableCash` 变动；`W_y` 只落在台账与报表。
5. **亏损年度**：`W_y < 0` 表示累计提取被年度亏损回撤，按原样记录与展示（如 `-0.42b`）；**禁止**用任何现金调整"补足"，`C_y` 允许下降。报表须注明"负值表示账面回撤，非资金回补"。
6. 与《VIP股票策略研究月报（2026-09）》§7.2 的关系：月报的"新年度 baselineEquity = 上年 closingEquity"在本方案中**只作为"本年度基准"的数值来源**（式 d）保留；该文 §7.2 关于"5 槽 / 2B / 旧版 BUY"的表述已过期，且该文未定义"累计提取"；本方案以 `VIP_ALPHA`（1 槽 × 10B）重写，并额外冻结"报表基准永远固定为初始资金"。

### 5.3 首个不完整年度（R7）

`VIP_ALPHA` 于 2026 年建立，2027-01-01 的首次结算是**不完整年度**：

```text
已确认：首笔 α 批次入场时间
N           = ChronoUnit.DAYS.between(startDate, boundaryTime.toLocalDate())
partialYear = N < 该年度自然日总数(365/366)
E_0         = I                                                        // 首次建仓前权益恰为 I（槽位初始 availableCash = I、无持仓）
R_interval  = E_y / I − 1 = W_y / I
ACT/365（已确认）
```

展示要求：**同时**给出 `R_interval` 与 `R_annual`，并明确标注"**试运行**"（`partial_year = true`）。

不适用年化时（`N < 1`、`1 + R_interval ≤ 0`）：`annualized_return` 落 NULL，报表只展示区间收益并标注"样本不足，不展示年化"，**不得**用近似值或外推值填充。年化只用于展示，可由 double 计算后转 `BigDecimal(scale 18, HALF_UP)`，且必须由单一工具方法实现，不得参与提取额计算。

### 5.4 展示与精度（R8）

- 金额一律 `X.XXb`：单位 `1e9`，保留 2 位小数，`HALF_UP`；例 `10000000000.00 → 10.00b`、`1250000000.00 → 1.25b`、负数带 `-` 号、`null → 0.00b`。
- 百分数沿用 `StockNoticeTextFormat.formatNetReturn`（`+X.XX%` / `−X.XX%`）；`R_interval`、`R_annual` 复用同一实现，禁止各写一套。
- 金额展示与金额持久化必须使用**同一** `BigDecimal` 源值（先落库、再由落地值渲染），避免"报表数字与台账不一致"。

---

## 6 年报消息与渲染

### 6.1 通知类型与开关

- 新增枚举值（追加到 `StockNoticeTypeEnum`，保持 `fromCode` 严格解析语义）：

```java
/**
 * 年度结算年报 - 每年1月1日结算上一自然年后发送
 */
ANNUAL_SETTLEMENT("ANNUAL_SETTLEMENT", "年度结算年报"),
```

- 开关：`SettingConstants.KEY_VIP_STOCK_ANNUAL_SETTLEMENT_ENABLED`（默认 `false`），由调度入口读取，语义与 `KEY_VIP_STOCK_DAILY_SUMMARY_ENABLED` 一致。
- 通知落库字段（沿用摘要范式）：`noticeNo`（前缀改用 `A` 避免与摘要 `D` 混淆，待确认）、`noticeType = ANNUAL_SETTLEMENT`、`summaryDate = boundaryTime.minusDays(1).toLocalDate()`（= 被结算年 12-31）、`groupId = projectProperty.getVipGroupId()`、`sendStatus = PENDING`、`sendAttemptCount = 0`、`messageRuleVersion = VipStockAlertScheduler.MESSAGE_RULE_VERSION`、`payloadSnapshot / payloadHash`。

### 6.2 必须修复的发送放行（P0）

`StockNoticeSendService#requiresBatch` 现实现为"仅 `DAILY_SUMMARY` 免批次，其余（含未知类型）一律要求有效批次"，而 `selectSendableNotices` **不做类型过滤**。若不修改：

> 一条 `ANNUAL_SETTLEMENT` 的 `PENDING` 行一旦被 `sendPendingNotices()` 扫到，会因 `batchId == null` 被 `markMissingBatchNoticesFinal` 直接置为 `FAILED_FINAL`（错误信息"关联虚拟交易批次不存在"），年报永久无法自动重发。

因此必须把 `requiresBatch` 改为**无批次通知类型白名单**：

```java
private boolean requiresBatch(TornStockNoticeAuditDO notice) {
    String type = notice.getNoticeType();
    return !StockNoticeTypeEnum.DAILY_SUMMARY.getCode().equals(type)
            && !StockNoticeTypeEnum.ANNUAL_SETTLEMENT.getCode().equals(type);
}
```

保持"未知类型仍要求批次"的 fail-closed 语义不变（只白名单化已知的有自包含正文类型）。

### 6.3 载荷与冻结

- payload 快照（JSON，`JsonUtils.objToJson` 落 `jsonb`）至少包含：`noticeType`、`settleYear`、`boundaryTime`、`boundaryBarStartTime`、`boundaryBarDigest`、`summaryDate`、`groupId`、`settlementId`、`ruleVersion`、`initialCash`、`openingEquity`、`closingCash`、`closingReserved`、`closingMarketValue`、`closingEquity`、`cumulativeExtractedBefore`、`extractedAmount`、`cumulativeExtractedAfter`、`yearReturn`、`coverageDays`、`partialYear`、`annualizedReturn`、`openPositionStocks`、`messageText`。
  `messageText` = 渲染结果（§6.4），冻结后即为投递与审计的唯一正文。
- `payloadHash = StockNoticePayloadCanonicalizer.sha256(payloadSnapshot)`；投递前的最终冻结走 `StockNoticeSendRecorder#freezePayload`（与摘要/买卖通知同一实现、同一复核口径）。
- **禁止**为年报新建第二套 payload/冻结/发送实现。

### 6.4 渲染分支与模板

- 新增 `StockAnnualSettlementRenderer`（纯函数，无 DAO、无时钟），与既有 `StockDailySummaryRenderer` 同层同风格；**不进 `StockNoticeComposeService`**（该类只支持 BUY/SELL，年报不参与合并）。
- 渲染只消费结算行 + 边界持仓简要 + 格式化工具，禁止在渲染阶段查询或重算权益。

模板（**已定稿并经用户确认，2026-09-24**：不展示结算口径、期末边界权益明细、边界持仓/行情/口径版本；括号内容一律简化为状态说明）：

```text
【VIP股票 α 年度报告 · {settleYear}】

年度状态：{试运行（{startDate} 起，共 N 天） | 完整年度（365 天）}
区间收益：{+X.XX%}
年化折算：{+X.XX%}（试运行，仅供参考）

本年账面利润：{+X.XXb}
累计账面利润：{+X.XXb}

本报告为系统内部虚拟组合记录，不构成投资建议；账面利润为记账口径，资金仍在槽内继续复利。
```

口径要点：

- 金额一律 `X.XXb`（`StockNoticeTextFormat#formatBillion`）；一切金额同口径（不足 1b 显示 `0.00b`）；收益率为带正负号的百分数；
- 术语统一为「账面利润」（不称「提取」），并保留「记账口径、不发生资金划转」的说明；
- 年化只在试运行（不完整）年度展示；完整年度区间收益即年化，此时省略该行，避免同一数字出现两次；
- 数据不足或边界行情缺失时不发本消息（转 `MANUAL_REVIEW` + 运维告警）。

- 负 `W_y` 的追加说明（已定稿）：`注：本年账面利润为负表示账面利润被年度亏损回撤，非资金回补。`

### 6.5 金额 X.XXb 格式化实现

- 在既有共享文案工具 `StockNoticeTextFormat` 中新增一个静态方法（保持"同一格式化只实现一份"的类职责）：

```java
private static final BigDecimal BILLION = new BigDecimal("1000000000");
private static final int BILLION_SCALE_DIGITS = 2;

public static String formatBillion(BigDecimal amount) {
    if (amount == null) { return "0.00b"; }
    return amount.divide(BILLION, BILLION_SCALE_DIGITS, RoundingMode.HALF_UP).toPlainString() + "b";
}
```

- 金额只经此一处转换；**禁止**在渲染类内各写一份 b 单位格式化。

---

## 7 异常、降级与失败重试

| 场景 | 检测 | 状态/终态 | 重试策略 | 人工 |
|---|---|---|---|---|
| 边界桶轮次未完成（Gate A） | `selectByRoundTime(boundaryBarStart)` 非 `COMPLETED` | `PENDING_BOUNDARY` | 窗口内每 2 分钟重试（§8.1）；出窗转 `MANUAL_REVIEW` | 出窗后需要 |
| 边界行情缺失/非法/不可用（Gate C） | `PortfolioEquityCalculator` 返回 `equity == null`（`missingPriceStocks` 非空） | `DEGRADED_PRICE_MISSING`，**金额列全 NULL** | 窗口内重试；出窗转 `MANUAL_REVIEW` | 出窗后需要（R3：绝不伪造） |
| 边界后已发生资金变动（Gate B） | `selectAlphaActionBatches` 非空，或槽位 `update_time ≥ boundaryTime` | `DEGRADED_NOT_PROVABLE` | **不自动重试**（状态已不可证） | 必须（Q3） |
| 上一年度未结算 | 无 `settle_year = y−1` 的 `SETTLED` 行 | `BLOCKED_PRIOR_YEAR` | 待上一年度结算后由后续触发重算 | 需要关注（Q4） |
| 台账唯一键冲突（并发/重复触发） | `ON CONFLICT DO NOTHING` 影响 0 行 | 保持既有行 | 只做状态回读 + 日志，**不重复提取** | 否 |
| 通知建行冲突 | `uk_stock_notice_audit_annual_settlement` 冲突 | 复用既有行 | 回读 `notice_id` 并尝试投递 | 否 |
| 通知领取失败（已被他人持有） | `StockNoticeSendRecorder#claim` 返回 false | 行仍为 `PENDING/SENDING` | 由 `sendPendingNotices()` 兜底（`CLAIM_LEASE_MINUTES = 5` 租约恢复） | 否 |
| 冻结失败（更新行数不符） | `freezePayload` 返回 false | `markSendFailed` | 既有自动重发（上限 3 次） | 达上限后 `FAILED_FINAL` |
| Bot 发送失败 | `sendSingleMessageResult` 非成功 | `FAILED_RETRYABLE` → 3 次后 `FAILED_FINAL` | 既有调度自动重发 | 达上限后需 |
| 结算事务异常 | 抛异常回滚 | 无新行/无新通知 | 下一窗口重试（幂等键保证不重复提取） | 否 |
| 渲染异常 | 渲染器抛异常 | 保持 `PENDING_BOUNDARY`（金额已落库则可重渲染） | 回滚事务后重试 | 连续失败需 |

通用约束：

- 任何降级路径**都不得**写入金额、不得写"最接近的可用权益"、不得改写历史事实；
- 降级必须**可观测**：`settlement_status` + `degrade_reason` + `last_attempt_at` 三列即为核验依据，并输出 `ERROR/WARN` 日志（含 `portfolioCode/settleYear/status/reason`）；
- 运维告警**不复用**股票通知链（年报是业务消息，告警是运维消息），运维告警方式待确认（Q3），本轮至少保证日志可检索。

---

## 8 调度与发布顺序

### 8.1 调度

新增 `VipStockAnnualSettlementScheduler`（归属 `alert/summary` 或新包 `alert/settlement`，命名待评审），并新增独立调度器 Bean（沿用 `StockSchedulingConfiguration` 风格）：

```java
@Bean
public ThreadPoolTaskScheduler stockAnnualSettlementScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("stock-annual-settlement-scheduler-");
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.setAwaitTerminationSeconds(0);
    return scheduler;
}
```

调度点（全部 `zone = "Asia/Shanghai"`，且全部落在 00:00–00:15 窗口内）：

| 入口 | cron | 说明 |
|---|---|---|
| 主入口（每年 1 月 1 日） | `0 5 0 1 1 *` | 00:05：既在 23:45 桶轮次（00:00:10 触发）之后，又在新年度第一个可成交桶（00:00 桶 → 00:15:10 轮次）之前 |
| 窗口内补偿 | `0 7,9,11,13 0 1 1 *` | 00:07/09/11/13：仅在 Gate 未通过时继续尝试；已 `SETTLED` 时秒级幂等短路 |
| 启动补偿 | `@EventListener(ApplicationReadyEvent)` | 仅当处于同一"可证窗口"内才尝试（复用 `BotConstants.ENV_PROD` 生产环境判定）；出窗直接 `MANUAL_REVIEW`，**不自动补结** |
| 开关 | `KEY_VIP_STOCK_ANNUAL_SETTLEMENT_ENABLED` | 非 `true` 直接返回；非生产环境直接返回 |

设计要点：

- 幂等由 §4.4 保证，因此"多触发点"安全且不引入分布式锁；
- 不使用 `vipStockRoundScheduler`（避免与 15 分钟轮次争用同一单线程）；
- 出窗判定即 Gate B：一旦新年度 00:00 桶的轮次已产生任何 `entry_time/exit_time ≥ boundaryTime` 的事实，就不再自动结算。

### 8.2 为什么必须避开 08:00–09:35

- 08:00 起 α 决策窗口开启（`StockAlphaRuleDefinition.DECISION_WINDOW_START`），决策、执行桶、换仓、入场结算在同一 `vipStockRoundScheduler` 单线程内串行；
- 入场过期 `entry_stale_at` = 信号桶 + 35 分钟（`StockPortfolioService.calculateEntryStaleAt`，`ENTRY_STALE_GRACE_MINUTES = 35`）：08:00 决策对应的宽限截止最早为 08:35，叠加 15 分钟桶与启动恢复补偿，**08:00–09:35 是"决策 + 执行 + 过期判定"的资源敏感区**；
- 同一时段还叠加每日摘要 08:10、Tornsy 巡检 07:00 之后的派生重建，任何重型/长事务任务都不应插入该区段；
- 本方案全部触发点都在 00:05–00:13，与 08:00–09:35 **完全不重叠**，且使用独立调度线程。

### 8.3 发布顺序

1. **迁移先行**：合并 `1.6.6/stocks-annual-settlement.yaml` 并在 `db.changelog-master.yaml` 末尾 include；开关默认 `false`；验证既有 changeset 未被改写（`databasechangelog` 校验和不变）。
2. **代码**：枚举值、状态枚举、`SettingConstants` 常量、台账 DO/DAO/Mapper（含 `ON CONFLICT DO NOTHING` 插入）、结算服务、渲染器、调度器、`StockNoticeTextFormat#formatBillion`、`requiresBatch` 白名单扩展。
3. **关闭态上线**：开关 `false` 时调度入口立即返回，不写台账、不建通知，对轮次/买卖/摘要零影响。
4. **打开前演练（非生产）**：用历史边界（造 23:45 边界 bar 与轮次 COMPLETED 记录）验证幂等、降级与渲染；演练不得在生产库写入。
5. **打开开关（仅 VIP_ALPHA）**：确认无正在运行的轮次重任务；打开后在可证窗口内验证一次结算与年报投递。
6. **首个年度结算（试运行）**：2027-01-01 00:05 产出 2026 年度试运行年报；人工复核报表数字与台账（§9）。
7. **不自动补结历史**：发布晚于可证窗口时，2026 年度落 `MANUAL_REVIEW`，由人工流程处理（Q3 待确认）。

### 8.4 回滚

- 关闭 `VIP_STOCK_ANNUAL_SETTLEMENT_ENABLED` 即停止结算与建行；
- 台账与通知行为追加式、只读事实，不参与轮次/资金路径，回滚无需数据修复；
- 已建 `PENDING` 通知仍可被 `sendPendingNotices()` 投递（`requiresBatch` 白名单已在代码中），或在需要静默时人工置终态；
- **不删除**已结算台账行（历史事实），无需也不得回滚 `availableCash`（本方案从未改过它）。

---

## 9 验收

风险等级 L3：金额、守恒、迁移、幂等、调度均须有证据。以下每项给出**入口 + 判定**；SQL 以本方案定义的表/列名为准。

### 9.1 资金守恒

- 判定 1：`closing_equity = closing_cash + closing_reserved + closing_market_value`（精度 `scale=2`，无余差）；
- 判定 2：`closing_equity = initial_cash + cumulative_extracted_after`；
- 判定 3：`extracted_amount = closing_equity − cumulative_extracted_before − initial_cash` 且 `cumulative_extracted_after = cumulative_extracted_before + extracted_amount`（数据库 CHECK `ck_annual_settlement_extraction_identity` 兜底）。

```sql
SELECT settle_year, closing_equity,
       closing_cash + closing_reserved + closing_market_value AS recomputed,
       initial_cash + cumulative_extracted_after AS identity
FROM torn_stock_portfolio_annual_settlement
WHERE portfolio_code = 'VIP_ALPHA' AND settlement_status = 'SETTLED'
  AND (closing_equity <> closing_cash + closing_reserved + closing_market_value
       OR closing_equity <> initial_cash + cumulative_extracted_after);
-- 期望：0 行
```

### 9.2 账实一致

- 用同一批 `torn_stock_market_bar_15m`（`bar_start_time = boundary_bar_start_time`、`build_version = '1.0.0'`、`usable = true`、`last_price > 0`）与同一批边界持仓**独立重算** `Σ(quantity × last_price × 0.999)`，与 `closing_market_value` 一致；
- `boundary_bar_digest` 按 `stocks_id` 升序重算（`stocksId:barId:lastPrice` 拼接后 SHA-256）与库中值一致；
- `cumulative_extracted_after = SUM(extracted_amount)`（同组合全部 `SETTLED` 行）：

```sql
SELECT portfolio_code, MAX(cumulative_extracted_after) AS ledger_balance, SUM(extracted_amount) AS ledger_sum
FROM torn_stock_portfolio_annual_settlement
WHERE portfolio_code = 'VIP_ALPHA' AND settlement_status = 'SETTLED'
GROUP BY portfolio_code
HAVING MAX(cumulative_extracted_after) <> SUM(extracted_amount);
-- 期望：0 行
```

- 测试层级：纯领域计算断言（权益/提取恒等式）+ 一条真实 PostgreSQL Mapper 测试（唯一键幂等与 CHECK 约束生效），不把边界矩阵复制到多层。

### 9.3 只对 VIP_ALPHA

```sql
SELECT DISTINCT portfolio_code FROM torn_stock_portfolio_annual_settlement;
-- 期望：仅 'VIP_ALPHA'
SELECT COUNT(*) FROM torn_stock_notice_audit WHERE notice_type = 'ANNUAL_SETTLEMENT';
-- 期望：每结算年度 1 行，且 group_id 为 VIP 群
```

代码侧：`portfolioCode` 只能取 `StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE`（不得由外部参数注入其它组合）。

### 9.4 影子零变化

结算前后对 `VIP_ALPHA_SHADOW` 做全字段指纹比对（`torn_stock_portfolio_slot` 与 `torn_stock_virtual_batch`）：

```sql
SELECT md5(string_agg(s::text, '|' ORDER BY s.id))
FROM torn_stock_portfolio_slot s WHERE s.portfolio_code = 'VIP_ALPHA_SHADOW';

SELECT md5(string_agg(b::text, '|' ORDER BY b.id))
FROM torn_stock_virtual_batch b WHERE b.portfolio_code = 'VIP_ALPHA_SHADOW';
```

并要求：台账中**无** `VIP_ALPHA_SHADOW` 行；`torn_stock_notice_audit` 中**无**影子年度通知（影子审计只能是既有 `SHADOW_RECORDED` 语义，且不在本方案范围内）。

### 9.5 缺边界行情不伪造

- 构造：某边界持仓股票的 23:45 桶缺失 / `usable = false` / `last_price <= 0`；
- 判定：不产生 `SETTLED` 行（金额列全 NULL）、状态为 `DEGRADED_PRICE_MISSING`、`degrade_reason` 含缺失股票简称（沿用 `missingPriceStocks` 展示）、**不创建**年报通知行；
- 恢复该 bar 后窗口内重试 → 转为 `SETTLED`，且 `extracted_amount` 与首次可证值一致（不得因重试改变口径）。

```sql
SELECT settle_year, settlement_status, closing_equity, degrade_reason
FROM torn_stock_portfolio_annual_settlement
WHERE portfolio_code = 'VIP_ALPHA' AND settlement_status = 'DEGRADED_PRICE_MISSING';
-- 期望：closing_equity 为 NULL；同期无 ANNUAL_SETTLEMENT 通知行
```

### 9.6 幂等重跑不重复提取

- 同一 `(portfolio_code, settle_year, rule_version)` 连续触发 N 次（含并发两次）：

```sql
SELECT portfolio_code, settle_year, rule_version, COUNT(*)
FROM torn_stock_portfolio_annual_settlement
WHERE deleted = 0 GROUP BY 1,2,3 HAVING COUNT(*) > 1;
-- 期望：0 行

SELECT summary_date, COUNT(*) FROM torn_stock_notice_audit
WHERE notice_type = 'ANNUAL_SETTLEMENT' AND deleted = 0
GROUP BY summary_date HAVING COUNT(*) > 1;
-- 期望：0 行
```

- 并要求多次运行前后 `extracted_amount`、`cumulative_extracted_after` 数值不变；通知审计 `send_attempt_count` 不因重复触发而无谓增长（领取串行语义）。

### 9.7 不强制平仓（跨年持仓保持）

```sql
SELECT batch_status, quantity, entry_time, exit_time, cooldown_until
FROM torn_stock_virtual_batch WHERE portfolio_code = 'VIP_ALPHA' AND deleted = 0;
-- 结算前后逐行一致；边界开放持仓仍为 OPEN/DATA_STALE/EXIT_PENDING/DATA_STALE_EXIT，无新增 SELL
```

### 9.8 不改 availableCash / 不改槽位

```sql
SELECT md5(string_agg(s::text, '|' ORDER BY s.id))
FROM torn_stock_portfolio_slot s WHERE s.portfolio_code = 'VIP_ALPHA';
-- 结算前后必须完全相同（含 initial_cash / available_cash / reserved_cash / current_batch_id / slot_status / lock_version）
```

补充：`initial_cash` 保持 `10000000000.00`；不得出现任何把 `available_cash` 重置为初始值的写入。

### 9.9 通知链复用与可投递

- `ANNUAL_SETTLEMENT` 行状态流转为 `PENDING → SENDING → SENT`（或失败进入 `FAILED_RETRYABLE` → `FAILED_FINAL`）；
- `payload_hash` 与 DB 读回的 `payload_snapshot` 经 `StockNoticePayloadCanonicalizer` 复核一致；
- 回归验证 `requiresBatch`：无 `batch_id` 的 `ANNUAL_SETTLEMENT` 行**不得**被判 `FAILED_FINAL`；
- 不得新增第二套发送实现（`StockNoticeBotSender` / `StockNoticeSendRecorder` 为唯一出口）。

### 9.10 调度隔离与窗口

- 全部年报触发点位于 00:00–00:15；**08:00–09:35 无任何年报触发**（以日志时间与线程名前缀 `stock-annual-settlement-scheduler-` 证明）；
- 年报调度不占用 `vipStockRoundScheduler` / `realtimeStockScheduler` 线程；
- 开关关闭时，调度入口对轮次与通知发送零影响（`sendPendingNotices` 行为不变）。

### 9.11 首年试运行展示

- 2026 年度行 `partial_year = true`、`coverage_days = N`、`annualized_return` 非 NULL 时年报**同时**出现区间收益与年化折算，并含"试运行"字样（不适用时按 §5.3 省略年化并注明样本不足）；
- 所有金额在消息正文中均为 `X.XXb`（不得出现原始大数或千分位整数）。

---

## 10 明确不做事项

1. **不强制平仓**：跨年持仓继续按原规则持有，不为结算触发任何 SELL。
2. **不动 `availableCash`**（同样不动 `reservedCash` / `initialCash`）：提取是账面科目，不发生任何资金划转；禁止用现金调整伪造提取或"补足"亏损。
3. **不改持仓/股数/成本/批次/冷却**：`torn_stock_virtual_batch` 零写入，历史事实只读。
4. **不把槽位现金重置回初始值**：不做"资金回归/基准重置"型写回（`StockPortfolioInitService` 也只补建缺失槽位）。
5. **不合并多槽为共享现金池**：本组合 `VIP_ALPHA` 为独立单槽账本，槽间资金不互通。
6. **不做影子仓**：`VIP_ALPHA_SHADOW` 不在本轮范围（待业务专家确认后另行处理），不做影子结算、不写影子台账、不发影子年报。
7. **不做槽外机会**：槽位外机会（含其独立账本/消息闭环）不在本轮交付。
8. **不做通用投资引擎/新研究平台**：不新增回测、研究、参数寻优、投影推演类能力，不建设第二套权益/收益计算框架。
9. **不做第二套消息平台**：不新增发送服务、不新增通知表、不新增 payload/冻结实现；只复用既有通知审计、领取、冻结、重发链。
10. **不做自动调参**：不依据年度收益自动调整 α 参数、槽位资金、止盈止损或提取比例。
11. **不自动补结历史年度**：跨窗口不做自动历史重建/自动补结，转人工核验（Q3）。
12. **不改写已执行的 Liquibase changeset**，不修改历史迁移文件。
13. **不新增依赖**（不引入新库/新框架）。

---

## 11 关联文档

- `.ai/knowledge/stocks/vip_stock_virtual_portfolio_strategy.md` §16（年度结算与年报）——**业务规则冻结口径的唯一来源**（α 口径：`VIP_ALPHA` 1 槽 10B、方案 C 累计提取利润、不强制平仓、不动 `availableCash`）。
- `.ai/knowledge/stocks/vip_stock_strategy_research_monthly_2026_09.md` §7（已归档）——原 V1 口径（5 槽 / 2B / 三类 BUY）的业务规则来源，仅作历史追溯，已不适用。
- `.ai/knowledge/stocks/vip_stock_alert_technical_design.md`——α 长期技术基线（权益/批次/槽位/通知/调度的既有契约）。
- `.ai/knowledge/stocks/vip_stock_virtual_portfolio_strategy.md`——组合长期业务基线。
- `.ai/knowledge/stocks/vip_stock_strategy_version_history.md`——策略版本与旧版退场记录。
- `.ai/knowledge/stocks/tornsy_stock_history_backfill_technical_design.md`——L3 技术设计文档写法参考（元信息 / 证据 / 明确不做事项 / 变更记录）。
- `.ai/knowledge/java_coding_style.md`——编码规范（实施时必须先读）。
- 代码证据（本轮已核实，均为既有实现）：
  - `torn/service/stocks/alert/portfolio/PortfolioEquityCalculator.java`
  - `torn/service/stocks/alert/portfolio/StockPortfolioService.java`
  - `torn/service/stocks/alert/portfolio/StockPortfolioInitService.java`
  - `repository/model/torn/stocks/portfolio/TornStockPortfolioSlotDO.java`
  - `torn/service/stocks/alert/market/round/VipStockAlertScheduler.java`
  - `torn/service/stocks/alert/market/StockMarketClock.java`
  - `torn/service/stocks/alert/alpha/config/StockAlphaRuleDefinition.java`
  - `torn/service/stocks/alert/summary/StockDailySummaryService.java`、`StockDailySummaryQueryService.java`、`StockDailySummaryNoticeService.java`、`StockDailySummaryRenderer.java`
  - `torn/service/stocks/alert/notice/StockNoticeSendService.java`、`StockNoticeAuditWriter.java`、`StockNoticeTextFormat.java`、`StockNoticePayloadCanonicalizer.java`
  - `constants/torn/enums/stocks/portfolio/StockNoticeTypeEnum.java`、`StockNoticeStatusEnum.java`、`StockRoundStatusEnum.java`
  - `repository/mapper/torn/stocks/portfolio/TornStockMarketBar15mMapper.xml`、`TornStockMarketRoundMapper.xml`、`TornStockVirtualBatchMapper.xml`、`TornStockNoticeAuditMapper.xml`
  - `src/main/resources/db/changelog/db.changelog-master.yaml`、`1.0.1-2.0.0/1.2.0/stocks-portfolio.yaml`、`1.0.1-2.0.0/1.6.1/stocks-alpha-persistence.yaml`、`1.0.1-2.0.0/1.6.1/stocks-notice-claim-retry.yaml`、`1.0.1-2.0.0/1.6.5/stocks-alpha-dual-basis.yaml`

---

## 12 变更记录

| 版本 | 日期 | 变更人 | 变更内容 |
|---|---|---|---|
| v0.1 | 待评审 | — | 首版技术设计：冻结 R1–R9；确定边界定义（12-31 23:45 桶）、三道门禁、追加式结算台账与"累计提取利润"科目载体、提取恒等式、`requiresBatch` 白名单扩展（P0）、`X.XXb` 格式化与年报渲染模板、00:05 / 00:07–00:13 调度窗口与发布/回滚顺序、11 项验收；待确认事项见 §1.5 |

---

> 实施前置：本文件为**设计文档**，不构成实施授权。任何代码、迁移或数据操作都必须另行取得需求方确认，并遵守 `.ai/prompts/system_prompt.md` 的风险分级与范围控制要求。
