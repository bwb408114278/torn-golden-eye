# Torn股票月度状态规范

## 1. 文档定位

- 文档类型：业务规则与开发依据
- 更新频率：每月一次
- 证据范围：向前滚动365天；历史不足时使用全部可用历史
- 适用功能：BUY策略资格、群批次、历史回放和风险观察
- 风格规则版本：`PERSONALITY_RULE_V1`
- 风险规则版本：`RISK_RULE_V1_SHADOW`

股票月度状态拆为三个独立概念：

```text
strategyFitPrior + maturity + riskLevel
```

它们不是独立买卖信号。`strategyFitPrior`用于策略适配；`maturity`表达历史长度；`riskLevel`首期只用于解释、降权和shadow观察，不自动成为正式BUY硬否决。

---

## 2. 月份与证据时点

对目标生效月份`M`：

```text
evidenceEnd = M月开始前最后一个可用15分钟bar时间
evidenceStart = max(股票首个可用bar时间, evidenceEnd - 365天)
```

要求：

- 只使用`[evidenceStart, evidenceEnd]`；
- 整个M月冻结；
- 月内不重复分类；
- 次月变化只影响新候选；
- 开放批次固化开仓月份状态；
- 月中计算只能作为下月候选草稿。

月度计算时点：

```text
每月1日00:00后，数据补算到上月最后一个已结束可用bar后生成DRAFT
```

如果上月末数据尚未补齐，保持DRAFT，不得确认不完整状态。

---

## 3. 输入指标完整公式

所有价格使用可用15分钟bar的`lastPrice`。为降低连续bar自相关，趋势回归使用每日最后一个可用bar价格`dailyClose[d]`。

### 3.1 历史长度

```text
evidenceDays = duration(evidenceStart, evidenceEnd) / 1天
```

不是bar数量折算。完整自然月数量只作审计，不参与成熟度边界。

### 3.2 首尾收益与展示年化

```text
fullReturn = lastPrice / firstPrice - 1
annualizedDisplay = (lastPrice / firstPrice)^(365 / evidenceDays) - 1
```

`annualizedDisplay`仅用于分类规则V1和展示，短历史必须结合其他指标，禁止单独解释为长期收益。

### 3.3 日级对数趋势

对每日序号`x=0..N-1`与`y=ln(dailyClose)`做普通最小二乘：

```text
slope = cov(x,y) / var(x)
trend30 = exp(slope × 30) - 1
```

拟合残差：

```text
residual = y - (intercept + slope × x)
slopeSE = stddevSample(residual) / sqrt(sum((x-mean(x))²))
trend30Low  = exp((slope - 1.645×slopeSE) × 30) - 1
trend30High = exp((slope + 1.645×slopeSE) × 30) - 1
```

要求至少10个`dailyClose`；不足时月度状态不可确认。

### 3.4 后半段与最近季度

```text
secondHalfReturn = lastPrice / priceAtFloor(N/2) - 1
quarterAnchor = evidenceEnd - 90天
lastQuarterReturn = lastPrice / latestPriceAtOrBefore(quarterAnchor) - 1
```

历史不足90天时，`lastQuarterReturn = fullReturn`，并在快照标记`quarterWindowTruncated=true`。

### 3.5 全窗口价格带与最大回撤

```text
fullBand = maxPrice / minPrice - 1
runningPeak[t] = max(price[0..t])
maxDrawdown = min(price[t] / runningPeak[t] - 1)
```

### 3.6 完整自然月均价

只使用在证据窗口内完整覆盖月初至月末的自然月。月均价：

```text
monthMean[m] = arithmeticMean(all usable 15m lastPrice in month m)
monthChange[m] = monthMean[m] / monthMean[m-1] - 1
```

```text
negativeMonthRatio = count(monthChange < 0) / count(monthChange)
negativeMonthStreak = 从最后一个monthChange向前连续<0的数量
```

若没有月变化：

```text
negativeMonthRatio = null
negativeMonthStreak = 0
```

### 3.7 数据完整性

月度确认最低要求：

```text
usableBarCoverage >= 95%
AND maxMissingBucketGap <= 2小时
AND dailyCloseCount >= 10
```

其中：

```text
usableBarCoverage = usableBarCount / expected15mBucketCount
```

未达到时：

```text
stateStatus = DRAFT
strategyFitPrior = null
riskLevel = null
reason = MONTHLY_EVIDENCE_INCOMPLETE
```

不得默认`STEADY/NONE`。

---

## 4. 成熟度完整规则

成熟度只按`evidenceDays`计算：

```text
if evidenceDays < 60       → M0_UNMATURE
else if evidenceDays < 120 → M1_EARLY
else if evidenceDays < 240 → M2_PROVISIONAL
else if evidenceDays < 365 → M3_SEASONED
else                       → M4_MATURE
```

当前代码使用1天/7天/30天bar数的实现与业务规则冲突，必须废弃。bar数只用于数据覆盖率，不用于成熟度分类。

---

## 5. 机器原始风格 `rawPersonality`

