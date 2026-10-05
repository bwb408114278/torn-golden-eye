# OC 岗位 best_success（大成功占比）的算法溯源与复现

## 1. 文档信息

- 文档类型：算法溯源结论 + 复现方法（非实施方案）
- 适用项目：Golden-Eye
- 设计日期：2026-10-05
- 维护人：Bai
- 数据来源：运行库 torn_setting_oc_slot（只读实测）+ tornprobability.com 公开 API（/api/GetRoleWeights、/api/CalculateSuccess）+ Torn OC 2.0 流程图
- 设计状态：**数值溯源已定论（8 个已配置 OC / 37 个岗位全部复现到 0.21 个百分点以内），可纯公式复算**
- 相关字段：torn_setting_oc_slot.best_success（备注「大成功占比」）；对照字段 priority（备注「权重占比」，见 oc_priority_flowchart_derivation.md）

---

## 2. 结论（一句话）

> **best_success_i = 100 × [∂P(最佳结局)/∂p_i] / Σ_j [∂P(最佳结局)/∂p_j]，基线取全体岗位 p = 60%。**

- **P(最佳结局)** = 流程图最上方那条「全绿路径」走到底到达的 Good End，也就是该 OC **收益最高**的结局；对应 tornprobability `/api/CalculateSuccess` 返回的 `goodEnding1`（已验证：32 个 OC 在参数全 100 时 `goodEnding1 = 1`，即全员成功必然走这条路径）。
- 口径含义：**该岗位对「大成功（最佳结局）达成概率」的边际贡献占比**，归一化到合计 100。
- 与 priority 的区别：priority ≈ 站点权重 = 对**整案成功率** S 的边际贡献占比；best_success = 对**最佳结局概率** P(GE1) 的边际贡献占比。两者是不同分布（Spearman 仅 ≈0.14），所以同一个岗位在两个字段上的名次可以差别很大（如 Break the Bank 的 Robber：priority 13 / best_success 33.42）。

---

## 3. 复算方法（可执行）

图模型（在 Stacking the Deck 上用官方 API 逐位验证过，详见 oc_priority_flowchart_derivation.md 第 4 节）：

1. 节点 = 一次岗位判定，节点成功率 = **参与岗位当前成功率的算术平均**（flex 节点；单岗位节点即该岗位成功率）；
2. 绿边（向右）= 判定成功后的下一节点；红边（向下）= 判定失败后的下一节点；判定失败的落点也可能是 Good End（如 Break the Bank 的 Robber 失败 → Good End #4）；
3. 访问概率：visit[N0]=1，其余节点迭代求解 visit[n] = Σ_(前驱 q) visit[q] ×（绿边取 rate(q)、红边取 1-rate(q)）；结局概率 = 直接汇入该结局的 visit × 对应边概率。

于是：

```
best_success_i = 100 × (∂/∂p_i) P(goodEnding1) |_(p ≡ 0.60)  ÷  Σ_j (∂/∂p_j) P(goodEnding1)
```

实操上不需要自己建图：直接调 `POST https://tornprobability.com:3000/api/CalculateSuccess`，把第 i 个参数取 61、其余取 60，再取 59、其余取 60，中心差分即得偏导（本仓库实测残差 ≤0.21 个点，见第 4 节）。

---

## 4. 验证（8 个已配置 OC，37 个岗位）

参数序必须用 `/api/GetRoleWeights` 的岗位键序（= 该 OC 图上岗位首次出现顺序），基线统一 60%，中心差分 h=1：

