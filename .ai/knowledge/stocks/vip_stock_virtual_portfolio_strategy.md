# VIP群Torn股票系统虚拟组合业务方案

## 1. 文档定位

- 文档类型：业务规则与开发依据
- 适用功能：VIP群股票BUY/SELL提醒、系统虚拟组合、影子研究
- 数据基线：2026-01-26～2026-07-23
- 当前证据等级：`SHADOW_CANDIDATE`
- 相关文档：
  - `stock_personality_monthly_calibration.md`
  - `stock_personality_full_history_2026_07.md`
  - `virtual_portfolio_research_evidence.md`
  - `vip_stock_alert_strategy_background.md`

本文定义投资业务规则。开发人员应据此编写技术方案和实现，不得自行改变策略语义、成交口径、批次边界或验收标准。

---

## 2. 产品目标与收益原则

### 2.1 产品目标

建立不绑定群成员个人持仓的公开系统虚拟组合：

```text
系统发现BUY机会
→ 下一连续15分钟bar建立系统参考仓
→ 发布带batchNo的BUY
→ 按系统参考成本独立跟踪
→ 发布与原BUY严格配对的SELL
```

系统不保存成员是否跟随，也不计算成员个人盈亏。SELL只关闭系统公开批次；未跟随原BUY的成员无需操作。

### 2.2 收益目标

年化20%只是早期阶段性设想，不是收益上限。策略研究应在风险、稳健性和可执行性不恶化的前提下持续寻求更高收益，但禁止：

- 为达到目标年化使用验证/留出数据反向调参；
- 选择仅单月或少数交易驱动的最高年化版本；
- 以增加回撤、消息噪声或不可解释性换取表面收益；
- 把历史年化折算写成未来承诺。

用户报告其截至2026-07-24的人工操作年化约24%，但现有7月交易日志尚未更新；该数字仅作为现实参照，不属于本方案已独立复核的研究证据。

当前冻结数据下，正式候选5槽组合的历史年化折算约19.6%～20.5%。这只是短历史基线，后续目标应通过新增月份、成熟风格、更优BUY和经验证的动态SELL逐步提高，而不是围绕20%封顶。

### 2.3 非目标

- 不承诺成员能成交于系统参考价；
- 不计算个人持仓收益；
- 不向所有持股者广播无来源的市场SELL；
- 不使用当前数据不支持的真实成交量、盘口或蜡烛形态；
- 不把福利型长期持仓纳入短线虚拟组合。

---

## 3. Torn交易与回放口径

- 股票价格每分钟更新；
- 买入无税；
- 卖出收取卖出总值0.1%；
- 当前15分钟bar只产生决策，参考成交使用下一根连续15分钟bar；
- 数据断层不得跨越成交；
- 期末清算仅用于历史净值计算，不能在生产中伪装成策略SELL。

系统批次扣费后净收益：

```text
netReturn = exitReferencePrice / entryReferencePrice × 0.999 - 1
```

---

## 4. 四层账本

### 4.1 原始信号事件账本

记录所有false→true信号，不受资金和槽位限制。至少保存：

- 股票、策略、信号时间和特征快照；
- 风格先验、成熟度、风险等级及规则版本；
- 资格结果、候选排序和拒绝原因；
- 后续MFE、MAE和理论结果。

该层不发群消息，用于分析漏买、门禁误杀和排序质量。

### 4.2 无限资金影子批次

- 无限跨股票资金和槽位；
- 同一股票×策略版本最多一个开放影子批次；
- 持续信号期间不重复建立高度相关批次；
- 完整模拟BUY→SELL。

该层回答“信号本身是否有优势”。

### 4.3 拒绝观察事件

拒绝观察用于衡量正式门禁和容量约束的机会成本。它不是正式持仓，也不是无限资金影子持仓。

#### 状态与账本

- 拒绝观察批次始终保持`CANCELLED`和`REJECTED_OBSERVATION`；
- 不进入`ENTRY_PENDING/OPEN/EXIT_PENDING`；
- 不占槽位、不预留资金、不发BUY、不产生正式SELL；
- 通过独立观察器回写信号事件的理论路径字段。

#### 统一理论入场

