# VIP群Torn股票系统 策略版本历史与旧版策略退场存档

## 元信息

- 文档类型：策略版本管理 + 旧版策略规格存档 + 弃用清单（三合一）
- 适用项目：Golden-Eye
- 最后更新：2026.09.18
- 维护人：Bai（待业务专家审核）
- 状态：**草稿，待业务专家审核**
- 时区：Asia/Shanghai
- 关联文档：
  - `.ai/knowledge/stocks/vip_stock_virtual_portfolio_strategy.md`（长期业务基线，现行 α 策略唯一口径依据）
  - `.ai/knowledge/stocks/vip_stock_alert_alpha_convergence_technical_plan_one_time.md`（本轮 A/B/C/D 统一技术方案；本文是该方案交付项 D 的交付物）

---

## 1. 本文的作用

本文只回答一个问题：**旧版（α 之前）的策略到底是什么、为什么退场、由什么替代、代码在哪里**。

三个用途：

1. **策略版本管理**：保留策略升级的可回溯记录，避免「改过什么、为什么改」只存在于提交历史里；
2. **规格存档**：旧版代码删除后，历史批次（`torn_stock_virtual_batch` 中 `buy_rule_version=1.0.0`、旧 `close_type` 的行）仍然可被解释；
3. **弃用清单**：满足交付项 D-5 的四要素要求——组件、理由、替代者、最后引用点。

**本文不收录 α=0.04 之后的规则**（含本轮 A/B/C/D）。α 现行规则以长期业务基线为唯一口径，避免双重口径。

---

## 2. 策略版本总表

| 版本 | 名称 | 载体 | 状态 | 说明 |
|---|---|---|---|---|
| V1 | 旧版三类 BUY + 旧版五槽竞争 | `VIP_FORMAL` 5 槽 × 2B / `VIP_SHADOW_CANDIDATE` / `UNLIMITED_SHADOW` | **已退场** | 本文 §3 完整规格；2026-09-18 起停止新入场，随后清仓并删码 |
| V2 | α=0.04 单槽 Top1 | `VIP_ALPHA` 1 槽 × 10B | **现行（早间窗口待实施）** | 以长期业务基线 §3–§5 为准 |
| V3 | α 多槽相位分散（研究） | `VIP_ALPHA_SHADOW` 2 槽 × 5B | **待实施，仅影子观察** | 只降波动类改动，期望收益与 V2 相同；采纳与否由成员体验决定 |

---

## 3. V1 旧版策略完整规格（存档）

> 以下规格来自退场时的代码实现，用于解释历史批次，**不再参与任何新决策**。

### 3.1 组合与资金

```text
VIP_FORMAL            : 旧版正式组合，5 个独立槽位，每槽初始资金 2,000,000,000.00
VIP_SHADOW_CANDIDATE  : 候选影子组合，5 个独立槽位，每槽 2B（与正式组合共享规则、完全隔离）
UNLIMITED_SHADOW      : 无限资金影子，只计算理论收益，不操作正式槽位
```

- 槽位之间资金**不自动调拨**，各自实现槽内复利；
- 股数 = `floor(可用资金 ÷ 入场参考价)`，余款保留在原槽；
- 卖出所得 = `股数 × 卖出参考价 × 0.999`（0.1% 手续费），回笼到原槽；
- `ENTRY_PENDING` 预留槽位与预算，取消时释放完整预留资金与槽位。

### 3.2 买入策略（三类）

三类策略各自声明**适用风格**与**触发条件**，命中后计算质量分：`StockBuyStrategyEnum` = `{DEEP_MEAN_REVERSION_BUY, RANGE_LOWER_BUY, STRICT_REBOUND_CONFIRM_BUY}`。

#### 3.2.1 DEEP_MEAN_REVERSION_BUY（深度均值回归）

- 适用风格：NARROW / RANGING / STEADY；
- 触发条件（全部满足）：距 30 日最低价 ≤ 0.3%；effectiveZ1 ≤ -2.0；近 7 日收益率 ≥ -1%；MA7/MA30 - 1 ≥ -2%（中期趋势保护）；
- NARROW 风格对 Z1 打 0.6 折（effectiveZ1 = zscore1d × 0.6）；
- 质量分 = 100 + max(0, -effectiveZ1) × 10 + max(0, 0.003 - pctAbove30dLow) × 1000。

#### 3.2.2 RANGE_LOWER_BUY（区间下沿）