按以下顺序首次命中即返回：

```text
DECLINER → WEAK → NARROW → RANGING → STRONG → STEADY
```

### 5.1 DECLINER

```text
(
  annualizedDisplay <= -8%
  AND trend30 <= -0.6%
  AND secondHalfReturn <= -1%
)
OR
(
  negativeMonthStreak >= 3
  AND lastQuarterReturn <= -1.5%
)
```

### 5.2 WEAK

仅在未命中DECLINER时判断：

```text
(
  annualizedDisplay <= -2.5%
  AND trend30 <= -0.25%
)
OR
(
  negativeMonthRatio >= 60%
  AND secondHalfReturn < 0
)
OR
(
  secondHalfReturn <= -2.5%
  AND trend30 < 0
)
```

### 5.3 NARROW

仅在未命中风险类时判断：

```text
fullBand <= 4.5%
AND abs(annualizedDisplay) <= 5%
AND abs(trend30) <= 0.4%
```

### 5.4 RANGING

```text
fullBand <= 10%
AND abs(annualizedDisplay) <= 7%
AND abs(trend30) <= 0.6%
```

### 5.5 STRONG

```text
annualizedDisplay >= 8%
AND trend30 >= 0.6%
AND secondHalfReturn >= 0
```

### 5.6 STEADY

未命中以上任何分类时：

```text
rawPersonality = STEADY
```

`STEADY`是完整证据下的剩余类别，不是缺数据回退类别。

---

## 6. suggestedPersonality、previousPersonality与迟滞

### 6.1 previousPersonality

读取同一股票、生效月份早于M的最近一条`CONFIRMED`月度状态：

```text
previousState = max(effectiveMonth < M AND stateStatus=CONFIRMED)
previousPersonality = previousState.strategyFitPrior
```

首个确认月份无上一条时为`null`。不得读取DRAFT、RETIRED或当前`sys_setting`代替。

### 6.2 suggestedPersonality

`suggestedPersonality`是机器应用迟滞后的建议，不是未经处理的`rawPersonality`。

立即生效的变化：

```text
rawPersonality in {DECLINER, WEAK}
OR previousPersonality is null
OR rawPersonality = previousPersonality
→ suggestedPersonality = rawPersonality
```

`STRONG`与`STEADY`之间按当月原始结果直接变化，不要求两月确认，因为它们不共同控制range门禁。

### 6.3 NARROW ↔ RANGING迟滞

只针对这两个相邻安全风格：

```text
previous=NARROW AND raw=RANGING
previous=RANGING AND raw=NARROW
```

普通越界要求连续两个生效月份的`rawPersonality`都为目标值：

```text
previousMonth.rawPersonality = target
AND currentMonth.rawPersonality = target
→ suggestedPersonality = target
else suggestedPersonality = previousPersonality
```

“连续两次”的计算时点是连续两个自然月月初草稿计算，不是月内重复运行两次。

显著越界可当月切换：

```text
NARROW → RANGING:
  fullBand > 5.5%
  OR abs(annualizedDisplay) > 6%
  OR abs(trend30) > 0.5%

RANGING → NARROW:
  fullBand <= 3.5%
  AND abs(annualizedDisplay) <= 4%
  AND abs(trend30) <= 0.3%
```

### 6.4 从风险/强势类别恢复

从`DECLINER/WEAK`恢复到`NARROW/RANGING/STEADY/STRONG`，或从`STRONG`切换到低位适配类别，要求连续两个生效月份原始结果均为目标类别；否则保留previousPersonality。风险升级仍当月立即生效。

---

## 7. 风险等级完整公式

风险等级与风格分类独立。先计算投票事实。

### 7.1 HIGH票

```text
H1 = trend30High < -0.3%
H2 = secondHalfReturn <= -1.5% AND trend30 < 0
H3 = lastQuarterReturn <= -2% AND trend30 < 0
H4 = negativeMonthStreak >= 3
highVotes = count(H1..H4 = true)
```

### 7.2 MEDIUM票

```text
M1 = trend30 < -0.3%
M2 = secondHalfReturn < -0.8%
M3 = lastQuarterReturn < -1.2%
M4 = negativeMonthRatio != null AND negativeMonthRatio >= 60%
M5 = negativeMonthStreak >= 2
M6 = maxDrawdown <= -4%
mediumVotes = count(M1..M6 = true)
```

### 7.3 rawRiskLevel

```text
if highVotes >= 2
   OR (trend30High < -0.6% AND lastQuarterReturn < 0)
→ HIGH
else if mediumVotes >= 2
→ MEDIUM
else
→ NONE
```

### 7.4 effective riskLevel迟滞

读取上一确认月份`previousRiskLevel`：

```text
rawRiskLevel = HIGH
→ HIGH立即生效

rawRiskLevel = MEDIUM:
  previousRiskLevel = HIGH → 保持HIGH
  其他 → MEDIUM

rawRiskLevel = NONE:
  previousRiskLevel in {HIGH, MEDIUM}
  → 只有连续两个生效月份rawRiskLevel均为NONE才降为NONE
  否则保留previousRiskLevel
```