可观察的拒绝原因统一使用原信号后的紧邻下一连续15分钟bar：

```text
theoreticalEntryBar = signalBar + 15分钟
```

该bar必须满足正式bar可用标准，且：

```text
theoreticalEntryPrice / signalReferencePrice - 1 <= 0.0015
```

只限制向上偏离。理论入场失败时不等待更晚bar，直接结束观察：

```text
resolvedAt = expectedEntryBarEnd + 5分钟
observationResult = NO_THEORETICAL_ENTRY
laterMfe = null
laterMae = null
```

#### 观察窗口

成功获得理论入场后：

```text
observationStart = theoreticalEntryBarEnd
observationDeadline = theoreticalEntryTime + 14天
```

从理论入场价开始，使用之后所有可用15分钟bar更新：

```text
laterMfe = max(observedPrice / theoreticalEntryPrice - 1)
laterMae = min(observedPrice / theoreticalEntryPrice - 1)
```

MFE/MAE不扣卖出费，表示纯价格路径；另行计算理论正式生命周期结果时，净收益扣0.1%卖出费。

观察器并行运行与正式批次相同的冻结退出规则：

```text
+0.8%目标 / -1.5%风险 / RANGE position30>=0.60且盈利 / 14天
```

首次命中退出规则时，以紧邻下一连续bar作为理论退出；成功成交后：

```text
resolvedAt = theoreticalExitTime
```

若直到14天仍无退出，`resolvedAt=observationDeadline`，使用截止前最后可用bar记录路径和理论期末净收益，但不生成SELL消息。

#### 数据缺口

- 缺口期间不插值、不更新MFE/MAE；
- 观察日历时钟继续前进，不因缺口延长14天窗口；
- 退出信号后的紧邻成交bar不可用时，不跨缺口成交；继续观察到下一条正式退出事实或14天截止；
- 截止前没有任何理论入场后的可用bar时，结果标记`OBSERVATION_DATA_INSUFFICIENT`，MFE/MAE保持null；
- 截止时有部分可用bar则保留已观测MFE/MAE，并标记`observationDataIncomplete=true`。

#### 原因口径

以下拒绝原因使用同一套理论入场和14天窗口：

```text
STYLE_NOT_FIT
RISK_HIGH_SHADOW_REJECT
ABSOLUTE_TREND_GUARD_FAILED
SAME_STOCK_OPEN
COOLDOWN_ACTIVE
SIGNAL_NOT_RESET
PORTFOLIO_FULL
```

以下数据/执行拒绝只记录“无法理论入场”，不建立14天路径：

```text
STYLE_MISSING
STYLE_STALE
DATA_NOT_CONTIGUOUS
ENTRY_DATA_STALE
ENTRY_PRICE_DEVIATION
```

原因是这些事件缺少合法或及时的参考成交，不能事后用更晚价格伪造机会。

### 4.4 5槽正式系统虚拟组合

```text
最大5槽
每槽初始资金20亿
总初始资金100亿
同一股票最多一个开放正式批次
```

5槽限制只属于正式组合。每槽20亿是技术方案审核阶段冻结的生产资金口径，采用整数股数、余款现金和槽内复利；现有历史研究结果基于每槽4亿，因此实施前必须按20亿口径重新执行精确资金账本回放。6槽结果仅保留为容量敏感性证据，不再作为主业务参数。

---

## 5. 月度股票状态

每支股票每月保存三个独立概念。

### 5.1 策略适配先验 `strategyFitPrior`

沿用六类风格：

- `DECLINER`
- `WEAK`
- `NARROW`
- `RANGING`
- `STEADY`
- `STRONG`

它只负责策略适配、少量参数修正和候选排序，不是独立BUY/SELL信号。

### 5.2 成熟度 `maturity`

| 状态 | 历史范围 | 含义 |
|---|---:|---|
| `M0_UNMATURE` | <60天或不足2个完整月 | 极早期 |
| `M1_EARLY` | 60～119天 | 早期临时风格 |
| `M2_PROVISIONAL` | 120～239天 | 可作先验，不能视为成熟事实 |
| `M3_SEASONED` | 240～364天 | 接近成熟 |
| `M4_MATURE` | ≥365天 | 完整滚动一年 |

