# Torn 股票月度风格快照（2026-07）

## 1. 快照信息

- 快照月份：2026-07
- 证据范围：2026-01-26 ～ 2026-07-23
- 目标观察范围：滚动365天
- 历史状态：不足365天，使用全部可用历史
- 股票数量：35
- 数据规模：587,650根15分钟bar
- 用途：2026-08候选预览及人工校正输入，不作为2026-07正式生效版本
- 成熟度：`M2_PROVISIONAL`
- 状态：机器建议，未经人工确认，不代表生产配置

---

## 2. 分类方法

主证据：

- 全量首尾收益和年化趋势；
- 全量对数线性趋势；
- 全量价格带和最大回撤；
- 完整自然月均价变化；
- 负月比例和连续负月数量；
- 深度Z和近低点独立事件。

辅助证据：

- 全量后半段收益；
- 最近90日收益；
- 短期趋势是否显示修复或恶化。

判断顺序：

```text
DECLINER → WEAK → NARROW → RANGING → STRONG → STEADY
```

风险分类先于窄幅和横盘，避免把持续下移的价格带误判为安全区间。

---

## 3. 机器建议

### DECLINER（1）

- HRG

### WEAK（8）

- BAG
- IOU
- MUN
- SYS
- TCI
- THS
- TMI
- YAZ

### NARROW（3）

- CNC
- EVL
- TSB

### RANGING（12）

- CBD
- ELT
- LAG
- LOS
- LSC
- PRN
- TCC
- TCM
- TCP
- TCT
- WLT
- WSU

### STEADY（6）

- EWM
- IIL
- MCS
- MSG
- PTS
- TGP

### STRONG（5）

- ASS
- FHG
- GRN
- IST
- SYM

---

## 4. 重点风险

| 股票 | 建议 | 年化趋势 | 30日折算线性趋势 | 后半段收益 | 全量价格带 | 说明 |
|---|---|---:|---:|---:|---:|---|
| HRG | DECLINER | -5.97% | -0.68% | -4.11% | 6.70% | 持续下移，禁止裸低位 |
| SYS | WEAK | -7.85% | -0.95% | -1.78% | 8.31% | 价格区间整体下移 |
| TCI | WEAK | -7.32% | -0.73% | -2.40% | 7.01% | 价格区间整体下移 |
| YAZ | WEAK | -5.18% | -0.73% | -0.19% | 7.40% | 低价和极端Z不代表安全区间 |
| BAG | WEAK | -2.24% | -0.41% | -0.95% | 6.80% | 横盘标签存在下移风险 |
| IOU | WEAK | -2.82% | -0.35% | +1.97% | 6.10% | 后半段修复，全量仍偏弱 |
| MUN | WEAK | +0.02% | -0.24% | -0.25% | 4.84% | 全量接近平坦但方向偏弱 |
| THS | WEAK | -2.69% | -0.26% | -0.62% | 5.00% | 弱势证据持续 |
| TMI | WEAK | +0.40% | -0.32% | -0.23% | 4.18% | 窄带缓慢下移风险 |

这些股票不应因为“靠近30日低点”直接进入区间下沿或裸均值回归。

---

## 5. 强势候选

| 股票 | 建议 | 年化趋势 | 30日折算线性趋势 | 后半段收益 |
|---|---|---:|---:|---:|
| FHG | STRONG | +14.34% | +1.32% | +1.63% |
| SYM | STRONG | +13.81% | +1.01% | +4.81% |
| GRN | STRONG | +12.55% | +1.22% | +3.15% |
| ASS | STRONG | +8.91% | +0.87% | +1.18% |
| IST | STRONG | +8.43% | +0.75% | +3.20% |

`STRONG`不适合区间下沿策略，优先用于趋势回调、趋势延续和追踪止盈研究。

---

## 6. 人工复核项

| 股票 | 机器建议 | 复核原因 |
|---|---|---|
| LSC | RANGING | 全量趋势仅+4.44%，但后半段+6.91%，可能已进入近期强势 |
| ELT | RANGING | 全量趋势+3.74%，介于温和趋势和横盘之间 |
| CNC | NARROW | 全量带宽4.11%，但年化趋势-4.92%，需确认早期下跌影响 |
| TCM | RANGING | 全量接近平坦，但后半段-2.40%；可保守维持WEAK |
| WSU | RANGING | 全量-2.42%、后半段-2.27%，需确认筑底还是继续下移 |
| IOU | WEAK | 全量偏弱但后半段+1.97%，需确认修复是否稳定 |

人工覆盖必须保存最终分类和原因。

---

## 7. 与当前生产配置

机器建议与当前配置：

- 相同：8支；
- 不同：27支。

差异不能直接解释为当前配置错误。原因可能包括：

- 分类阈值不同；
- 当前配置包含人工经验；
- 本快照首次严格采用滚动一年/全部可用历史主口径；
- 部分股票处于风格切换边界。

禁止自动整串覆盖生产配置。

---

## 8. 审核用配置串

```text
ASS:STRONG,BAG:WEAK,CBD:RANGING,CNC:NARROW,ELT:RANGING,EVL:NARROW,EWM:STEADY,FHG:STRONG,GRN:STRONG,HRG:DECLINER,IIL:STEADY,IOU:WEAK,IST:STRONG,LAG:RANGING,LOS:RANGING,LSC:RANGING,MCS:STEADY,MSG:STEADY,MUN:WEAK,PRN:RANGING,PTS:STEADY,SYM:STRONG,SYS:WEAK,TCC:RANGING,TCI:WEAK,TCM:RANGING,TCP:RANGING,TCT:RANGING,TGP:STEADY,THS:WEAK,TMI:WEAK,TSB:NARROW,WLT:RANGING,WSU:RANGING,YAZ:WEAK
```

该配置串只用于人工审核。确认后才能更新 `sys_setting.STOCK_PERSONALITY` 并刷新缓存。
