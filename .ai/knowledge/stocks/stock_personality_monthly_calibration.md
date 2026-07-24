# Torn股票月度状态规范

## 1. 文档定位

- 文档类型：业务规则与开发依据
- 更新频率：每月一次
- 证据范围：向前滚动365天；历史不足时使用全部可用历史
- 适用功能：BUY策略资格、群批次、历史回放和风险观察

股票月度状态拆为三个独立概念：

```text
strategyFitPrior + maturity + riskLevel
```

它们都不是独立买卖信号，也不能替代BUY条件。

---

## 2. 时间口径

对目标月份M：

```text
evidenceEnd = M月开始前最后一个有效时点
evidenceStart = max(首条有效历史, evidenceEnd - 365天)
```

- 只使用evidenceEnd以前的数据；
- 整个月冻结；
- 历史不足365天时使用全部可用历史；
- 开仓批次固化当月版本；
- 次月变化只影响新候选；
- 禁止使用当前风格反套早期历史。

例如2026-08生效版本只能使用截至2026-07-31的数据；截至7月23日计算的状态只能视为8月候选预览，不能冒充7月正式版本。

---

## 3. 策略适配先验

六类`strategyFitPrior`：

| 风格 | 业务含义 | 主要策略倾向 |
|---|---|---|
| `DECLINER` | 中长期持续下移 | 禁止裸低位，只允许严格反弹确认 |
| `WEAK` | 趋势偏弱 | 禁止区间下沿，只允许严格反弹确认 |
| `NARROW` | 趋势平坦且价格带极窄 | deep/range，Z值修正 |
| `RANGING` | 方向不明显、区间可控 | range优先，允许deep |
| `STEADY` | 温和或混合趋势 | 低优先级deep，继续研究趋势策略 |
| `STRONG` | 长期趋势明显向上 | 不使用低位区间策略，继续研究趋势策略 |

分类顺序：

```text
DECLINER → WEAK → NARROW → RANGING → STRONG → STEADY
```

风险类别先于窄幅/横盘，防止将持续下移的窄带误判为安全区间。

六类风格只负责：

- 判断策略族是否适配；
- `NARROW`的少量参数修正；
- 候选优先级和解释。

禁止为六种风格复制六套策略。

---

## 4. 成熟度

| 状态 | 历史范围 | 业务含义 |
|---|---:|---|
| `M0_UNMATURE` | <60天或不足2个完整月 | 极早期，只记录临时判断 |
| `M1_EARLY` | 60～119天 | 早期先验 |
| `M2_PROVISIONAL` | 120～239天 | 可用于适配，不能视为成熟事实 |
| `M3_SEASONED` | 240～364天 | 接近完整年度 |
| `M4_MATURE` | >=365天 | 完整滚动一年 |

当前冻结历史最高只有M2。群消息和批次必须同时保存风格与成熟度，例如：

```text
RANGING / M2_PROVISIONAL
```

历史不足一年不等于停止生成风格，也不等于默认`STEADY`。

---

## 5. 风险等级

独立生成：

```text
NONE / MEDIUM / HIGH
```

风险等级回答是否存在持续下移、半山腰或继续刷新低点的风险。它不能用来放宽策略资格：

```text
未证明危险 ≠ 已证明适合deep/range
```

### 5.1 主风险证据

- 全窗口日级对数趋势及区间；
- 后半段和最近90日收益；
- 完整自然月均价序列；
- 负月比例和连续负月数量；
- 最大回撤；
- 近低点、创新低和深度Z独立事件；
- 数据覆盖率和最长缺口。

连续bar高度相关，事件计数必须按边沿或时间块去重。

### 5.2 使用方式

当前短历史回放中，直接将HIGH设为硬门禁使5槽历史年化由19.61%降至16.97%，且MDD未改善。因此首期：

- `strategyFitPrior`决定正式策略资格；
- `riskLevel`用于保存、解释、候选降权和shadow拒绝观察；
- HIGH暂不自动删除正式候选；
- 是否硬否决由独立`riskRuleVersion`控制；
- 完整年度或新增前向证据支持后再升级。

MEDIUM候选必须通过BUY时点绝对趋势保护；NONE仍须满足原策略条件。