当前数据最高只有M2。群消息若展示风格，必须同步展示成熟度。

### 5.3 风险等级 `riskLevel`

```text
NONE / MEDIUM / HIGH
```

风险等级回答是否存在持续下移、半山腰或继续刷新低点的风险。当前研究表明，直接把HIGH设为正式硬否决会将历史年化从19.61%降至16.97%，且未改善MDD，因此首期：

- `strategyFitPrior`决定正式策略资格；
- `riskLevel`用于保存、解释、降权和shadow观察；
- HIGH暂不自动删除正式候选；
- 完整一年或新增前向证据支持后，才可升级为硬门禁。

### 5.4 时间口径

对目标月份M：

```text
evidenceEnd = M月开始前最后一个有效时点
evidenceStart = max(首条有效历史, evidenceEnd - 365天)
```

整月冻结。开仓批次固化当月版本；次月变化只影响新候选。

风格缺失或过期时，禁止默认`STEADY`：

```text
STYLE_MISSING / STYLE_STALE
→ 暂停正式BUY
→ 仅记录原始信号
```

详细指标和迟滞见`stock_personality_monthly_calibration.md`。

---

## 6. 正式BUY策略

### 6.1 深度均值回归

策略标识：`DEEP_MEAN_REVERSION_BUY`

适用：`NARROW / RANGING / STEADY`

```text
距30日最低价 <= 0.3%
AND effectiveZ1 <= -2.0
AND 7日收益 >= -1%
AND MA7 / MA30 - 1 >= -2%
```

```text
NARROW: effectiveZ1 = rawZ1 × 0.6
其他风格: effectiveZ1 = rawZ1
```

禁止：`DECLINER / WEAK / STRONG`。

### 6.2 区间下沿

策略标识：`RANGE_LOWER_BUY`

适用：`NARROW / RANGING`

```text
30日价格带宽 <= 8%
AND 当前位于30日区间底部10%
AND effectiveZ1 <= -0.5
AND 近6小时收益 <= 0
```

必须保留MA7/MA30和7日收益的绝对趋势保护，避免将下降中的窄带误判为安全区间。

### 6.3 严格反弹确认

策略标识：`STRICT_REBOUND_CONFIRM_BUY`

仅适用：`WEAK / DECLINER`

```text
距30日最低价 <= 0.5%
AND return1d > 0
AND Z1 >= 0.8
AND 当前价格 <= MA30 × 1.002
```

它不是所有低位BUY的前置条件，而是弱势股票禁止裸买后的独立路径。

### 6.4 不进入正式组合的BUY

- 当前Java宽反弹BUY：历史连续年化约3.66%，淘汰出正式组合；
- 当前价格版趋势回调/延续：跨窗口为负，继续研究；
- 未支持真实成交量时，不得宣称验证“放量突破”。

### 6.5 冻结候选排序

同一15分钟轮次出现多个BUY并竞争有限槽位时，使用原冻结回放中的质量分降序：

```text
candidateComparator =
  qualityScore DESC
  → stocksId ASC
```

质量分按策略内部计算，不额外写死策略族优先级：

```text
deepScore = 100 + max(0, -effectiveZ1) × 10
                 + max(0, 0.003 - low30Distance) × 1000

rangeScore = 80 + max(0, 0.10 - position30) × 100
                + max(0, -effectiveZ1) × 5

reboundScore = 60 + Z1 × 5
                  + max(0, 0.005 - low30Distance) × 1000
```

同一股票多策略同时命中时，先按同一qualityScore选择`primaryStrategy`，其余写入`matchedStrategies`，只创建一个候选。不得使用枚举顺序、集合遍历顺序或股票简称作为主要排序。历史冻结数据中同轮最多3个BUY候选，排序敏感性未改变5槽组合结果；仍需保留同分规则以保证生产确定性。

### 6.6 候选流程

```text
数据完整
→ 读取月度状态
→ 计算三个BUY策略
→ 应用策略适配与绝对趋势保护
→ 记录成熟度和风险
→ false→true边沿
→ 同股、冷却、复位检查
→ 候选排序
→ 5槽竞争
→ 下一连续bar建立参考仓
→ 发布BUY
```

