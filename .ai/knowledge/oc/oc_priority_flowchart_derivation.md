# OC 岗位权重 priority 的算法溯源与复现（V2·修订版）

## 1. 文档信息

- 文档类型：算法溯源结论 + 复现方法（非实施方案）
- 适用项目：Golden-Eye
- 设计日期：2026-11-14（V1）→ 2026-11-15（V2 修订）
- 维护人：Bai
- 数据来源：运行库 torn_setting_oc_slot（只读实测）+ 第三方站点 tornprobability.com 公开 API + Torn OC 2.0 官方流程图（Lock Stock / Stacking the Deck）
- 设计状态：**数值溯源已定论；上游算法口径已定量定性并可在本地复现绘图模型**
- 相关字段：torn_setting_oc_slot.priority（备注「权重占比」）、best_success（备注「大成功占比」）

> V1 结论「兜底位法 + 人工定档、不存在纯公式」**已被本轮证据推翻**，修订内容见第 8 节。
> 本轮结论：**库里的 priority = 第三方站点 tornprobability.com 的岗位权重，用最大余数法取整到合计 100 后抄入库；个别 OC 被人为改动。**

---

## 2. 字段事实（前置约束，V1 结论仍有效）

1. priority 是**人工字段**，代码侧没有推导逻辑：`src/main/resources/mapper/setting/TornSettingOcSlotMapper.xml:5` 注释「人工字段(pass_rate/priority/best_success)仅在首次创建时由Manager填默认值, 并发重复写入由JVM共享锁收敛」；INSERT 语句在同文件 :8-12。
2. 现有数值来自一次性 Liquibase 硬编码：`src/main/resources/db/changelog/0.3.0/oc.yaml:510-616`，changeSet id=change_slot_priority，逐行 UPDATE ... SET priority=...。同批提交 1d9c84b「perf(oc): 修改大锅饭系数，实装计算」（2025-11-14）。
3. 运行期新增 OC 默认全 0：`TornSettingOcSyncManager.java:45-60`（DEFAULT_SLOT_PRIORITY=0、DEFAULT_BEST_SUCCESS=0），写库在 :273-274。
4. 口径是**「每个 OC 内部各岗位的占比，合计 100」**：148 行实测中已配置 OC 的 sum(priority) 全为 100，例外只有 Mob Mentality=101、Break the Bank=101（取整误差）。因此改一个岗位必然牵动同 OC 其它岗位。
5. priority 与 best_success 是两个互不相关的分布（Spearman 平均仅 0.14），best_success 至今没有消费方（其算法已在 `oc_best_success_derivation.md` 溯源：= 对「最佳结局」达成概率的边际贡献占比，与 priority 不是同一口径），本文只讨论 priority。
6. 消费侧会把连续权重压成 5 档：`TornOcRecommendManager.java:283-295`（>=25→5、>=20→4、>=15→3、>=10→2、else 1）；`OcFlowRosterMatcher.java:125/130/256` 用 max(1,priority)。**权重差 1~2 点在消费侧几乎无感**。

---

## 3. 数值溯源：来自第三方站点的公开 API（决定性结论）

### 3.1 上游接口

| 接口 | 说明 |
| --- | --- |
| `GET https://tornprobability.com:3000/api/GetRoleWeights` | 返回 32 个 OC × 各岗位 → 浮点权重（合计恰好 100） |
| `GET /api/GetSupportedScenarios` | 32 个 scenario（名字带空格）+ 每个 OC 的岗位数 |
| `POST /api/CalculateSuccess` | body `{"scenario":"<带空格全名>","parameters":[0~100...]}` → `{successChance,failureChance,goodEnding1..n,badEnding1..n}` |
| `POST /api/SubmitCPR` | 玩家 CPR 数据上报（与本议题无关） |

除以上 4 个外其余路径全部 404；站点根与静态资源经 nginx 一律 403（带 Chrome UA、经公共代理同样 403）。

### 3.2 库内数值 = API 浮点权重的最大余数取整

按**岗位名**匹配（normalize：小写 + 去非字母数字 + 去尾部 #N），对 API 权重做 Hamilton（最大余数）取整到合计 100，与库内 priority 比对：