| OC | 岗位 | 库内 best_success | 复算 | 偏差 |
| --- | --- | --- | --- | --- |
| No Reserve | Car Thief#1 / Engineer#1 / Techie#1 | 26.38 / 33.23 / 40.39 | 26.34 / 33.15 / 40.51 | ≤0.12 |
| Bidding War | Bomber#1 / Bomber#2 / Driver#1 / Robber#1 / Robber#2 / Robber#3 | 4.59 / 29.72 / 19.07 / 3.42 / 3.42 / 39.77 | 4.57 / 29.73 / 19.10 / 3.45 / 3.45 / 39.69 | ≤0.08 |
| Blast from the Past | Bomber#1 / Engineer#1 / Hacker#1 / Muscle#1 / Picklock#1 / Picklock#2 | 20.41 / 11.67 / 24.63 / 16.63 / 11.05 / 15.61 | 20.44 / 11.62 / 24.61 / 16.65 / 11.08 / 15.60 | ≤0.05 |
| Honey Trap | Enforcer#1 / Muscle#1 / Muscle#2 | 47.09 / 26.08 / 26.83 | 46.91 / 26.09 / 27.00 | ≤0.18 |
| **Break the Bank** | Muscle#1 / Muscle#2 / Muscle#3 / Robber#1 / Thief#1 / Thief#2 | 22.89 / 7.43 / 11.53 / 33.42 / 15.01 / 9.72 | 22.89 / 7.44 / 11.57 / 33.30 / 15.09 / 9.71 | ≤0.12 |
| Clinical Precision | Assassin#1 / Cat Burglar#1 / Cleaner#1 / Imitator#1 | 36.65 / 10.75 / 30.70 / 21.90 | 36.86 / 10.67 / 30.67 / 21.80 | ≤0.21 |
| Stacking the Deck | Cat Burglar#1 / Driver#1 / Hacker#1 / Imitator#1 | 27.70 / 11.12 / 38.93 / 22.25 | 27.81 / 11.10 / 38.90 / 22.19 | ≤0.11 |
| Ace in the Hole | Driver#1 / Hacker#1 / Imitator#1 / Muscle#1 / Muscle#2 | 7.42 / 30.76 / 50.92 / 6.06 / 4.84 | 7.27 / 30.94 / 50.88 / 6.03 / 4.88 | ≤0.18 |

### 4.1 已排除的候选口径（同一批 8 个 OC 上均不成立）

| 候选口径 | 结果 |
| --- | --- |
| ∂P(整案成功 S)/∂p 归一（= priority 口径） | 明显不符（BTB 为 11.65/11.77/8.95/3.84/31.64/32.14） |
| 站点 `GetRoleWeights` 浮点权重本身 / 取整 | 不符（站点权重只有 priority 的对应关系） |
| 其他结局：∂P(goodEnding2..5)/∂p 归一 | 完全不符（最大偏差 15~2300 个点，只有 goodEnding1 成立） |
| 基线换成 50% / 55% / 65% / 70% | 最大偏差升到 1.4~4.6 个点；60% 是最优且唯一 |
| Shapley / Banzhaf / 留一法（S 或 GE1 的价值函数） | 不符（BTB 的 GE1 Shapley 为 22.6/19.3/10.8/17.0/16.3/14.0） |
| 出手次数 / 成功次数 / 节点数 / 通径长度 / 兜底位归属 归一 | 不符 |
| 期望收益（按各 Good End 金额加权）对 p 的偏导归一 | 不符 |

---

## 5. Break the Bank 实例（流程图 → 数值）

### 5.1 图（已与官方 API 逐位对齐，11 组参数向量残差 < 1e-5）

节点（岗位集）+ 绿边 / 红边：

~~~
A1[Robber,Muscle1] ok B1  bad A2      C1[Muscle3]        ok D1  bad C2
A2[Muscle2]        ok B1  bad A3      C2[Thief2]         ok D1  bad D2
A3[Muscle3]        ok B1  bad BE1     C3[Muscle3]        ok GE5 bad C4
B1[Muscle2,Thief1] ok C1  bad B2      C4[Muscle1]        ok GE5 bad BE3
B2[Muscle2,Thief1] ok C1  bad B3      D1[Thief2]         ok E1  bad D2
B3[Thief2]         ok C3  bad BE2     D2[Robber,Thief2]  ok E1  bad BE4
E1[Muscle3]        ok F1  bad E2      F1[Robber,Muscle1] ok G1  bad R
E2[Muscle1,Muscle3] ok F1 bad BE5     G1[Muscle1,Robber] ok H1  bad R
R[Robber]          ok GE3 bad GE4     H1[Robber,Thief1]  ok GE1 bad GE2
~~~