同股多策略同时命中时只建一个批次，保存`primaryStrategy`和`matchedStrategies`。

无空槽时不发BUY、不建正式批次，只进入容量拒绝观察。

---

## 7. SELL业务模型

### 7.1 基本原则

每个开放批次必须独立计算SELL，不依赖：

- 是否满仓；
- 是否出现新BUY；
- 是否允许换仓；
- 无状态市场SELL是否得分最高。

开放批次至少维护：参考成本、持有时间、当前净收益、峰值/谷值、MFE、MAE、峰值回撤、BUY策略族、风格和规则版本。

### 7.2 首期正式生命周期底座

当前证据最强的正式关闭规则：

```text
净收益 >= +0.8% → CLOSED_TARGET
净收益 <= -1.5% → CLOSED_RISK
持有 >= 14天 → CLOSED_TIME
RANGE盈利且position30 >= 0.60 → CLOSED_RANGE
```

这些阈值是当前可靠基线，不是长期收益上限或最终投资哲学。`position30 = (currentPrice-low30)/(high30-low30)`；0.60来自冻结回放原实现。邻域0.50/0.55/0.60/0.65/0.70在独立复算中均为正，0.60不是事后新搜索的最高点，正式版本冻结为0.60。

### 7.3 动态自然SELL

动态SELL必须始终独立计算，首期先shadow：

```text
dynamicTarget =
    基础可保护利润
  + 个股价格带宽
  + 剩余回归空间
  + 趋势仍强奖励
  - 持有时间衰减
```

主要证据：

- 批次净收益与MFE；
- 峰值回撤；
- Z1、return6h、return1d；
- MA7/MA30；
- 30日位置和带宽；
- BUY策略族和持有时间。

不同策略的恢复语义：

- deep：恢复到短周期常态；
- range：回到动态区间中上部；
- rebound：Z1偏热且短期动量仍正时主动落袋。

峰值回撤仅作后备保护。动态规则当前最好连续年化约16.08%，尚未超过正式宽生命周期，因此不能直接替代，但应在新增月份中持续优化和验证，以追求高于当前约20%的长期目标。

### 7.4 风险SELL

风险SELL与盈利SELL分离。除-1.5%硬边界外，结构风险必须由多项证据共同确认，例如：

- MA7/MA30显著恶化；
- return6h和return1d均负；
- 接近30日低点并继续走弱；
- Z1显著下行；
- 持续一定时间而非单bar噪声。

裸连续下跌永不作为独立SELL。

### 7.5 可选换仓

默认：`rotationEnabled=false`。

换仓只是满仓时的shadow增强：

```text
5槽已满
AND 旧仓已有利润
AND 旧仓反弹衰竭
AND 新候选为STRICT_REBOUND_CONFIRM
AND candidateValue > holdValue + buffer
```

禁止割掉亏损均值回归仓追逐新机会。换仓不得延迟自然SELL、覆盖风险SELL或成为SELL前置条件。

---

## 8. 数据连续性与批次状态

### 8.1 连续bar定义

连续性按**市场时间桶**判断，不按服务器是否连续在线判断。15分钟桶记为：

```text
B0 = [T, T+15m)
B1 = [T+15m, T+30m)
```

B1是B0的连续下一bar，只要两个桶都存在且达到可用数据标准；服务器在桶内短暂重启3～5分钟，不会自动破坏连续性，也不需要重新积累完整15分钟周期。服务恢复后应从数据库补算上次成功水位至当前时点的分钟记录和15分钟特征。

首期冻结的bar可用标准：

```text
bucketSampleCount >= 10
AND lastSampleTime >= bucketEnd - 5分钟
```

含义：一个15分钟桶最多允许缺失5个分钟采样，且桶尾最后5分钟内必须至少有一个采样；bar价格使用桶内最后一个实际价格。桶首缺失但后续样本充足可以接受，避免3～5分钟重启造成无谓停摆。

若任一桶不存在或未达标准，则为断层：

```text
DATA_NOT_CONTIGUOUS
```