| 类别 | 个数 | 清单 |
| --- | --- | --- |
| **完全一致** | 14 | Ace in the Hole、Blast from the Past、Clinical Precision、Counter Offer、Guardian Ángels、Honey Trap、Leave No Trace、Manifest Cruelty、Market Forces、No Reserve、Smoke and Wing Mirrors、Sneaky Git Grab、**Stacking the Deck**、Stage Fright |
| 各岗位差 1~2 点 | 6 | Bidding War、Break the Bank、Crane Reaction、Gaslight the Way、Gone Fission、Snow Blind |
| 明显不同 | 12 | Lock Stock（API 40/18/18/12/12 → 库 38/23/21/9/9）、Hostile Takeover、Cash Me if You Can（54/28/18 → 50/22/28）、Window of Opportunity、Mob Mentality（34/27/18/21 → 34/26/18/23）、Dish It Out（37/25/15/9/14 → 5×20）、Best of the Lot / First Aid and Abet / Pet Project / Plucking the Lotus Petal / Thou Shalt Not Steal / Cleared for Takeoff（全 0，未配置） |
| API 无此 OC | 1 | Ship Happens |

> 注意：**必须按岗位名匹配**。API 的键顺序与库内 slot_code 字母序不同（例：Counter Offer API 序 Robber,Looter,Hacker,Picklock,Engineer = 35.95/7.00/12.13/16.54/28.39；库内字母序 Engineer 28/Hacker 12/Looter 7/Picklock 17/Robber 36），按位置比会得到一堆假不一致。

### 3.3 「多站点一致」的原因

所有能抓到的 Torn 用户脚本都从**同一个接口**取权重，因此结果必然一致，并非各自独立复算：

| 脚本 | 取数 |
| --- | --- |
| 547973 Torn OC Weights Under Roles | 唯一逻辑就是 GetRoleWeights |
| 573084 Covy OC Requirements | 同一 API（WEIGHTS_API_URL） |
| 585163 -Fortie- Faction: OC Intelligence | 同一 API + 本地缓存 |
| 583330 Torn OC Best-Fit Role Recommender | 同一 API |
| 577968 OC2.0 Helper v1.03 | 同一 API（第 20 行 const API_URL） |
| 522974 OC 2.0 Helper v5.4.0 | 同一 API + CalculateSuccess |

---

## 4. 上游的底层模型：流程图 DAG（已在本地 100% 复现）

### 4.1 复现规则

| 元素 | 语义 |
| --- | --- |
| 菱形节点 | 一次岗位判定，标注可执行该判定的岗位列表（多岗位即 flex 共享节点） |
| 绿箭头（向右） | 该判定成功 → 推进到下一列 |
| 红箭头（向下） | 该判定失败 → 本列向下坠一层；若失败直接落到 Bad End / UNKNOWN，则该节点是本列兜底位 |
| 终局 | Good End #k（成功，计入 successChance）/ Bad End #k（失败） |

三条关键建模规则（实测得出）：

1. **flex 共享节点的有效成功率 = 参与岗位成功率的算术平均**。判别实验（Stacking the Deck，参数序 [CatBurglar, Driver, Hacker, Imitator]，col1 第二节点标注 Cat Burglar / Impersonator）：BE1=(1-p0)(1-q)，测得 q=(p0+p3)/2，即该节点平均的是 **CatBurglar 与 Imitator**；
   - (0,100,100,100)→BE1=0.5；(0,100,100,0)→1；(0,0,0,100)→0.5；(0,100,0,0)→1；(50,100,100,0)→0.375；全 60→0.16，全部吻合。
2. **跨案件别名按岗位类别归并**：图上写 Impersonator 时，参数用的是 Imitator。
3. **末节点的失败可能落到 Good End**（Stacking the Deck 的 N5 失败→Good End #2、N12 失败→Good End #4），图上红箭头确实指向 Good End 框，不是画错。

### 4.2 校验结果（p 全 = 60%）

Lock Stock（21 节点、8 Bad End + 3 Good End）逐位吻合：

~~~
BAD1 0.06400  BAD2 0.05990  BAD3 0.12580  BAD4 0.03594  BAD5 0.04572
BAD6 0.04279  BAD7 0.04005  BAD8 0.05768
GOOD1 0.31543 GOOD2 0.12617 GOOD3 0.08652
successChance 0.52811 / failureChance 0.47189
~~~

Stacking the Deck（16 节点、5 Bad End + 4 Good End）逐位吻合：

~~~
SBE1 0.16000 SBE2 0.06666 SBE3 0.08309 SBE4 0.04064 SBE5 0.06980
SGE1 0.09145 SGE2 0.12193 SGE3 0.15704 SGE4 0.20939
successChance 0.57981
~~~

### 4.3 两张图的边表（可直接实现）

Lock Stock（岗位序：Assassin, Smuggler, Hacker, Muscle1, Muscle2；`ok` = 绿边目标，`bad` = 红边目标）：