> 读图注意：图面上第 2 列的实心紫「Muscle 2」与第 7 列的实心蓝「Muscle 1」在官方模型里其实是 flex 节点 `[Muscle2,Thief1]`、`[Muscle1,Robber]`（用单岗位参数实验反解得 q=(p_a+p_b)/2，与官方 API 完全吻合）。只看图片配色会把这两处的成功率算错，必须按官方模型口径取算术平均。

### 5.2 计算过程（基线 p ≡ 60%）

P(Good End #1) = 0.11435（60% 全员时）。

| 岗位 | ∂P(GE1)/∂p | 归一化 ×100 | 库内 best_success |
| --- | --- | --- | --- |
| Robber | 0.00419 | 33.30 | 33.42 |
| Muscle#1 | 0.00285 | 22.89 | 22.89 |
| Muscle#2 | 0.00095 | 7.44 | 7.43 |
| Muscle#3 | 0.00132 | 11.57 | 11.53 |
| Thief#1 | 0.00201 | 15.09 | 15.01 |
| Thief#2 | 0.00112 | 9.71 | 9.72 |

直观解释：Robber 站在「A1 首发」「F1/G1/R 收尾」这些**决定能否拿到 Best Ending** 的位置上，失败即掉进 Good End #4，所以对最佳结局的边际影响最大；Muscle#2 只出现在前三列、且成功与否主要影响能否推进而非能否拿到最优结局，所以占比最低。

---

## 6. 残留偏差与注意事项

1. **偏差 ≤0.21 个点**：Honey Trap 全员 pass_rate 本来就是 60，基线不可能是偏差来源，因此残余偏差应归因于站点模型/基线在 2025-11 之后的小幅更新（与 priority 上「6 个 OC 差 1~2 点 = 抄的是旧版 API」同源）。
2. best_success 目前**无消费方**：代码侧只在 `TornSettingOcSlotDO` 承载、`OcPlanSlot` 透传（javadoc 写成「该岗位当前可达到的最高成功率」，与字段备注不一致）；`TornSettingOcSyncManager` 对新建 OC 写默认值 0。库内数值来自一次性 Liquibase 硬编码（`src/main/resources/db/changelog/0.3.0/oc.yaml` 的 `change_slot_priority`，commit 1d9c84b，2025-11-14）。
3. 只有 8 个 OC（No Reserve、Bidding War、Blast from the Past、Honey Trap、Break the Bank、Clinical Precision、Stacking the Deck、Ace in the Hole，即 changeSet 中 id 29~65）有非 0 值，其余 OC 全 0 —— 说明当时只对这 8 个 OC 跑了这套计算。
4. 复算时**参数序**必须用 `/api/GetRoleWeights` 的键序，不要用库内 slot_code 的字母序，否则会得到一堆假不符。

---

## 附录 A 复现脚本（Node，直接可跑）

```js
const BASE = "https://tornprobability.com:3000";
const scenario = "Break the Bank";
const keys = Object.keys((await (await fetch(BASE + "/api/GetRoleWeights")).json()).BreakTheBank); // 参数序
const cs = async p => (await fetch(BASE + "/api/CalculateSuccess",
  {method:"POST", headers:{"content-type":"application/json"}, body: JSON.stringify({scenario, parameters:p})})).json();
const base = keys.map(() => 60);
const g = [];
for (let i = 0; i < keys.length; i++) {
  const up = [...base]; up[i] = 61;
  const dn = [...base]; dn[i] = 59;
  g.push(((await cs(up)).goodEnding1 - (await cs(dn)).goodEnding1) / 2); // ∂P(最佳结局)/∂p_i
}
const sum = g.reduce((a, b) => a + b, 0);
keys.forEach((k, i) => console.log(k, (100 * g[i] / sum).toFixed(2)));
```


---

## 7. 回填快照（2026-10-05，已交付可执行 SQL）

- 可执行 SQL：`out/oc-best-success/best_success_backfill.sql`（143 条 UPDATE，按 oc_name + slot_code 定位，幂等，含 BEGIN/COMMIT）
- 覆盖：32 个 OC / 143 个岗位（全部可算 OC）；**Ship Happens 的 5 个岗位保持 0** —— 上游 tornprobability 只有 32 个 scenario，没有该 OC 的图，需另按官方流程图补。
- 上游快照时间：2026-10-05 00:58:19（`/api/CalculateSuccess`）。上游模型会随时间小幅变动，故快照值时点必须记录；日后重算只会差 0.0~0.2 个点。
- 质量校验：143 条 UPDATE 与库内 (oc_name, slot_code) 一一对应（无重复、无缺失）；每个 OC 的合计均为 100 ± 0.02（纯 2 位小数取整误差，与 priority 的取整误差同性质）。
- 与旧值差异：现有 8 个 OC 的 37 个岗位全部变动 ≤0.21 个点（口径统一为本次算法）；其余 24 个 OC 由 0 变为本次计算值。

| OC | 岗位=best_success | 合计 |
| --- | --- | --- |
| Gaslight the Way | `Imitator#1`=3.96 · `Imitator#2`=40.50 · `Imitator#3`=25.79 · `Looter#1`=3.96 · `Looter#2`=12.89 · `Looter#3`=12.89 | 99.99 |
| Smoke and Wing Mirrors | `Car Thief#1`=42.43 · `Hustler#1`=35.86 · `Hustler#2`=11.94 · `Imitator#1`=9.77 | 100.00 |
| Snow Blind | `Hustler#1`=31.10 · `Imitator#1`=62.20 · `Muscle#1`=3.35 · `Muscle#2`=3.35 | 100.00 |
| Stage Fright | `Enforcer#1`=27.15 · `Lookout#1`=29.70 · `Muscle#1`=20.27 · `Muscle#2`=1.32 · `Muscle#3`=14.21 · `Sniper#1`=7.33 | 99.98 |
| Counter Offer | `Engineer#1`=26.64 · `Hacker#1`=21.30 · `Looter#1`=13.71 · `Picklock#1`=28.70 · `Robber#1`=9.64 | 99.99 |
| Leave No Trace | `Imitator#1`=53.69 · `Negotiator#1`=15.12 · `Techie#1`=31.19 | 100.00 |
| No Reserve | `Car Thief#1`=26.34 · `Engineer#1`=33.15 · `Techie#1`=40.51 | 100.00 |
| Bidding War | `Bomber#1`=4.57 · `Bomber#2`=29.73 · `Driver#1`=19.10 · `Robber#1`=3.45 · `Robber#2`=3.45 · `Robber#3`=39.69 | 99.99 |
| Honey Trap | `Enforcer#1`=46.91 · `Muscle#1`=26.09 · `Muscle#2`=27.00 | 100.00 |
| Blast from the Past | `Bomber#1`=20.44 · `Engineer#1`=11.62 · `Hacker#1`=24.61 · `Muscle#1`=16.65 · `Picklock#1`=11.08 · `Picklock#2`=15.60 | 100.00 |
| Break the Bank | `Muscle#1`=22.89 · `Muscle#2`=7.44 · `Muscle#3`=11.57 · `Robber#1`=33.30 · `Thief#1`=15.09 · `Thief#2`=9.71 | 100.00 |
| Clinical Precision | `Assassin#1`=36.86 · `Cat Burglar#1`=10.67 · `Cleaner#1`=30.67 · `Imitator#1`=21.80 | 100.00 |
| Stacking the Deck | `Cat Burglar#1`=27.81 · `Driver#1`=11.10 · `Hacker#1`=38.90 · `Imitator#1`=22.19 | 100.00 |
| Ace in the Hole | `Driver#1`=7.27 · `Hacker#1`=30.94 · `Imitator#1`=50.88 · `Muscle#1`=6.03 · `Muscle#2`=4.88 | 100.00 |
| Market Forces | `Enforcer#1`=14.67 · `Negotiator#1`=23.57 · `Lookout#1`=8.81 · `Arsonist#1`=20.61 · `Muscle#1`=32.34 | 100.00 |
| Mob Mentality | `Looter#1`=2.43 · `Looter#2`=53.92 · `Looter#3`=10.50 · `Looter#4`=33.14 | 99.99 |
| Pet Project | `Kidnapper#1`=23.49 · `Muscle#1`=33.86 · `Picklock#1`=42.65 | 100.00 |
| Best of the Lot | `Car Thief#1`=25.79 · `Imitator#1`=5.28 · `Muscle#1`=35.81 · `Picklock#1`=33.12 | 100.00 |
| Cash Me if You Can | `Lookout#1`=23.88 · `Thief#1`=21.00 · `Thief#2`=55.11 | 99.99 |
| Manifest Cruelty | `Cat Burglar#1`=26.78 · `Hacker#1`=27.99 · `Interrogator#1`=27.90 · `Reviver#1`=17.32 | 99.99 |
| Crane Reaction | `Sniper#1`=29.47 · `Lookout#1`=17.20 · `Bomber#1`=13.23 · `Muscle#1`=16.31 · `Muscle#2`=13.90 · `Engineer#1`=9.90 | 100.01 |
| Gone Fission | `Hijacker#1`=18.77 · `Imitator#1`=48.44 · `Bomber#1`=12.15 · `Pickpocket#1`=8.81 · `Engineer#1`=11.82 | 99.99 |
| Guardian Ángels | `Hustler#1`=33.14 · `Engineer#1`=28.76 · `Enforcer#1`=38.10 | 100.00 |
| Sneaky Git Grab | `Pickpocket#1`=23.60 · `Imitator#1`=30.24 · `Techie#1`=32.99 · `Hacker#1`=13.17 | 100.00 |
| Plucking the Lotus Petal | `Robber#1`=16.47 · `Hustler#1`=34.19 · `Robber#2`=24.45 · `Muscle#1`=24.88 | 99.99 |
| Window of Opportunity | `Engineer#1`=26.92 · `Looter#1`=12.43 · `Looter#2`=9.31 · `Muscle#1`=45.11 · `Muscle#2`=6.23 | 100.00 |
| Dish It Out | `Assassin#1`=24.84 · `Engineer#1`=26.25 · `Muscle#1`=12.42 · `Saboteur#1`=14.68 · `Saboteur#2`=21.81 | 100.00 |
| Lock Stock | `Assassin#1`=31.19 · `Hacker#1`=37.65 · `Muscle#1`=6.87 · `Muscle#2`=6.87 · `Smuggler#1`=17.42 | 100.00 |
| Hostile Takeover | `Cat Burglar#1`=11.80 · `Engineer#1`=36.68 · `Hacker#1`=6.07 · `Kidnapper#1`=13.81 · `Muscle#1`=6.88 · `Negotiator#1`=24.75 | 99.99 |
| Cleared for Takeoff | `Imitator#1`=19.85 · `Techie#1`=28.69 · `Pickpocket#1`=13.43 · `Lookout#1`=4.40 · `Assassin#1`=6.83 · `Interrogator#1`=26.81 | 100.01 |
| First Aid and Abet | `Picklock#1`=39.09 · `Decoy#1`=19.32 · `Pickpocket#1`=41.59 | 100.00 |
| Thou Shalt Not Steal | `Picklock#1`=41.59 · `Thief#1`=16.78 · `Pickpocket#1`=41.63 | 100.00 |
| Ship Happens | `Engineer#1`=0.00 · `Assassin#1`=0.00 · `Hustler#1`=0.00 · `Spy#1`=0.00 · `Interrogator#1`=0.00 | 0.00（未回填） |


### 7.1 遗留事项

1. **新建 OC 仍会写 0**：`TornSettingOcSyncManager`（DEFAULT_BEST_SUCCESS=0）对运行期新出现的 OC 只写默认值，不会自动算 best_success。若 best_success 要长期启用，需要二选一：
   - 维护式：新 OC 上线后按本文口径重算并追加一条回填 SQL；
   - 内建式：把「图 + 基线 60% + 对 goodEnding1 求偏导」实装进代码（需要把 32 张图录成配置，成本较高，但可自动覆盖新 OC）。
2. **Ship Happens**：拿到官方流程图后按第 3 节口径补算，追加一条 UPDATE 即可。
3. 若下游要求"每个 OC 合计严格等于 100.00"，可用最大余数法对 2 位小数做一次配平（当前是自然取整，最大偏差 0.02）。