不得把更晚的“下一条可用记录”当成紧邻下一bar成交。冻结数据13,646个15分钟桶中，样本数低于10的只有21个（0.154%），桶尾陈旧超过5分钟的11个（0.081%）；该规则能容忍常见短重启，同时隔离长宕机。

### 8.2 ENTRY_PENDING陈旧

`ENTRY_PENDING`等待的是紧邻下一时间桶的数据完成，而不是要求服务连续运行：

```text
expectedEntryBarStart = signalBarStart + 15分钟
expectedEntryBarEnd = signalBarStart + 30分钟
staleAt = expectedEntryBarEnd + 5分钟
```

服务在线时，若到`staleAt`该桶仍不存在或不满足bar可用标准，候选以`ENTRY_DATA_STALE`取消。服务在此期间重启时，恢复后先补算历史；只要补算得到的expected entry bar满足上述标准，且恢复时刻不晚于`staleAt`，仍可建立参考批次。

恢复晚于`staleAt`时，即使数据库可补出该桶，也不补发已经过时的群BUY，候选取消。换言之，从信号bar开始最多等待35分钟；参考成交仍只能属于紧邻下一桶，不能使用更晚桶。

### 8.3 OPEN期间数据陈旧

开放批次遇到不可用桶时进入`DATA_STALE`，暂停普通目标、动态和换仓决策，不得跨缺口模拟成交。服务重启后必须从持久化的最后成功采集/特征水位补算；补算恢复的是历史状态，不要求重新等待完整30日或15分钟窗口。数据恢复并形成一个满足可用标准的新桶后，可恢复持仓评估；风险处置由独立运维/灾难规则决定，不能伪造缺口期间价格。

### 8.4 状态生命周期

```text
CANDIDATE
  ├─ 数据/价格/风格/槽位不满足 → CANCELLED
  └─ 下一连续bar参考成交 → OPEN

OPEN
  ├─ 目标关闭 → CLOSED_TARGET
  ├─ 动态关闭（未来正式化） → CLOSED_DYNAMIC
  ├─ 区间关闭 → CLOSED_RANGE
  ├─ 风险关闭 → CLOSED_RISK
  ├─ 时间关闭 → CLOSED_TIME
  ├─ 可选换仓 → CLOSED_ROTATION
  └─ 数据过期 → DATA_STALE
```

### 8.5 冷却与复位

正常、动态、区间、时间或换仓关闭后冷却24小时；风险关闭后冷却48小时。冷却结束后必须先观察BUY条件回到false，再等待新false→true边沿。

---

## 9. 跟随窗口与群消息

### 9.1 ENTRY价格偏离与成员跟随窗口

系统参考成交价来自严格下一连续bar。若下一bar价格相对信号bar上涨超过0.15%，取消候选：

```text
entryDeviation = entryReferencePrice / signalReferencePrice - 1
entryDeviation > 0.0015 → CANCELLED / ENTRY_PRICE_DEVIATION
```

只限制向上偏离；价格相同或下跌不因价格偏离取消，但仍需重新通过数据完整性和批次状态检查。冻结数据1303个连续BUY边沿中，向上偏离最大约0.1495%，99分位约0.1183%；0.15%不会删除历史样本，作为覆盖历史极值后的保守边界。该阈值与成员最高跟随价保持一致，避免系统在已经不建议成员追入的价格建立公开批次。

成员跟随窗口初值：

```text
followUntil = BUY发送后60分钟
followMaxPrice = entryReferencePrice × 1.0015
```

超过任一条件后不建议追入，但系统继续管理原批次。该参数需用真实消息延迟和价格偏离shadow校准。

### 9.2 BUY消息必须包含

- batchNo、股票和BUY策略；
- 风格先验、成熟度和风险等级；
- 系统参考买价；
- 跟随截止和最高跟随价；
- 当前槽位；
- “系统不记录个人持仓”的风险提示。

### 9.3 SELL消息必须包含

- 原BUY batchNo；
- 参考买卖价和扣费后系统净收益；
- 持有时长；
- 关闭类型和解释；
- “仅对应本批次，未跟随者无需操作”。

风险退出不能称为止盈；时间退出必须说明它只代表系统结束该批次。

### 9.4 消息降噪

