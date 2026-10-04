# `Stock分析` 输出文案定稿与实施工作单（一次性）

| 项 | 内容 |
|---|---|
| 文档类型 | **一次性工作单**：实施并验收完成后删除；长期规则已冻结在 `.ai/knowledge/stocks/vip_stock_private_analysis_upgrade_technical_design.md` §4.4.1，本文档删除不丢规则 |
| 定稿 | 2026-10-04 用户定稿：采纳评审稿 Q1~Q6 全部建议，另加三处修订 —— ① `RSI` 译为「相对强弱指标」；② 月度摘要**移除「（YYYY-MM 生效）」**；③ **用户可见文案保留 `Stock`，禁止改写为「股票」或「股」**（`股票` 会触发敏感词检测导致封号，2026-10-04 用户确认） |
| 补遗 | 评审稿的英文清单漏了 3 处理由文案里的 `Stock`；按第 ③ 条约束，这 3 处 `Stock` **保留不动**，只把风格口语名（`阴跌型` / `弱势`）统一为枚举中文名（`持续下行` / `弱势`）；`月度成熟度M0` 的 `M0` 编码仍需清零 |
| Review 状态 | 2026-10-04 第一轮 Review（commit `d212efb0`）：**不通过** —— P1×1（`Stock` 被改成「股」，与用户约束相反）、P2×2、P3×4，整改清单见 §0；整改后需复跑定向回归并重新截图 |
| 范围 | **仅展示文案与格式化**；不改评分、阈值、动作判定、门槛叠加、月度口径、α 与通知链路、数据库、表格列数 |

## 0. 复核结论与整改清单（2026-10-04 第一轮 Review）

**通过项（已复核）**：参考价 2 位小数（含新单测 `getBasePriceText_twoDecimalHalfUp`）、月度摘要中文化且**无生效月份**、门槛合并为一行 `本月买入门槛 +N（…）`、依据 `· ` 前缀（`Collectors.joining`）、`RSI`→`相对强弱指标`、`存在接…风险` 病句修复、告警去重（`风格编码 α 未评估`）、表头/标题/指令描述**正确保留 `Stock`**、`StockMonthlyStyleResolver` 未知码不泄漏 α 英文码；**定向回归 69 例全绿**（TradeStrategy 9 / StyleResolver 6 / InitService 9 / Calculator 25 / Scheduler 20，BUILD SUCCESS）。

### P1（阻断，必须整改）

| 编号 | 位置 | 现状 | 改为 |
|---|---|---|---|
| P1-1 | `StockTradeStrategyService.java:285` | `持续下行股已出现确认信号，允许小仓位参与` | `持续下行Stock已出现确认信号，允许小仓位参与` |
| P1-1 | `StockTradeStrategyService.java:432` | `持续下行股尚未出现1日反弹确认，容易长时间套牢` | `持续下行Stock尚未出现1日反弹确认，容易长时间套牢` |
| P1-1 | `StockTradeStrategyService.java:435` | `弱势股尚未出现反弹确认，建议等待` | `弱势Stock尚未出现反弹确认，建议等待` |

**根因：** 上一版工作单 §2.1 写了「理由文案中的 `Stock` 去掉或改「股」」，是**我的规格错误**；用户约束为「`股票` 会触发敏感词检测导致封号，保持原来的 `Stock` 描述」。长期规则已在设计文档 §4.4.1 第 1/7 条改正（**`Stock` 一律保留，禁止改「股票」或「股」**）。

### P2（不阻断，建议同批修）

| 编号 | 位置 | 问题 | 建议 |
|---|---|---|---|
| P2-1 | `StockTradeStrategyService.java:194/197` | 质量码 `INSUFFICIENT_HISTORY` / `HISTORY_NOT_CONSECUTIVE` 字面量在消费侧重复硬编码，来源是 `Stock15mFeatureCalculator.java:37/41` 的 private 常量 —— 生产者改码后展示会**静默回退** `数据未就绪`，无编译期保护 | 把两个常量提升为 `public static final` 并在消费侧引用（已验证 `service/stocks/rebuild → service/user` 无反向依赖，不成环） |
| P2-2 | `StockTradeStrategyServiceTest.java`（ASCII 守卫断言处） | `assertFalse(reasons.chars().anyMatch(...ASCII...))` 未白名单 `Stock`，与「`Stock` 必须保留」规则冲突：未来含 `Stock` 的理由会红灯，存在被误改回敏感词的风险 | 断言前剔除白名单：`reasons.replace("Stock", "")` 再判 ASCII，并注释说明白名单原因（设计 §4.4.1 第 9 条） |