---

## 6. BUY时点绝对趋势保护

月度风格不能替代当下保护。

### deep

- MA7/MA30不得过度恶化；
- 7日收益不得严重下跌；
- MEDIUM风险时要求1日或6小时至少出现稳定迹象。

### range

- MA7/MA30要求更严格；
- 7日收益不得持续下移；
- 不能把下降中的窄带当安全区间。

### strict rebound

继续使用：

```text
低位 + return1d>0 + Z1>=0.8 + 价格<=MA30×1.002
```

---

## 7. 指标要求

### 全量主证据

- 窗口首尾收益及仅供展示的年化折算；
- 日级对数线性趋势和置信区间；
- 全量高低价格带和最大回撤；
- 完整自然月均价；
- 负月比例和连续负月；
- 深度Z、近低点、创新低独立事件；
- 数据覆盖率和最长缺口。

### 辅助证据

- 后半段收益；
- 最近30/60/90日趋势；
- 最近完整月变化；
- 上一版本状态及人工覆盖原因。

短样本首尾年化不能作为单一硬阈值。33天样本中约0.7%的首尾变化即可被机械放大为约8%的年化趋势，必须结合日级趋势、完整月和成熟度。

---

## 8. 迟滞与人工覆盖

```text
普通/强势 → WEAK/DECLINER或HIGH：允许当月升级
风险解除：连续两次月度校正改善
NARROW ↔ RANGING：连续两次满足，或显著越过阈值
```

人工覆盖必须保存：

- 机器建议；
- 最终值；
- 覆盖原因和操作人；
- 完整指标快照。

历史快照不得静默覆盖。

---

## 9. 策略适配矩阵

| 风格 | deep | range | strict rebound | 趋势策略 |
|---|---|---|---|---|
| `DECLINER` | 禁止 | 禁止 | 允许 | 禁止 |
| `WEAK` | 禁止 | 禁止 | 允许 | 禁止 |
| `NARROW` | 允许，Z×0.6 | 允许，Z×0.6 | 观察 | 研究 |
| `RANGING` | 允许 | 优先 | 观察 | 默认关闭 |
| `STEADY` | 低优先级 | 禁止 | 观察 | 研究重点 |
| `STRONG` | 禁止 | 禁止 | 观察 | 研究重点 |

风险等级是附加事实，不能将不适配策略变成适配策略。

---

## 10. 历史回放

对每个月M：

```text
读取M月以前可见数据
→ 生成并冻结M月strategyFitPrior/maturity/riskLevel
→ 在M月回放BUY
```

必须同时报告：

- 正式组合；
- 无限槽允许批次；
- 风险拒绝观察；
- 被风格拒绝观察；
- 拒绝率、误杀收益和MFE/MAE；
- 5槽精确资金收益与MDD。

禁止根据验证或留出结果反改当月阈值。

---

## 11. 月度状态数据

至少包含：

```text
stocks_id
stocks_shortname
effective_month
strategy_fit_prior
maturity
risk_level
suggested_personality
previous_personality
manual_override
override_reason
metric_snapshot
personality_rule_version
risk_rule_version
evidence_start_time
evidence_end_time
calculated_at
confirmed_at
```

唯一键：`(stocks_id, effective_month)`。

系统批次至少固化：

```text
strategy_fit_prior
style_maturity
risk_level
personality_effective_month
personality_rule_version
risk_rule_version
evidence_start_time
evidence_end_time
```

---

## 12. 开发验收

- [ ] 每月最多使用向前365天，历史不足时使用全部可用历史；
- [ ] 按目标月份重建并冻结，无未来函数；
- [ ] 分别保存strategyFitPrior、maturity和riskLevel；
- [ ] 风险判断先于NARROW/RANGING；
- [ ] 风险等级未被用于放宽策略资格；
- [ ] NARROW正确修正Z值；
- [ ] DECLINER/WEAK只允许严格反弹确认；
- [ ] 风格缺失/过期未默认STEADY；
- [ ] HIGH硬否决由独立版本控制并先完成shadow；
- [ ] 人工覆盖可审计；
- [ ] 批次固化月度状态，次月不回写开放批次。