~~~
N0  [Muscle1,Muscle2] ok N6   bad N8      N11 [Muscle1,Muscle2] ok N4  bad N14
N8  [Muscle1,Muscle2] ok N6   bad N16     N14 [Smuggler,M1,M2]   ok N4  bad BAD6
N16 [Assassin]        ok N6   bad BAD1    N4  [Smuggler]         ok N5  bad N12
N6  [Assassin]        ok N1   bad N9      N12 [Smuggler]         ok N5  bad N15
N9  [Assassin]        ok N1   bad N21     N15 [Smuggler,M1,M2]   ok N18 bad BAD7
N21 [Assassin]        ok N20  bad BAD2    N5  [Hacker]           ok GOOD1 bad N13
N1  [Assassin]        ok N2   bad N7      N13 [Hacker]           ok GOOD2 bad N18
N7  [Assassin]        ok N2   bad BAD3    N18 [Hacker,Smuggler]  ok GOOD3 bad BAD8
N20 [Assassin]        ok N2   bad BAD4    N2  [Hacker]           ok N3  bad N10
N10 [Hacker]          ok N3   bad N17     N17 [Hacker]           ok N3  bad BAD5
N3  [Muscle1,Muscle2] ok N4   bad N11
~~~

Stacking the Deck（岗位序：CatBurglar, Driver, Hacker, Imitator）：

~~~
N0  [CatBurglar]            ok N1   bad N8      N9  [Imitator] ok N10  bad N13
N8  [CatBurglar,Imitator]   ok N1   bad SBE1    N13 [Imitator] ok N10  bad SBE2
N1  [Driver]                ok N2   bad N6      N3  [Hacker]   ok N4   bad N10
N6  [Driver]                ok N2   bad N9      N10 [Hacker]   ok N11  bad N14
N2  [CatBurglar]            ok N3   bad N9      N14 [Imitator] ok N11  bad SBE3
N4  [Hacker]                ok N5   bad N7      N7  [Imitator] ok SGE2 bad SBE4
N11 [Imitator]              ok N12  bad N15     N15 [Hacker]   ok SGE4 bad SBE5
N5  [Imitator]              ok SGE1 bad SGE2    N12 [Hacker]   ok SGE3 bad SGE4
~~~

### 4.4 评估器算法（本地复现 S(p)）

~~~
1. 对每个节点 n：rate(n) = mean(参与者岗位的当前成功率)；未出现在参数列表的岗位按 1.0（100%）计
2. 解访问概率：visit[N0] = 1；对其余节点做 Jacobi 迭代（约 80 轮收敛）
   visit[n] = Σ_(前驱 q) visit[q] × ( ok边取 rate(q) ; bad边取 1-rate(q) )
3. S = Σ_(节点 n) visit[n] × P(该节点走向 Good End)
   P = rate(ok(n)) 若 ok(n) 是 Good End；= 1-rate(bad(n)) 若 bad(n) 是 Good End
~~~

---

## 5. 上游「权重」到底是什么口径（本轮定量定性）

在两张已验证的图上，对多种候选口径做归一化后与实际权重比（MAD = 平均绝对偏差，单位：百分点）：

| 口径 | Lock Stock | Stacking the Deck |
| --- | --- | --- |
| **偏导 ∂S/∂p（基线 = 库内 pass_rate）** | **0.93** | **2.50** |
| **摆幅 S(该岗位=100%) − S(该岗位=50%)，其余取基线** | **0.25** | **0.48** |
| 摆幅（其余取基线 60%） | 0.17 | 1.45 |
| 使用（出手）次数占比 | 4.47 | 10.94 |
| 节点数占比 | 7.08 | 6.39 |
| 兜底/终结归属占比 | 15.01 | 9.20 |
| Shapley 1/0.5、Banzhaf | 3.6~6.6 | 3.8~7.7 |
| 期望成功次数、Sobol 方差份额、路径计数 | 3~11 | 3~11 |

把**基线成功率当作自由参数**做拟合时，`∂S/∂p` 可以把两组实际权重压到残差≈0：

- Lock Stock：拟合基线 0.61/0.61/0.63/0.56/0.52 → MAD **0.022**
- Stacking the Deck：拟合基线 0.69/0.67/0.48/0.68 → MAD **0.003**
- 另一种形式 S(该岗位=100%)−S(全体=基线) 也几乎完全拟合（拟合基线均在 0.5~0.6 附近）

**结论：**