### P3（记录，不阻断）

| 编号 | 位置 | 说明 |
|---|---|---|
| P3-1 | `StockMonthlyStyleResolver.codeBlockedReason` | 编码为 `null`/空串时渲染 `不可识别：null`；因 `ck_monthly_confirmed_complete` + 只读 `CONFIRMED` ⇒ 生产不可达。建议兜底 `<维度>编码缺失` |
| P3-2 | 门槛项名称 | 实现用短形式 `数据不足` / `数据不连续`，工作单原文为 `数据不足（历史不满30日）`。**接受实现**（合并行内嵌套括号可读性差），设计 §4.4.1 第 4 条已同步为短形式 |
| P3-3 | `StockTradeStrategyService.java:288` | `非强势股出现低位反弹确认信号`（历史遗留，非本次引入）；是否统一为 `非强势Stock` 由用户决定 |
| P3-4 | `StockTradeAdvice.java` | 文件末尾缺换行（历史遗留，非本次引入） |

### 整改验收（P1 完成后）

1. `mvn -o -B compile` 通过；
2. 定向回归 `StockTradeStrategyServiceTest`、`StockMonthlyStyleResolverTest` 等 5 个类全绿；
3. 私聊 `Stock分析` 截图：DECLINER/WEAK 类股票的理由行显示为 `持续下行Stock…` / `弱势Stock…`（**不得出现「股」**）；
4. 若采纳 P2-1/P2-2，附带常量引用与白名单断言改动说明。

### 生命周期

P1 整改 + 复核通过后删除本文档（规则已在设计文档 §4.4.1 长期化）。

## 1. 定稿文案

### 1.1 对照表

| 编码 | 定稿中文 | 来源 |
|---|---|---|
| `DECLINER` / `WEAK` / `NARROW` / `RANGING` / `STEADY` / `STRONG` | 持续下行 / 弱势 / 窄幅震荡 / 区间震荡 / 稳健 / 强势 | `StockStrategyFitEnum.chineseDisplay` |
| `M0_UNMATURE` / `M1_EARLY` / `M2_PROVISIONAL` / `M3_SEASONED` / `M4_MATURE` | 未成熟 / 早期 / 暂定 / 较成熟 / 成熟 | `StockMaturityEnum.chineseDisplay` |
| `NONE` / `MEDIUM` / `HIGH` | 暂无明显风险 / 中等风险 / 高风险 | `StockRiskLevelEnum.chineseDisplay` |
| `ALPHA_NOT_EVALUATED` | α 未评估风格 / α 未评估成熟度 / α 未评估风险 | 同上（按维度取） |
| `RSI` | 相对强弱指标 | 理由文案 |
| `INSUFFICIENT_HISTORY` / `HISTORY_NOT_CONSECUTIVE` | 数据不足（历史不满30日） / 数据不连续（30日内有缺口） | 门槛文案 |

风格中文直接由 `StockStrategyFitEnum.fromCode(personality.name()).getChineseDisplay()` 取（两枚举六类业务编码 1:1，无需给 `StockPersonalityEnum` 加字段）。

### 1.2 定稿成品（before → after）

```text
【现状 before】
2026-10-04 18:03 Stock 模型记录
股票   参考价       动作   评分   规则说明        依据
PTS    79.850000   买入   58    波段低位建仓    价格距离30日低点不足1%
                                                当前价格低于近30日常态价格
                                                RSI偏低，短线卖压释放
                                                月度：风格=STEADY 成熟度=M3_SEASONED 风险=NONE（2026-10 生效）
CNC    841.470000  观望   32    波段低位建仓    近14日跌幅超0.5%且接近历史低点，不建议裸买入
                                                月度：风格=DECLINER 成熟度=M3_SEASONED 风险=HIGH（2026-10 生效）
                                                月度风险HIGH：买入门槛+10
```

```text
【定稿 after】
2026-10-04 18:03 Stock模型记录
Stock  参考价    动作   评分   规则说明        依据
PTS    79.85    买入   58    波段低位建仓    · 价格距离30日低点不足1%
                                              · 当前价格低于近30日常态价格
                                              · 相对强弱指标偏低，短线卖压释放
                                              · 月度：风格=稳健 成熟度=较成熟 风险=暂无明显风险
CNC    841.47   观望   32    波段低位建仓    · 近14日跌幅超0.5%且接近历史低点，不建议裸买入
                                              · 月度：风格=持续下行 成熟度=较成熟 风险=高风险
                                              · 本月买入门槛 +10（风险高 +10）
```