- 每15分钟评估；
- 同轮BUY和SELL分别合并；
- 每轮最多展示3个动作；
- 风险SELL优先于其他SELL，SELL优先于BUY；
- SELL不得因预算静默丢弃；
- 每日一条组合摘要；
- 开放期间不重复播报BUY。

目标：平均实际消息≤4条/日，P95≤6条/日。当前离线估算约2.32条/日（含每日摘要），需生产shadow复核。

---

## 10. 业务数据对象

### 10.1 月度状态

至少包含：

```text
stocksId, effectiveMonth
strategyFitPrior, maturity, riskLevel
suggestedPersonality, finalPersonality
manualOverride, overrideReason
metricSnapshot
personalityRuleVersion, riskRuleVersion
evidenceStartTime, evidenceEndTime
calculatedAt, confirmedAt
```

### 10.2 原始信号事件

至少包含：

```text
stock, strategy, signalTime, featureSnapshot
styleVersion, maturity, riskLevel
eligibilityResult, eligibilityReason
edgeState, candidateRank
portfolioDecision, rejectReason
laterMfe, laterMae
```

### 10.3 系统批次

至少包含：

```text
batchNo, stock
primaryStrategy, matchedStrategies
buyRuleVersion, sellRuleVersion
styleRuleVersion, riskRuleVersion
allocationRuleVersion, messageRuleVersion
status, signalTime, entryTime, entryReferencePrice
stylePrior, styleMaturity, riskLevel, styleEffectiveMonth
followUntil, followMaxPrice
peakPrice, troughPrice, currentNetReturn, mfe, mae
dynamicSellState, maxHoldUntil
exitSignalTime, exitTime, exitReferencePrice, exitReason, netReturn
cooldownUntil, resetObserved
openNoticeStatus, closeNoticeStatus
```

### 10.4 通知审计/outbox

至少包含：

```text
batchId, noticeType, scheduledRoundTime
payloadHash, payloadSnapshot
sendStatus, attemptCount, nextRetryTime
sentTime, groupId, errorMessage
```

目标：重复BUY=0、重复SELL=0、孤儿SELL=0、永久漏发SELL=0。

---

## 11. 业务职责边界

开发方案应将以下职责分开：

1. 月度风格与风险状态；
2. 无持仓市场信号评估；
3. 策略资格与拒绝原因；
4. 系统虚拟组合、槽位和批次；
5. 持仓感知SELL；
6. 群消息决策、合并、outbox和发送。

现有`StockTradeStrategyService`只能提供部分市场信号参考，不能继续让BUY、SELL、HOLD的异构分数通过无状态`max(score)`决定批次生命周期：

```text
无开放批次 → 只评估BUY
有开放批次 → 只评估该批次的HOLD/SELL
```

---

## 12. Shadow轨道与规则等级

至少并行运行：

- 5槽正式影子组合；
- 无限槽信号影子批次；
- 动态SELL影子建议；
- HIGH风险拒绝观察；
- 满仓和其他拒绝观察；
- 当前Java原始BUY对照。

规则状态：

```text
RESEARCH → SHADOW_CANDIDATE → PROVISIONAL → VALIDATED → RETIRED
```

当前：

| 模块 | 状态 |
|---|---|
| deep+range+strict rebound | `SHADOW_CANDIDATE` |
| 宽生命周期底座 | `SHADOW_CANDIDATE` |
| 动态自然SELL | `RESEARCH/SHADOW_CANDIDATE` |
| HIGH风险硬否决 | `RESEARCH` |
| 可选盈利换仓 | `SHADOW_CANDIDATE`，默认关闭 |
| 当前Java宽反弹BUY | `RETIRED_FROM_GROUP_PORTFOLIO` |
| 当前趋势回调BUY | `RESEARCH` |

升级为`PROVISIONAL`至少需要20个自然日真实shadow、冻结规则、新增月份方向未明显恶化、消息和状态机质量门禁通过。`VALIDATED`需要接近或超过完整一年、覆盖不同市场状态并获得成熟风格证据。

---

## 13. 收益、隔离回放与质量验收

### 13.1 组合收益

组合收益必须来自精确净值：

```text
equity = 可用现金 + 开放仓位按当前价格计算的扣费后市值
```