1. 上游权重 = **该岗位对整案成功率的边际贡献（敏感度）**，在站点内部的一套基线成功率上取值，再归一化到 100。
2. 它与「出手次数/节点数/兜底位归属/Shapley」都不吻合——V1 的兜底位法只是 Lock Stock 图上的**巧合结果**（每列末节点恰好也是边际贡献最大的节点）。
3. 站点的基线成功率是**逐岗位（甚至逐节点）不同**的，不是全等值，也未必等于库内 pass_rate；因此外部无法 100% 逐位复刻 32 个 OC 的权重，但公式形式可以确定。
4. 注意 ∂S/∂p 与摆幅都需要「其余岗位取基线」这个前提；用全 60%、全 50% 单一参考点都不能同时拟合两张图（LS 偏好 ~0.6，StD 偏好 ~0.2）。

---

## 6. 复现步骤（可执行口径）

~~~
priority_i = Hamilton_round( 100 × w_i / Σ w )
w_i = ∂S/∂p_i |_(p = 基线向量)   —— 或 ——   S(p_i=100%, 其余=基线) − S(p_i=50%, 其余=基线)
~~~

1. 用第 4.3 的边表建图（每个 OC 一张，共 32 张；未画的 OC 需从官方流程图补录）；
2. 基线向量取该 OC 的 pass_rate（库内已有：多数 60/65，个别 55/62/67/69），或统一 60% 作近似；
3. 按第 4.4 算 S，对每个岗位求偏导或摆幅，归一化；
4. Hamilton 取整到合计 100；
5. 需要体现图外因素时**用独立的 override 表**，不要直接改基线（否则重演现在「看不出算法」的情况）。

### 6.1 与库内现状的差异归因

| 现象 | 归因 |
| --- | --- |
| 14 个 OC 完全一致 | 抄的就是当前 API 值的取整结果 |
| 6 个差 1~2 点 | 抄的是 API 旧版本（站点权重会随其模型/基线更新而变） |
| Lock Stock / Hostile Takeover / Cash Me if You Can / Window of Opportunity / Mob Mentality 明显不同 | 人工按图改过（Lock Stock 最典型：Assassin 40→38、Hacker 18→23、Smuggler 18→21、Muscle 12→9） |
| Dish It Out 5×20 | 人工拍平 |
| 7 个 OC 全 0（+ Ship Happens 无 API 对应） | 从未配置 |

---

## 7. 对「自研权重算法」的直接启示

1. 我们完全可以自己算：**图 + 基线成功率 + 边际贡献** 三件套即可，不需要第三方站点。
2. 需要先补齐的基建：32 张流程图（目前只录了 Lock Stock、Stacking the Deck）与每个岗位的基线成功率。
3. 口径可自选，但建议与个人收益/风险绑定：例如把「边际贡献」换成「期望收益贡献 = ∂(期望声望/期望掉落)/∂p_i」，更贴合我们分岗激励的目的。
4. 消费侧的分档（>=25/20/15/10）建议与权重一起重定，否则细粒度权重会被抹平。
5. 7 个全 0 的 OC 必须先用新算法补值，否则 `OcFlowRosterMatcher` 的 max(1,priority) 会让它们永远垫底。

---

## 8. 相对 V1 的修订说明

| V1 结论 | V2 修订 |
| --- | --- |
|「priority 由兜底位法读图推导、数值靠人工微调」 | 错。真正的上游是 tornprobability.com 的 GetRoleWeights，库内是其取整抄录；兜底位法只是局部巧合 |
|「不存在能同时复现全部 OC 的纯公式」 | 仅对「特征线性组合」类公式成立；真正的公式是流程图上的边际贡献，拟合基线后可复现到 0.02 个点 |
|「兜底位决定排序」 | 兜底位是图结构的结果，决定排序的是边际贡献（含非兜底节点，如 Lock Stock 的 Hacker、Stacking the Deck 的 Imitator） |
| 3.1 读图约定 | 保留并扩充：绿=推进、红=下落、flex 取参与者平均、别名归并 |

---

## 附录 A 数据与工具

- 数据库：jdbc:postgresql://localhost:14321/golden-eye（user postgres），只读脚本 out-oc-flow/Q6.java（输出 oc|rank|slot|pass_rate|priority|best_success，148 行）；运行方式：
  `cd out-oc-flow; java -cp "C:\Users\Bai\.m2\repository\org\postgresql\postgresql\42.7.11\postgresql-42.7.11.jar" Q6.java`