```text
【告警 before → after】
before: ASS：月度风格不可用（月度风格不可用(ALPHA_NOT_EVALUATED)），已停止推荐
after:  ASS：月度风格不可用（风格编码 α 未评估），已停止推荐
```

注：信号理由为按真实模板拼装的示意行，实际命中由当日特征决定；股票、价格、风格/成熟度/风险取自 2026-10 生效月与 2026-10-04 17:45 最新桶真值。

## 2. 代码改动清单

### 2.1 `torn/service/user/StockTradeStrategyService.java`

| 行 | 现状 | 改为 |
|---|---|---|
| 126 | `holdAllSignal("月度成熟度M0：历史不足60天，不推荐")` | `holdAllSignal("月度成熟度不足（未成熟）：历史不足60天，不推荐")` |
| 149 | `monthlyReasons.add("特征未就绪" + qualityReason + "：买入门槛+10")` | 质量码转中文后再拼：`INSUFFICIENT_HISTORY` → `数据不足（历史不满30日）`；`HISTORY_NOT_CONSECUTIVE` → `数据不连续（30日内有缺口）`；null/未知 → `数据未就绪`；最终形如 `数据不足（历史不满30日）：买入门槛+10` |
| 151-154 | `"月度：风格=" + personality.name() + " 成熟度=" + maturity.name() + " 风险=" + riskLevel.name() + "（" + effectiveMonth + " 生效）"` | `"月度：风格=" + 中文风格 + " 成熟度=" + 中文成熟度 + " 风险=" + 中文风险`（**删除生效月份拼接**） |
| 156 | `使用 YYYY-MM 风格（YYYY-MM 未生成）` | **不变**（月度规范 §13.4 要求的沿用留痕） |
| 159 | `月度风险HIGH：买入门槛+10` | `月度风险高：买入门槛+10` |
| 146-163 | 三条门槛提示各自追加一行 | 合并为一行：`本月买入门槛 +N（数据不足 +10、成熟度早期 +10、风险高 +10）`（只列命中的项，`N` 为合计；无序用顿号连接） |
| 203 | `RSI偏低，短线卖压释放` | `相对强弱指标偏低，短线卖压释放` |
| 249 | `阴跌型Stock已出现确认信号，允许小仓位参与` | `持续下行Stock已出现确认信号，允许小仓位参与`（**保留 `Stock`**） |
| 386 | `"接近30日低点但仍在走弱，存在接" + personality.getDescription() + "风险"` | `"接近30日低点但仍在走弱，存在" + 中文风格 + "风险"`（去掉 description 拼接：原样渲染为「存在接阴跌型：禁止裸低点买入，仅允许强反弹确认风险」病句） |
| 396 | `阴跌型Stock尚未出现1日反弹确认，容易长时间套牢` | `持续下行Stock尚未出现1日反弹确认，容易长时间套牢`（**保留 `Stock`**） |
| 399 | `弱势Stock尚未出现反弹确认，建议等待` | `弱势Stock尚未出现反弹确认，建议等待`（风格名不变，**保留 `Stock`**） |

`StockPersonalityEnum.description` **保留**（内部参数说明），只是不再拼进用户文案；不做枚举字段删改。

### 2.2 `napcat/strategy/vip/VipStocksStrategyImpl.java`

| 行 | 现状 | 改为 |
|---|---|---|
| 40 | `"查看 Stock 模型分析结果（系统内部研究口径）"` | `"查看Stock模型分析结果（系统内部研究口径）"`（**保留 `Stock`**，只去多余空格） |
| 57-59 | `… + " Stock 模型记录"` | `… + " Stock模型记录"`（**保留 `Stock`**，只去多余空格） |
| 79 | 表头首列 `"Stock"` | **不变**（保留 `Stock`；仅确认列数仍为 6） |
| 86 | `analyze.basePrice().toString()` | `analyze.getBasePriceText()` |
| 90 | `String.join("\n", analyze.reasons())` | 每条理由加 `· ` 前缀后拼接：`analyze.reasons().stream().map(r -> "· " + r).collect(Collectors.joining("\n"))` |

### 2.3 `torn/model/torn/stocks/trade/StockTradeAdvice.java`（新增展示方法）

```java
public String getBasePriceText() {
    return basePrice == null ? "-" : basePrice.setScale(2, RoundingMode.HALF_UP).toPlainString();
}
```

（需 import `java.math.RoundingMode`；展示层格式化，不改 `basePrice` 本身与任何评分输入。）

### 2.4 `torn/service/user/StockMonthlyStyleResolver.java`（原因文案中文化 + 去重）