若上一确认月份为HIGH、当月raw为MEDIUM，保持HIGH；只有连续两个raw NONE才能完全解除。首月无previous时`riskLevel=rawRiskLevel`。

首期`riskLevel`只记录、展示和进入观察，不改变`strategyFitPrior`，也不自动删除正式BUY。

---

## 8. 人工覆盖和最终优先级

### 8.1 字段语义

```text
rawPersonality            = 机器原始六分类
suggestedPersonality      = 机器应用迟滞后的建议
strategyFitPrior          = 最终确认并供BUY适配使用的风格
manualOverride            = strategyFitPrior是否被人工改写
```

### 8.2 优先级

```text
manualOverride = false
→ strategyFitPrior = suggestedPersonality

manualOverride = true
→ strategyFitPrior = 人工指定finalPersonality
```

人工覆盖不修改`suggestedPersonality`，必须保留机器建议用于审计。`overrideReason`必填且非空；人工最终风格必须是六类之一。

### 8.3 风险覆盖

首期不允许人工通过修改`strategyFitPrior`清除机器`riskLevel`。如需人工调整风险，必须有独立`riskOverride`及原因字段；当前Schema未提供时，风险保持机器值。

---

## 9. DRAFT与确认

### 9.1 草稿生成

系统每月自动计算并生成DRAFT。DRAFT必须包含：

- 全部指标快照；
- raw/suggested/final风格；
- maturity；
- raw/effective风险；
- previous状态；
- 规则版本；
- 证据起止时间；
- 数据完整性。

### 9.2 confirmDraftStates语义

首期同时支持人工确认和系统确认，但必须是两个明确入口：

```text
人工确认：confirmDraftStates(effectiveMonth, confirmedBy)
系统确认：autoConfirmDraftStates(effectiveMonth)
```

人工入口：

- `confirmedBy`必须由调用方传入真实操作者标识；
- 禁止为空、空白或固定`SYSTEM`；
- 支持确认人工覆盖后的草稿。

系统入口：

```text
confirmedBy = SYSTEM
```

只允许在以下条件全部满足时自动确认：

- 数据完整性通过；
- `manualOverride=false`；
- 所有必填指标与状态非空；
- 规则版本等于当前冻结版本；
- 不存在待人工复核标记；
- 已生成previous状态和迟滞结果。

若任一条件不满足，保持DRAFT。禁止一个无参方法把全部DRAFT无条件确认。

---

## 10. 策略适配矩阵

| strategyFitPrior | deep | range | strict rebound | 趋势策略 |
|---|---|---|---|---|
| `DECLINER` | 禁止 | 禁止 | 允许 | 禁止 |
| `WEAK` | 禁止 | 禁止 | 允许 | 禁止 |
| `NARROW` | 允许，Z×0.6 | 允许，Z×0.6 | 观察 | 研究 |
| `RANGING` | 允许 | 优先 | 观察 | 默认关闭 |
| `STEADY` | 低优先级 | 禁止 | 观察 | 研究 |
| `STRONG` | 禁止 | 禁止 | 观察 | 研究 |

风险等级不能把不适配策略变成适配策略。

---

## 11. 月度状态持久化

至少保存：

```text
stocksId, stocksShortname, effectiveMonth
strategyFitPrior, maturity, riskLevel
suggestedPersonality, previousPersonality
manualOverride, overrideReason
metricSnapshot
personalityRuleVersion, riskRuleVersion
evidenceStartTime, evidenceEndTime
stateStatus, calculatedAt, confirmedAt, confirmedBy
```

`metricSnapshot`至少包含：

```text
rawPersonality, rawRiskLevel
annualizedDisplay, trend30, trend30Low, trend30High
secondHalfReturn, lastQuarterReturn
fullBand, maxDrawdown
negativeMonthRatio, negativeMonthStreak
highVotes及明细, mediumVotes及明细
usableBarCoverage, maxMissingBucketGap
evidenceDays, completeMonthCount
quarterWindowTruncated
hysteresisReason
```

唯一键：`(stocks_id, effective_month)`。

---

## 12. 开发验收

- [ ] evidenceEnd严格截止于生效月以前；
- [ ] 成熟度按60/120/240/365天，不按1/7/30天bar数；
- [ ] 六类风格按首次命中顺序计算；
- [ ] 风险投票和迟滞公式一致；
- [ ] NARROW/RANGING连续两次按连续自然月计算；
- [ ] previous只读取最近CONFIRMED月份；
- [ ] suggestedPersonality保留机器建议；
- [ ] 人工覆盖只覆盖最终strategyFitPrior；
- [ ] 人工confirmedBy由调用方传入；
- [ ] SYSTEM仅用于满足条件的自动确认；
- [ ] 不完整草稿不能确认；
- [ ] 风格缺失/过期不得默认STEADY；
- [ ] 开放批次固化当月状态，次月不回写。