- 上游接口快照：out-oc-flow/role_weights.json（32 个 OC 的浮点权重，4185 字节）
- 读图工具：out-oc-flow/Graph2.java（节点连通域）、Graph3.java（绿边贪心追踪）、Probe.java（红/绿像素归属判定）、Crop.java（裁剪目视）
- 本轮实验脚本：Node fetch 直连 `https://tornprobability.com:3000/api/CalculateSuccess`（POST 需用 Node 的 fetch，web_fetch 只能 GET；pwsh 不能联网）
- 全库 priority 向量（只读实测，按 OC 槽位顺序）：
  - Mob Mentality 34/26/18/23；Cash Me if You Can 28/50/22；Gaslight the Way 9/27/41/10/0/13；Market Forces 29/27/16/5/23；Smoke and Wing Mirrors 51/9/13/27；Snow Blind 48/36/8/8；Stage Fright 16/6/20/3/9/46；Counter Offer 28/12/7/17/36；Guardian Angels 42/31/27；Honey Trap 27/31/42；No Reserve 31/31/38；Bidding War 8/18/13/7/22/32；Dish It Out 20/20/20/20/20；Leave No Trace 37/34/29；Sneaky Git Grab 51/18/17/14；Blast from the Past 16/24/12/34/11/3；Window of Opportunity 15/24/26/21/14；Break the Bank 14/10/32/13/3/29；Clinical Precision 16/19/22/43；Stacking the Deck 23/3/26/48；Manifest Cruelty 14/16/24/46；Ace in the Hole 8/28/21/18/25；Gone Fission 25/25/18/17/15；Crane Reaction 41/17/16/10/8/8；Lock Stock 38/23/21/9/9；Hostile Takeover 18/11/16/13/19/23。
  - 全 0：First Aid and Abet、Pet Project、Best of the Lot、Thou Shalt Not Steal、Plucking the Lotus Petal、Cleared for Takeoff、Ship Happens（2026-10-05 已回填，见第 9 节）。

---

## 9. 全 0 OC 的回填（2026-10-05）

第 3.2 节把 7 个全 0 的 OC 归入「未配置」；2026-10-05 复核发现：**其中 6 个上游 API 早已有值**，只是库内从未配置过，按第 3.2 节的算法（API 浮点权重 → Hamilton 最大余数取整）即可补出，且每个 OC 合计恰好 100。真正无解的只有 Ship Happens（上游 `GetSupportedScenarios` / `GetRoleWeights` 均无此 OC）。

| OC | 上游权重（浮点原值） | 回填 priority（合计 100） |
| --- | --- | --- |
| Cleared for Takeoff | Imitator 24.939008 / Interrogator 25.183916 / Assassin 18.155804 / Pickpocket 12.032038 / Techie 11.316435 / Lookout 8.372799 | Imitator#1=25 · Interrogator#1=25 · Assassin#1=18 · Pickpocket#1=12 · Techie#1=11 · Lookout#1=9 |
| Best of the Lot | Muscle 43.676574 / Picklock 20.733684 / CarThief 19.532879 / Imitator 16.056863 | Muscle#1=44 · Picklock#1=21 · Car Thief#1=19 · Imitator#1=16 |
| First Aid and Abet | Pickpocket 43.228969 / Decoy 30.730727 / Picklock 26.040303 | Pickpocket#1=43 · Decoy#1=31 · Picklock#1=26 |
| Pet Project | Picklock 36.444387 / Muscle 32.628846 / Kidnapper 30.926767 | Picklock#1=36 · Muscle#1=33 · Kidnapper#1=31 |
| Plucking the Lotus Petal | Muscle 47.896881 / Robber2 23.680943 / Hustler 14.394494 / Robber1 14.027682 | Muscle#1=48 · Robber#2=24 · Hustler#1=14 · Robber#1=14 |
| Thou Shalt Not Steal | Picklock 49.746527 / Pickpocket 37.856826 / Thief 12.396647 | Picklock#1=50 · Pickpocket#1=38 · Thief#1=12 |

- 上游快照：2026-10-05 01:05:59（`GET https://tornprobability.com:3000/api/GetRoleWeights`，返回 32 个 OC）
- 产出 SQL：[out/oc-priority/priority_zero_oc_backfill.sql](../out/oc-priority/priority_zero_oc_backfill.sql)（23 条 UPDATE，带 `AND priority = 0` 守卫，幂等）
- **未动**：Ship Happens（无上游数据，保持 0）；Gaslight the Way.Looter#2（上游权重 -0.000356，取整本就是 0，属正常值，不是漏配）
- 口径备注：这批值是「按上游当期权重取整」，与第 3.2 节「库内 = API 取整」完全同口径，不含人工调档；日后上游更新，重跑取整即可（差异量级同 6.1 节的 1~2 点）。