| 现状 | 改为 |
|---|---|
| `月度风格不可用(ALPHA_NOT_EVALUATED)` | `风格编码 α 未评估` |
| `月度风格不可用(XXX)` | `风格编码不可识别：XXX` |
| `月度成熟度不可用(XXX)` | `成熟度编码不可识别：XXX` |
| `月度风险等级不可用(XXX)` | `风险编码不可识别：XXX` |
| `月度风格连续N个月未生成(最近YYYY-MM)` | `连续 N 个月未生成（最近 YYYY-MM）` |
| `月度生效月份缺失` | 不变 |

### 2.5 不改事项

1. 评分、阈值、动作判定、门槛数值与叠加逻辑（只改文案与展示形态）；
2. 月度选月 / 沿用 / 停推口径与 `warnings` 触发条件；
3. α、通知、轮次、15m 链路、数据库与 Liquibase；
4. 表格列数与列顺序（6 列）；动作配色、依据限长、排序等增强未纳入本版。

## 3. 测试同步（`StockTradeStrategyServiceTest`）

| 行 | 现状断言 | 同步为 |
|---|---|---|
| 103 | `contains("月度风险HIGH：买入门槛+10")` | `contains("本月买入门槛")` 与 `contains("风险高 +10")` |
| 126 | `contains("特征未就绪")` | `contains("数据不足")` |
| 169 | `contains("月度成熟度M0")` | `contains("月度成熟度不足")` |
| 185 | `contains("月度：风格=RANGING")` | `contains("月度：风格=区间震荡")`，并补断言：不包含 `生效`、不包含 ASCII 字母 |
| 187 | `assertFalse(contains("月度风险HIGH"))` | `assertFalse(contains("月度风险高"))` |
| 新增（1 条即可） | — | `getBasePriceText()` 输出 2 位小数（如 `353.20`） |

`StockMonthlyStyleResolverTest` 若断言了 `blockedReason` 文本需同步（以编译或测试报错为准）。表格图片层无测试惯例，不新增渲染层测试。

## 4. 长期方案同步情况（已由我完成）

| 文档 | 同步内容 |
|---|---|
| 设计文档 §4.4.1（新增） | 私聊输出文案与格式化规则冻结（零英文 token、月度摘要不显示生效月份、参考价 2 位小数、门槛文案与合并形态、告警文案、依据 `· ` 前缀与顺序、`RSI`→相对强弱指标、表头/标题中文、表格 6 列不变、取舍说明） |
| 设计文档 §4.4 | 展示落地示例改为 `月度：风格=强势 成熟度=成熟 风险=高风险`（不显示生效月份）+ 指向 §4.4.1 |
| 设计文档 §6.3 | 观测项改为「已闭环（实测正常返回、各列齐全）」；验收文案同步为 §4.4.1 口径 |
| 设计文档 §9 | v1.0.6 变更记录 |
| 月度规范 `stock_personality_monthly_calibration.md` §13.4 | 沿用留痕示例保持有效（本次不动）；中文展示名以设计文档 §4.4.1 为准 |

## 5. 验收（开发侧）

1. `mvn -o -B compile` 通过；
2. 定向回归：`StockTradeStrategyServiceTest`、`StockMonthlyStyleResolverTest`、`StockMonthlyStateInitServiceTest`、`StockMonthlyStateCalculatorTest`、`VipStockAlertSchedulerTest` 全绿；
3. 静态复核：`src/main` 内用户可见文案不含 `Stock` / `RSI` / `M0` / `HIGH` / `INSUFFICIENT_HISTORY` / `ALPHA_NOT_EVALUATED` 等英文 token（`grep` 复核）；
4. 私聊实测 `Stock分析`：参考价 2 位小数、依据零英文、每条理由带 `· ` 前缀、月度行无「（YYYY-MM 生效）」；
5. 截图 before/after 各一张，交我复核。

## 6. 生命周期

1. 开发实施 + 定向回归 + 私聊实测；
2. 交我复核（§5 五项 + 截图）；
3. 复核通过后**删除本文档**（规则已在设计文档 §4.4.1 长期化）；
4. 后续文案调整直接改设计文档 §4.4.1，不再新建一次性文档。

## 7. 参考

- `.ai/knowledge/stocks/vip_stock_private_analysis_upgrade_technical_design.md` §4.4 / §4.4.1 / §6.3
- `.ai/knowledge/stocks/stock_personality_monthly_calibration.md` §13（消费口径与沿用留痕）
- 代码：`napcat/strategy/vip/VipStocksStrategyImpl.java`、`torn/service/user/StockTradeStrategyService.java`、`torn/service/user/StockMonthlyStyleResolver.java`、`torn/model/torn/stocks/trade/StockTradeAdvice.java`