- 适用风格：NARROW / RANGING；
- 触发条件（全部满足）：30 日通道宽度 ≤ 8%；position30 ≤ 10%；effectiveZ1 ≤ -0.5（NARROW 不打折）；近 6 小时收益率 ≤ 0；MA7/MA30 - 1 ≥ -2%；
- **RANGE 专属绝对趋势保护**（策略专属资格守卫，与 matches 分离）：return7d ≥ -2% 且 MA7/MA30 - 1 ≥ -2%；等于 -2% 通过；特征缺失时记为数据不足并写拒绝观察，不建立正式批次。

#### 3.2.3 STRICT_REBOUND_CONFIRM_BUY（严格反弹确认）

- 适用风格：**仅** WEAK / DECLINER；
- 触发条件（全部满足）：距 30 日最低价 ≤ 0.5%；return1d > 0；zscore1d ≥ 0.8；参考价 ≤ MA30 × 1.002；
- 质量分基础分 60，Z1 系数 5。

### 3.3 质量分与候选排序

- 同一股票命中多个策略时，取**质量分最高**的策略作为 `primaryStrategy`；
- 多股票竞争正式槽位时的排序：`qualityScore DESC → stocksId ASC`（`StockCandidateRankingPolicy`）；
- 该排序只用于**旧版**候选竞争，与 α 的 `alphaScore DESC → stocksId ASC` 无任何关系。

### 3.4 风格与风险门禁

- 月度状态表 `torn_stock_monthly_state` 提供 `personality`（风格）与 `risk_level`（风险）；
- `StockStrategyFitEnum` 决定策略风格适配性，不适配则策略直接不命中；
- 风格/风险规则版本写入批次 `style_rule_version` / `risk_rule_version` 供历史解释。

### 3.5 退出规则（固定优先级，取首个命中）

| 顺序 | 规则 | 条件 | 结果 |
|---|---|---|---|
| 1 | 目标退出 | netReturn ≥ +0.8% | `CLOSED_TARGET` |
| 2 | 风险退出 | netReturn ≤ -1.5% | `CLOSED_RISK` |
| 3 | 时间退出 | 持有 ≥ 14 天 | `CLOSED_TIME` |
| 4 | 区间恢复退出 | 策略为 RANGE_LOWER_BUY 且 netReturn > 0 且 position30 ≥ 0.60 | `CLOSED_RANGE`（high30 == low30 时 fail-closed） |

- `netReturn = 当前价 ÷ 入场参考价 × 0.999 - 1`；
- 时间退出以轮次时间 `roundTime` 为基准，不读系统时钟；
- 批次表另有 `dynamic_sell_state` / `peak_drawdown` / `peak_price` 等**动态峰值回撤**状态列，用于动态 SELL 研究轨道（`DYNAMIC_SELL_SHADOW`），未作为旧版正式退出规则上线。

### 3.6 影子与研究轨道

| 轨道 | 载体 | 用途 |
|---|---|---|
| 候选影子 | `VIP_SHADOW_CANDIDATE`（`SHADOW_FORMAL_CANDIDATE`） | 与正式组合同规则、同信号、独立 5 槽账本 |
| 无限资金影子 | `UNLIMITED_SHADOW` | 不占用槽位，模拟无限资金，只算理论收益 |
| 拒绝观察 | `REJECTED_OBSERVATION` | 记录被资格门禁拒绝的信号及其后续表现，供研究 |
| 回放研究 | `StockReplay*` | 只读回放引擎，含 `UNLIMITED_SHADOW` / `DYNAMIC_SELL_SHADOW` 等轨道 |

### 3.7 通知

- 旧版 BUY/SELL 模板（`message_rule_version = 1.0.0`）；
- 发送、审计、payload 冻结与幂等链路为公共基础设施，**α 继续复用，不在删除范围**。

---

## 4. 版本切换与历史数据解释

### 4.1 切换时间线

| 时间 | 事件 |
|---|---|
| 2026-09-05 | α=0.04 规则冻结（`ALPHA_0.04_V1`、股票池 `STOCKS_35_V1`） |
| 2026-09-17 | α 首个决策与批次（`A20260917-9` / CNC） |
| 2026-09-18 | 旧版正式新入场已在代码侧关闭（候选只走候选影子，不再创建 `VIP_FORMAL` 正式批次） |
| 2026-09-18 | 收敛交付 A/B/C/D 方案定稿：旧版进入退场流程 |

### 4.2 历史数据解释规则（必须遵守）