必须报告：区间收益、历史年化折算、MDD、交易数、胜率、平均/中位收益、槽位利用率、持有时长、满仓拒绝、期末仓位及股票/月/策略贡献。

当前冻结历史的可信范围：

```text
区间收益约7.3%～7.7%
历史年化折算约19.6%～20.5%
MDD约-0.6%～-0.7%
```

标记为`SHORT_HISTORY_ANNUALIZED_BACKTEST`，不得写成长期目标上限或收益承诺。未来研究应以提高风险调整后收益为目标，并与用户人工操作、动态SELL和新增BUY策略做冻结前向比较。

### 13.2 隔离回放边界

隔离回放属于研究验证，不得污染正式业务状态：

```text
只读加载生产bar/feature/月度状态
→ 生成内存runId与各轨道portfolioId
→ 复用正式纯领域规则和Policy
→ 内存中运行状态机与资金账本
→ 输出JSON摘要和CSV逐笔审计
```

冻结边界：

- `runId/portfolioId`只存在于回放进程和输出文件，不写正式业务表；
- 首期不新增回放业务表，不写本地或生产数据库；
- 输入数据库连接必须只读，禁止INSERT/UPDATE/DELETE/DDL；
- 可以复用正式的纯领域引擎、策略、Policy和计算器；
- 禁止调用会写DAO、发送消息、获取系统当前时间或更新全局水位的正式编排Service；
- 时间、价格、规则版本、月度状态和资金必须由回放上下文显式注入；
- 每条轨道独立状态，禁止轨道间共享持仓、槽位或冷却。

强制输出：

```text
<runId>-summary.json
<runId>-trades.csv
<runId>-rejections.csv
```

JSON至少包含输入范围、数据版本、全部规则版本、轨道、资金、收益、MDD、利用率、消息频率、错误和完成状态。CSV保存逐笔交易与拒绝事件，不强制保存每个bar流水；逐bar只需在内存计算净值，并输出压缩后的每日或15分钟净值曲线CSV用于MDD复核：

```text
<runId>-equity-curve.csv
```

失败或中断：

- 本次run标记`FAILED/INCOMPLETE`，已输出文件不得冒充完成结果；
- 不从中间状态续跑；
- 修复后使用相同输入和规则版本从头重跑；
- 输出采用临时文件，全部成功后原子重命名为正式文件名；
- 同一runId不得覆盖既有完成产物。

未来若回放规模需要持久化，可另行设计研究专用Schema，但不得复用正式event/batch/slot/notice表。

### 13.3 日报权益行情新鲜度

日报行情最大允许滞后冻结为：

```text
MAX_DAILY_EQUITY_PRICE_AGE = 30分钟
```

对每个开放正式仓位，实际用于估值的bar必须满足：

```text
bar.usable = true
AND bar.lastPrice > 0
AND bar.buildVersion = 当前冻结版本
AND bar.barEndTime <= summaryGeneratedAt
AND summaryGeneratedAt - bar.barEndTime <= 30分钟
```

边界规则：

```text
价格年龄 = 30分钟 → 可用
价格年龄 > 30分钟 → 行情数据不足
```

该阈值按bar结束时间而不是`barStartTime`、入库时间或任务处理时间计算。日报08:30执行时，正常情况下最新已稳定构建的bar可能是`[08:00,08:15)`，价格年龄15分钟；30分钟阈值还允许一个15分钟桶构建延迟或不可用，但不允许使用更早的长期陈旧行情。

不同股票使用的最新bar时间可以不同；组合`priceAsOf`必须取所有实际参与估值bar的最早`barEndTime`：

```text
priceAsOf = min(usedBar.barEndTime)
```

正式组合权益必须是全量可计算值。只要任一开放正式仓位没有满足上述新鲜度要求的实际行情：

```text
equityStatus = DATA_INSUFFICIENT
equity = null
```

禁止：

- 用投入成本代替缺失行情；
- 只计算现金部分并显示为组合权益；
- 用部分股票市值拼成看似完整的权益。

日报展示：

```text
- 当前组合权益：暂无法计算（行情数据不足）
- 缺失行情：TCC、MUN
- 可用现金及预留资金：$...
```

现金可以单独展示，但必须标记为`cashOnly`，不能命名为权益。数据结构至少包含：

```text
equityStatus: COMPLETE | DATA_INSUFFICIENT
equity: number | null
cashAndReserved: number
missingPriceStocks: [symbol...]
priceAsOf: timestamp | null
```

如果没有开放仓位，权益可以完整计算为全部现金，此时`equityStatus=COMPLETE`。缺失股票按`stocksId`确定性排序。日报其他买卖、已实现收益和影子统计仍正常显示。

### 13.4 质量门禁

```text
孤儿SELL=0
重复BUY=0
重复SELL=0
重复关闭=0
数据断层成交=0
未复位重入=0
同股多正式开放批次=0
槽位超卖=0
SELL静默丢弃=0
```

---

## 14. 原因码

BUY：

```text
BUY_DEEP_REVERSION
BUY_RANGE_LOWER
BUY_STRICT_REBOUND
STYLE_FIT_ALLOWED
ABSOLUTE_TREND_GUARD_PASSED
```

拒绝/观察：

```text
STYLE_NOT_FIT
STYLE_MISSING
STYLE_STALE
RISK_HIGH_SHADOW_REJECT
ABSOLUTE_TREND_GUARD_FAILED
FALLING_KNIFE_RISK
PERSISTENT_DECLINE
SAME_STOCK_OPEN
COOLDOWN_ACTIVE
SIGNAL_NOT_RESET
PORTFOLIO_FULL
DATA_NOT_CONTIGUOUS
ENTRY_DATA_STALE
ENTRY_PRICE_DEVIATION
```

SELL/HOLD原因码必须按下列语义冻结，不得互换：

```text
SELL_TARGET_REACHED
  = 扣费后netReturn >= +0.8%的首期固定目标退出

SELL_RANGE_RECOVERED
  = RANGE批次盈利且position30 >= 0.60的区间恢复退出

SELL_HARD_RISK
  = 扣费后netReturn <= -1.5%的首期硬性收益底线退出

SELL_STRUCTURAL_RISK
  = 未来结构风险状态机确认退出；必须由多项趋势/动量/低点恶化证据共同触发，首期固定退出引擎不得使用

SELL_MAX_HOLD
  = 持有达到14天的时间退出

SELL_DYNAMIC_STRENGTH
SELL_PROFIT_PROTECT
  = 动态SELL规则正式化后的原因，首期只作shadow

SELL_OPTIONAL_ROTATION
  = 可选换仓关闭，默认关闭

SELL_DATA_ADMIN_CLOSE
  = 数据/管理关闭，不得伪装为普通策略SELL

HOLD_NO_EXIT_TRIGGERED
  = 当前bar数据完整、批次可评估，但首期四种正式退出规则均未命中时的通用HOLD

HOLD_RECOVERY_IN_PROGRESS
HOLD_TREND_STILL_SUPPORTIVE
HOLD_NO_RISK_CONFIRMATION
  = 动态/结构风险状态机的特定解释；只有相应事实实际计算成立时才能使用，不作为首期固定规则的通用回退
```

首期固定退出映射唯一确定：

```text
CLOSED_TARGET → SELL_TARGET_REACHED
CLOSED_RANGE  → SELL_RANGE_RECOVERED
CLOSED_RISK   → SELL_HARD_RISK
CLOSED_TIME   → SELL_MAX_HOLD
未命中退出   → HOLD_NO_EXIT_TRIGGERED
```

若入场参考价、当前价格、时间或必要特征缺失，不能记为`HOLD_NO_EXIT_TRIGGERED`；应先进入数据不足/不可评估路径，例如`DATA_STALE`，避免把“无法判断”记录成“已判断且继续持有”。

---

## 15. 长期约束

- 任何影响交易结果的修改必须产生新规则版本和新shadow周期；
- 不得覆盖旧批次的风格、风险或规则版本；
- 不得把5槽限制应用到原始事件和无限槽影子账本；
- 不得将风格缺失默认安全；
- 不得用固定0.8%否定动态SELL的长期研究方向；
- 不得让换仓绑架自然SELL；
- 不得为追求更高年化牺牲无未来函数、留出独立性和生产可执行性。