- 历史 `VIP_FORMAL` 批次按**开仓时规则**收尾，不得改写为 α 批次；
- 旧版 SELL 不得标记为 `ALPHA_REBALANCE`；
- 旧版收益不得与 α 收益混算；
- 旧版与 α 允许同时持有同一股票，但组合 CODE、批次来源、资金结算、规则版本、消息与收益统计必须隔离；
- `StockLedgerTypeEnum` 中的 `SHADOW_FORMAL_CANDIDATE` / `UNLIMITED_SHADOW` / `REJECTED_OBSERVATION` 枚举值**保留**（历史行需可解析），仅停止写入。

---

## 5. 弃用清单（D-5：组件 / 理由 / 替代者 / 最后引用点）

| 组件 | 包 / 文件 | 退场理由 | 替代者 | 最后引用点 |
|---|---|---|---|---|
| 三类 BUY 策略 | `alert/signal/strategy/**`（3 个实现 + 接口 + 工具） | α 排名取代策略命中 | `StockAlphaRankingCalculator` + `StockAlphaTargetPolicy` | `StockBuySignalEvaluator` |
| 质量分与候选排序 | `alert/signal/policy/**`、`BuyStrategyMatcher` | α 用 `alphaScore`，不用 `qualityScore` | `alphaScore DESC → stocksId ASC` | `StockRoundTransactionService` 候选编排段 |
| 买入信号评估与资格门禁 | `alert/signal/**`（评估器、上下文、资格、信号状态） | 无旧版候选即无消费方 | α 决策服务 | `StockRoundTransactionService` |
| 候选影子与无限资金影子 | `alert/shadow/StockCandidateTrackAllocationService`、`StockShadowTrackRecorder` | 旧版研究轨道，D 项停止 | α 影子组合 `VIP_ALPHA_SHADOW` | `StockRoundTransactionService` |
| **α 换仓通知审计写入** | `alert/shadow/StockShadowRecordWriter` | **不是废弃组件**：α 生产换仓依赖它，必须先搬迁 | `alert/alpha/notice/StockAlphaNoticeAuditWriter`（新增） | `StockAlphaRebalanceService` |
| 月度风格/风险门禁 | `alert/monthly/**` | 只服务旧版策略适配 | 无（α 不评风格与风险） | `StockBuySignalEvaluator` |
| 拒绝观察 | `alert/observation/**` | 旧版资格门禁的研究产物 | 无 | `VipStockAlertScheduler`、`StockAlertRuntimeGate` |
| 回放研究 | `replay/**` | 旧版策略族的研究工具，随旧版一并停用 | 无 | 超管回放指令入口 |
| 动态 SELL 研究摘要 | `alert/summary/DynamicSellResearchSummaryCalculator`、`StockDynamicSellResearchConstants` | 动态 SELL 未上线，属研究残留 | 无 | `StockReplayEngine` |
| 孤儿测试夹具 | `StockNoticeAuditClaimItTest`（**已于交付前删除，非本次开发任务**） | 夹具脱离测试事务，清理失败即污染生产通知审计 | 保留 `TornStockNoticeAuditMapperTest`（组级原子回写，事务回滚） | 无 |

---

## 6. 明确保留（不在退场范围）

- `torn_stock_alpha_*`（决策、日线快照）；
- α 决策/入场/换仓/通知链与 `StockAlphaRuleDefinition`；
- `StockEntrySettlementService`、`StockBatchPathService`（α 与旧版共用，需按组合分流）；
- 公共通知发送、审计、payload 冻结与幂等链；
- 股票日报（战报）、数据巡检与派生数据重建；
- `StockLedgerTypeEnum` 的历史枚举值。

---

## 7. 待业务专家确认项

1. §3 的三类 BUY 触发条件与质量分公式，是否与业务记忆中的最终版本一致；
2. §3.5 的退出优先级与阈值（+0.8% / -1.5% / 14 天 / RANGE position30 ≥ 0.60）是否完整，是否有已上线但未在代码中体现的口径；
3. §3.6 四个研究轨道是否全部可以停止（尤其回放研究是否仍有在办的业务需求）；
4. §4.1 的时间线是否需要补充更早的上线日期；
5. 本文补全后，是否需要把旧版收益统计口径也一并存档。

---

## 8. 变更记录

| 版本 | 日期 | 内容 |
|---|---|---|
| 草稿 | 2026-09-18 | 首次成文：V1 旧版策略规格、版本时间线、弃用清单；待业务专家审核 |