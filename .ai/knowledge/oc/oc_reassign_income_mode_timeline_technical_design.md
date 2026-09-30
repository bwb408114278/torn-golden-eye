# OC大锅饭收益模式时间线技术方案（1.6.7）

## 1. 文档信息

- 文档类型：最终技术实施方案
- 适用项目：Golden-Eye
- 适用版本：1.6.7
- 设计日期：2026-09-30
- 维护人：Bai
- 设计状态：已通过业务确认，待工程师实施
- 实施状态：未实施
- 技术验收状态：待实施后Review

> 本文是"大锅饭收益模式按月切换"的唯一技术实施基线。开发人员只能按本文修改代码、测试与运维步骤。
> 本文修订 `oc_reassign_db_driven_technical_design.md` 中"收益模式=帮派行单值"的口径，冲突处以本文为准；需要同步修改的长期方案见第17章。

---

## 2. 需求目标

### 2.1 业务背景（真实需求）

SH 从 2026-10 起把 OC 大锅饭改为无工分（平分）模式，但**历史月份必须继续按工分（系数）口径展示**：成员用 `g#OC收益#用户ID#yyyy-MM` 查 9 月仍要看到"岗位系数/工时积分"两列，查 10 月及以后不显示这两列。因此收益模式必须能"按月分段、随时来回切换"。

### 2.2 已冻结口径

| # | 口径 | 内容 |
|---|---|---|
| 1 | 结算时间 | **沿用现状**：结算时按"结算发生时刻的当前月模式"。9 月未结算的 OC 放到 10 月结算即按平分口径，不追溯改用历史月模式 |
| 2 | 展示时间 | `OC收益` 表格是否展示系数列，按**查询月份**判定，不再用"用户当前帮派的单值模式"一刀切 |
| 3 | 生效边界 | 与财务一致：**自然月**，当月 1 日生效、月末结束；不允许月中生效，不做特殊化 |

### 2.3 交付前提（数据先行）

线上来不及"先改代码再切换"，因此 SH 的切换会**先改数据**：2026-10-01 手工把 `torn_setting_oc_reassign_faction.income_mode` 改为 `EQUAL` 并刷新缓存，SH 立即进入无工分状态。1.6.7 上线时 SH 已经是无工分版。

由此产生两条硬约束：

1. 迁移必须把"9 月及以前的真值=系数、10 月起=平分"**显式落成时间线段**，不能依赖"回落基线"表达历史；
2. **任何回滚/异常都不允许让 SH 退回有工分**：`faction.income_mode` 的语义改为"**当月生效口径的快照（回滚兜底）**"，由指令在同一事务同步维护，迁移不改写它。

### 2.4 能力目标

- 一个帮派可配置任意多个月份段（如 `2025-09 系数 → 2026-10 平分 → 2026-12 系数`），随时来回切换；
- 同一个月份重复设置＝**覆盖**（不提供删除）；
- 只允许设置**当月及未来月**，过去月只读；
- 转移成本最小：收益计算公式、工时口径、排行榜与汇总口径**零变更**。

---

## 3. 现状事实（实测）

| 事实 | 数据 |
|---|---|
| 大锅饭帮派行 | 6 行：PN(20465)/HP(2095)/CCRC(27902) = COEFFICIENT，NOV(16335)/BSU(11796) = EQUAL，SH(36134) = COEFFICIENT（人工切换后为 EQUAL） |
| 收益模式唯一写入口 | `OcReassignConfigService#openFaction`（`row.setIncomeMode(...)` 全项目仅此一处） |
| 模式读入口 | 4 处：`TornOcWorkingHourService#calculateWorkingHours`、`OcReassignAddValidator#validate`、`TornOcRecommendManager#calcReassignRecommendScore`、`OcBenefitQueryStrategyImpl#createDisplayConfig` |
| 6 帮派最早收益明细 | 全部为 **2025-09**（2025-09 ~ 2026-09 共 13 个月，无更早月份）|
| SH 9 月明细系数 | 5.00 ~ 25.83（非 1.00），确为系数口径 |
| 清单表生效时间 | SH 仅 `Cleared for Takeoff` 有 `effective_from = 2026-09-01`，其余为"始终" |
| 结算触发 | OC 同步带动：整点全帮刷新 + 每个待完成 OC 每分钟轮询；模式在**生成明细时**读取 |

---

## 4. 总体技术方案

1. 新建模式时间线段表 `torn_setting_oc_reassign_mode`（帮派 + 生效月 + 模式 + 同月唯一），模式解析口径从"帮派行单值"迁移为"**该月命中的最新段**"；
2. `TornSettingOcReassignManager` 扩展为模式时间线的唯一解析门面（新增缓存、按月的解析 API 与纯函数解析器）；
3. `OC大锅饭开启` 指令语义扩展为"**写入/覆盖一个月份段**"（开通新帮派＝同时插帮派行与首段），不新增指令常量；
4. `faction.income_mode` 降级为"当月口径快照（回滚兜底）"，在写段事务内同步；
5. `OC收益` 表格列版式改为按"查询月 + 明细行所属帮派集合"判定；结算、校验、推荐三处调用点保持既有签名（当月口径），**零改动**；
6. 收益计算公式、工时口径、排行榜、月度汇总、飞书链路全部不动。

---

## 5. 数据库设计

### 5.1 新表：torn_setting_oc_reassign_mode

帮派大锅饭收益模式时间线段，每帮派每月至多一行；段是稀疏的变更点，只记录"从哪个月起变成什么"。

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGINT | PK | 主键 ID |
| faction_id | BIGINT | NOT NULL | 帮派 ID |
| effective_month | CHAR(7) | NOT NULL | 生效月份，格式 `yyyy-MM`；与 `torn_faction_oc_income_summary.year_month` 同口径 |
| income_mode | VARCHAR(16) | NOT NULL | 收益模式：COEFFICIENT（系数）/ EQUAL（平分） |
| deleted | TINYINT | NOT NULL DEFAULT 0 | 删除标识 1 为已删除 |
| create_time | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | 更新时间 |

唯一约束：`uk_setting_oc_reassign_mode(faction_id, effective_month)`——"同月覆盖"语义需要数据库唯一性兜底。
不建额外索引：唯一约束以 faction_id 为前导列，已覆盖"按帮派取段"的查询路径。

### 5.2 派生规则（唯一口径，全部实现在 TornSettingOcReassignManager）

| 派生项 | 规则 |
|---|---|
| 模式时间线 | 帮派全部未删除段，按 `effective_month` 升序 |
| 指定月模式 | 取 `effective_month <= 目标月` 的最新一段的模式；**没有任何段命中时回落帮派行 `income_mode`**（该值无效时按 COEFFICIENT 兜底）|
| 当月模式 | 目标月取"当前自然月"，即结算与校验使用的口径 |
| 名单/排除规则/帮派集合/扫描起点 | 保持 1.6.2 规则不变，本次不涉及 |

`yyyy-MM` 字符串的字典序等于时间序，比较可安全使用；但解析只允许在门面内实现一次，禁止复制。

### 5.3 种子数据（随 1.6.7 迁移落库，共 7 行）

首段生效月统一取 **2025-09**（6 帮派最早收益明细月，覆盖全部历史月份）；SH 追加 2026-10 段承载"改无工分"这一既成事实。

| id | faction_id | 帮派 | effective_month | income_mode |
|---|---|---|---|---|
| 1 | 20465 | PN | 2025-09 | COEFFICIENT |
| 2 | 2095 | HP | 2025-09 | COEFFICIENT |
| 3 | 27902 | CCRC | 2025-09 | COEFFICIENT |
| 4 | 36134 | SH | 2025-09 | COEFFICIENT |
| 5 | 16335 | NOV | 2025-09 | EQUAL |
| 6 | 11796 | BSU | 2025-09 | EQUAL |
| 7 | 36134 | SH | 2026-10 | EQUAL |

**迁移不修改 `torn_setting_oc_reassign_faction`**：SH 行保留数据先行改成的 `EQUAL`，作为当月口径快照。

### 5.4 Liquibase 要求

- 新建 `src/main/resources/db/changelog/1.0.1-2.0.0/1.6.7/oc-reassign-mode.yaml`，两个 changeset：`create_table_oc_reassign_mode`、`seed_oc_reassign_mode`（author：Bai）；
- 在 `db.changelog-master.yaml` 末尾追加 `- include: file: db/changelog/1.0.1-2.0.0/1.6.7/oc-reassign-mode.yaml`；
- `createTable` 必须带表与全部字段 `remarks`（含主键、逻辑删除、审计时间），遵守 `.ai/knowledge/java_coding_style.md` 的 Liquibase 规范；
- 建表与种子的字段顺序、类型、默认值照 5.1/5.3 原样实现。

---

## 6. 包规划与新增文件

### 6.1 子包结论

新增文件全部沿用既有分层包，**不新建子包**：

| 包 | 现有文件数 | 新增 | 结论 |
|---|---|---|---|
| `repository/model/setting` | 15 | 1（ModeDO） | 平铺既定约定，不拆 |
| `repository/mapper/setting` | 15 | 1（ModeMapper） | 同上 |
| `repository/dao/setting` | 15 | 1（ModeDAO） | 同上 |
| `torn/model/faction/crime/income` | — | 1（模式段 record，与 `FactionOcExclusion` 同类派生值对象同放） | 不拆 |
| `torn/service/faction/oc/reassign` | 2 | 0（解析逻辑并入门面，不新增类） | 不拆 |
| `napcat/strategy/faction/crime/reassign` | 3 | 0 | 不拆 |

### 6.2 新增文件清单

1. `src/main/resources/db/changelog/1.0.1-2.0.0/1.6.7/oc-reassign-mode.yaml`（见 5.4）；
2. `repository/model/setting/TornSettingOcReassignModeDO.java`——模式段 DO（`id/factionId/effectiveMonth/incomeMode`，逻辑删除与审计时间继承 `BaseDO`）；
3. `repository/mapper/setting/TornSettingOcReassignModeMapper.java`——MP `BaseMapper`，无自定义 SQL、无 XML；
4. `repository/dao/setting/TornSettingOcReassignModeDAO.java`——`ServiceImpl`；
5. `torn/model/faction/crime/income/OcIncomeModeSegment.java`——`record OcIncomeModeSegment(String effectiveMonth, TornOcIncomeModeEnum mode)`，`effectiveMonth` 为 `null` 表示"无段时的基线兜底"。

---

## 7. 核心设计

### 7.1 TornSettingOcReassignManager（唯一解析门面）

新增缓存键：`CacheConstants.KEY_TORN_SETTING_OC_REASSIGN_MODE = "torn:setting:oc:reassign:mode"`；`warmUpCache` 追加 `getModeList()`；`refreshCache` 的 `@Caching(evict=...)` 追加该键。

公共 API：

```text
List<TornSettingOcReassignModeDO> getModeList()                          // @Cacheable 段列表
TornOcIncomeModeEnum getIncomeMode(long factionId)                       // 当月口径；保留签名，既有3处调用点零改动
TornOcIncomeModeEnum getIncomeMode(long factionId, YearMonth month)      // 指定月口径
List<OcIncomeModeSegment> getModeTimeline(long factionId)                // 名单回执渲染；无段时返回单条基线段
static TornOcIncomeModeEnum resolveIncomeMode(List<TornSettingOcReassignModeDO> modeList,
                                             String fallbackMode, YearMonth month)   // 纯函数
```

实现约束：

- 解析只允许一处实现：把段列表转成 `TreeMap<YearMonth, TornOcIncomeModeEnum>`（同月唯一由唯一约束保证），用 `floorEntry(month)` 命中；未命中回落 `fallbackMode`，`fallbackMode` 为空或不可解析时返回 `COEFFICIENT`；
- `resolveIncomeMode` 为静态纯函数，供配置服务在**事务内**用最新段列表同步基线，避免读到未驱逐的旧缓存；
- `getIncomeMode(long)` 直接委托 `getIncomeMode(factionId, YearMonth.now())`，不改既有语义；
- 本类仍是模式口径的唯一事实源，其他位置不得复制段比较与回落逻辑。

### 7.2 OcReassignConfigService（编排，事务边界）

`openFaction(long factionId, TornOcIncomeModeEnum mode, YearMonth effectiveMonth, long operatorId)`（`@Transactional`）：

1. 帮派存在于 `torn_setting_faction`，否则回执 `失败: 帮派不存在`；
2. `effectiveMonth` 不得早于当前自然月，否则回执 `失败: 生效月份不能早于当月`（过去月只读）；
3. 帮派行不存在 → 插入 `enabled = true` 的行，`income_mode` 取写入段后的当月口径（当月无段时为 COEFFICIENT）；已存在且 `enabled = false` → 回执 `失败: 该帮派大锅饭已停用`；已存在且启用 → 视为切换；
4. **同月覆盖**：按 `(factionId, effectiveMonth)` 查段，存在则更新 `income_mode`，不存在则插入；月份与模式都没变化时不写库，回执"未变化"；
5. **基线同步**：在本事务内查最新段列表，调用 `TornSettingOcReassignManager.resolveIncomeMode(..., 当前帮派行 income_mode, YearMonth.now())`，把结果写回帮派行 `income_mode`；
6. 注册事务提交后驱逐门面缓存（照 `registerCacheEvictAfterCommit` 既有模式，追加新模式缓存键）；
7. `log.info` 记录 factionId、effectiveMonth、mode、命中动作、操作人。

回执模型（嵌套实现，不单独建文件）：

```text
record OpenResult(boolean success, String failureReason, OpenAction action,
                  YearMonth effectiveMonth, TornOcIncomeModeEnum mode)
enum OpenAction { OPENED, MODE_SET, UNCHANGED }
```

`listFaction(factionId)`：`ListResult` 的 `TornOcIncomeModeEnum incomeMode` 替换为 `List<OcIncomeModeSegment> modeTimeline`；范围行部分不变。

### 7.3 OcReassignAddValidator（无改动）

系数完整性校验继续调用 `reassignManager.getIncomeMode(factionId)`（当月口径），语义与现状一致，**本类不修改**。

### 7.4 指令策略（复用 OC大锅饭开启）

`OcReassignOpenStrategyImpl`：

- 命令：`g#OC大锅饭开启#帮派ID#系数|平分(#yyyy-MM)`，仅超管（保持 `isNeedSa() = true`）；
- 参数：2 段或 3 段；月份缺省 = 当前自然月；月份用 `yyyy-MM` 解析，非法格式回执参数错误；
- 成功后不发起异步补算（模式切换不影响历史明细，仅影响后续结算与展示）。

`OcReassignListStrategyImpl`：回执行首行改渲染时间线；其余不变。
`OcReassignAddStrategyImpl`：无改动。

**BotCommands**：不新增常量（沿用 `OC_REASSIGN_OPEN`）。

### 7.5 指令回执文案（逐字模板，半角标点）

```text
# 首次开通
大锅饭已开启: PTA(9356) 2026-10起=系数

# 已开通后设置/覆盖某月模式
大锅饭模式已设置: SH(36134) 2026-10起=平分

# 同月同模式重复设置
该月模式未变化: SH(36134) 2026-10=平分

# 名单回执首行（时间线按月份升序，箭头分隔）
大锅饭名单: SH(36134) 模式: 2025-09起=系数 → 2026-10起=平分

# 无段帮派（未迁移或新帮派）
大锅饭名单: PTA(9356) 模式: 始终=系数

# 失败分支
失败: 帮派不存在
失败: 该帮派大锅饭已停用
失败: 生效月份不能早于当月
参数有误，正确格式：g#OC大锅饭开启#帮派ID#系数|平分(#yyyy-MM)
```

### 7.6 展示层（OcBenefitQueryStrategyImpl）

`TableDisplayConfig` 由私有 record 提为 **package-private 静态 record + 静态工厂**，判定规则收敛到工厂内一处：

```text
static TableDisplayConfig of(YearMonth month, List<TornFactionOcIncomeDO> incomeList,
                             long fallbackFactionId,
                             BiFunction<Long, YearMonth, TornOcIncomeModeEnum> modeLookup)
```

判定规则：

1. 取 `incomeList` 中去重的 `factionId` 集合；集合为空时取 `fallbackFactionId`（用户当前帮派）；
2. 对集合中每个帮派解析**查询月**的模式，**任意一个为 COEFFICIENT 就展示"岗位系数/工时积分"两列**（因此列数 9；否则 7）；
3. 普通收益表格块沿用同一 `totalColumns` 与合并跨度，不单独判定。

调用点：`buildDetailMsg` 内改为
`TableDisplayConfig.of(month, dataResult.getIncomeList(), user.getFactionId(), (fid, ym) -> reassignManager.getIncomeMode(fid, ym))`；
`createDisplayConfig(user)` 私有方法删除（避免两处判定）。

**结算 / 校验 / 推荐三处调用点不修改**：均使用 `getIncomeMode(factionId)`（当月口径），与第 2.2 节口径 1 一致。

---

## 8. 计划修改文件汇总

### 8.1 生产代码

新增：6.2 节全部 5 项。

修改：

1. `constants/torn/CacheConstants.java`（新增模式段缓存键）；
2. `constants/bot/BotCommands.java`（仅更新 `OC_REASSIGN_OPEN` 的注释说明，无新增常量）；
3. `torn/manager/setting/TornSettingOcReassignManager.java`（7.1）；
4. `torn/service/faction/oc/reassign/OcReassignConfigService.java`（7.2）；
5. `napcat/strategy/faction/crime/reassign/OcReassignOpenStrategyImpl.java`（7.4）；
6. `napcat/strategy/faction/crime/reassign/OcReassignListStrategyImpl.java`（7.4、7.5）；
7. `napcat/strategy/faction/crime/benefit/OcBenefitQueryStrategyImpl.java`（7.6）；
8. `db.changelog-master.yaml`（5.4）。

被修改的每个生产文件按规范更新文件头 `@version` 为 `1.6.7`，`@since` 保持原值。

### 8.2 测试代码

**不新增测试类**，只扩展现有 4 个类（见第 9 章）。

---

## 9. 测试方案（收敛）

同一规则一个主证据层级，不铺矩阵，不新增测试类。

### 9.1 TornSettingOcReassignManagerTest（扩展，纯单元 mock DAO）

新增模式解析用例：当月命中段；当月未命中未来段（不生效）；无段回落帮派行基线；跨年月份边界（`2026-12` → `2027-01`）；基线值不可解析时回落 COEFFICIENT。既有派生用例保持不动。

### 9.2 OcReassignConfigServiceTest（扩展，真实校验器）

| 用例 | 断言 |
|---|---|
| 首次开通并写入首段 | 帮派行与模式段同时落库；回执 `OPENED`；基线列 = 当月口径 |
| 已开通帮派追加未来段 | 只新增段；**基线列仍等于当月口径，而不是最新段的值** |
| 同月覆盖 | 同 `(factionId, effectiveMonth)` 只有一行且模式被更新 |
| 同月同模式重复设置 | 不写库；回执 `UNCHANGED` |
| 过去月拒绝 | 不落库；回执 `失败: 生效月份不能早于当月` |

### 9.3 OcReassignSeedEquivalenceTest（扩展，@SpringBootTest 只读）

硬编码断言 5.3 节 7 行模式段（帮派、月份、模式）与库内逐行一致；断言 9 月与 10 月对 SH 解析出的模式分别为 COEFFICIENT 与 EQUAL（历史与切换边界生命线）。

### 9.4 OcReassignStrategyTest（扩展，纯单元）

开启指令三参数解析（缺省当月、显式月份、非法月份拒绝）、回执文案逐字校验（7.5 三种成功分支 + 过去月失败）；名单回执时间线渲染。roleType/isNeedSa 既有断言保持。

### 9.5 OcBenefitQueryStrategyImplTest（扩展）

调用提纯后的 `TableDisplayConfig.of`（无反射、不查库）断言：SH 9 月 → 9 列；SH 10 月 → 7 列；同月明细含两个帮派且任一为 COEFFICIENT → 9 列；明细为空时按用户当前帮派的查询月口径。

---

## 10. 代码质量要求

1. 遵守 `.ai/knowledge/java_coding_style.md`：方法复杂度 ≤ 15、参数 ≤ 7、单文件 ≤ 800 行；Liquibase 表与字段必须写 `remarks`；
2. 段解析与回落逻辑只在门面实现一次，配置服务与展示层都不得复制比较逻辑；
3. 回执拼装不得重复：成功/失败分支由 `OpenResult` 单一出口渲染；
4. 新增 DO/record/公共方法写完整 Javadoc；注释写业务事实（如"SH 自 2026-10 起改为无工分"），不写"因为方案要求"；
5. 不引入新依赖、不做无关格式化；换行符 CRLF，提交前对变更文件执行 unix2dos；
6. 不用异常控制校验流程（服务返回结果 record）。

---

## 11. 实施验证命令

```text
JAVA_HOME="C:\Program Files\Java\jdk-21" mvn.cmd compile -q -DskipTests
JAVA_HOME="C:\Program Files\Java\jdk-21" mvn.cmd test -Dtest="TornSettingOcReassignManagerTest,OcReassignConfigServiceTest,OcReassignSeedEquivalenceTest,OcReassignStrategyTest,OcBenefitQueryStrategyImplTest"
JAVA_HOME="C:\Program Files\Java\jdk-21" mvn.cmd test -Dtest="TornOcIncomeServiceTest,TornOcBatchIncomeServiceTest,OcBenefitRankStrategyImplTest"
```

第二条为本次聚焦证据；第三条为收益读路径直接回归。发布前按项目门禁执行全量测试。

---

## 12. 上线步骤

1. **数据先行（2026-10-01 00:00，服务器本地时区）**：执行 13.1 的基线切换 SQL，随后超管在群里执行 `g#刷新缓存`；此刻起 SH 结算即为无工分，历史月份表格暂时显示为 7 列（已知中间态，1.6.7 上线后恢复 9 列）；
2. 部署 1.6.7：Liquibase 建表并写入 5.3 的 7 行模式段；
3. **上线前只读检查**（13.2）：确认 6 帮派基线列等于当月口径；不一致时按"基线同步"语义先手工修正（SH/NOV/BSU=EQUAL，PN/HP/CCRC=COEFFICIENT）再上线；
4. 验收：执行第 13 章 SQL，并让 SH 成员分别查询 9 月与 10 月收益确认列数；
5. **日常切换**：一律走 `g#OC大锅饭开启#帮派ID#系数|平分(#yyyy-MM)`；应急改库改为"往模式表插/改一行 + 刷新缓存"，`faction.income_mode` 不再是有效切换入口；
6. **回滚**：直接回滚应用包即可。基线列始终等于当月口径，SH 不会退回有工分（若回滚发生在"预置未来段刚生效、且此后未执行过任何指令"的窗口内，先执行 13.3 的基线校对再回滚）。

---

## 13. 只读/运维 SQL

### 13.1 数据先行：SH 基线切换（2026-10-01 执行）

```sql
UPDATE torn_setting_oc_reassign_faction
   SET income_mode = 'EQUAL', update_time = now()
 WHERE faction_id = 36134 AND deleted = 0;
-- 期望影响 1 行；执行后刷新缓存
```

### 13.2 上线前基线检查

```sql
SELECT faction_id, income_mode FROM torn_setting_oc_reassign_faction WHERE deleted = 0 ORDER BY faction_id;
-- 期望: SH/NOV/BSU=EQUAL, PN/HP/CCRC=COEFFICIENT
```

### 13.3 模式段时间线验收

```sql
SELECT faction_id, effective_month, income_mode
  FROM torn_setting_oc_reassign_mode WHERE deleted = 0
 ORDER BY faction_id, effective_month;
-- 期望 7 行，与 5.3 表格逐行一致；SH 两行(2025-09 系数 / 2026-10 平分)
```

### 13.4 切换生效验收（2026-10 之后）

```sql
SELECT coefficient, count(*) FROM torn_faction_oc_income
 WHERE faction_id = 36134 AND oc_executed_time >= '2026-10-01'
 GROUP BY coefficient ORDER BY coefficient;
-- 期望: 只有 1.00；若出现非 1.00 行，说明该链在写段/刷新缓存前已结算，需整链删行重算（人工预案）
```

### 13.5 回滚前基线校对（可选）

```sql
-- 把基线列刷成当月口径：以 SH 为例
UPDATE torn_setting_oc_reassign_faction SET income_mode = 'EQUAL', update_time = now()
 WHERE faction_id = 36134 AND deleted = 0;
```

---

## 14. 风险与处理

| 风险 | 处理 |
|---|---|
| 回滚应用包导致 SH 退回有工分 | 基线列语义=当月口径快照，写段事务内同步；迁移不改写该列（2.3、7.2）|
| 预置未来段生效后基线列滞后（A 方案的已知边界）| 影响仅限"生效后未发任何指令"窗口内回滚；上线步骤 6 给出校对动作 |
| 同月重复设置产生两行，解析结果不确定 | 唯一约束 `(faction_id, effective_month)` + 服务层先查后写（覆盖） |
| 9 月历史展示被误判为无工分 | 首段 2025-09 覆盖全部历史月份；9.3 断言 9 月解析为 COEFFICIENT |
| 误改过去月段导致历史展示漂移 | 服务拒绝早于当月的生效月（过去月只读） |
| 跨月链在 9 月表里出现 1.00 行 | 口径 1 的必然结果（结算按结算时刻模式），展示仍按查询月；不改口径 |
| 新模式未读缓存导致结算用错口径 | 模式段挂同一 `DataCacheManager`，写后提交即驱逐；手工改库必须刷新缓存 |

---

## 15. 明确不做事项

- 不提供模式段的删除/停用能力（纠错统一走"同月覆盖"）；
- 不允许设置早于当前自然月的段；
- 不给 `faction.income_mode` 增加跨月自动同步任务（同步只发生在指令事务内）；
- 不修改收益计算公式、工时口径、排行榜与月度汇总口径；
- 不修改 `torn_setting_oc_reassign_oc`（清单/生效时间）与添加指令的校验链；
- 不新增指令常量，不触碰 1.7.0 的指令检索与手册图片化范围；
- 不迁移或回填历史 `torn_faction_oc_income` 明细。

---

## 16. 长期技术方案同步修改清单

| 文档 | 位置 | 修改 |
|---|---|---|
| `oc_reassign_db_driven_technical_design.md` | 文档信息 | 增加一行：收益模式相关小节（2.1/2.2/5.4/7.1/7.2/7.4/7.5/8.3/16）已被 1.6.7 方案修订，冲突处以《OC大锅饭收益模式时间线技术方案（1.6.7）》为准 |
| 同上 | 2.1 | "帮派级收益模式可配置"→"收益模式按自然月分段可配置（时间线）" |
| 同上 | 2.2 / 7.4 | `OC大锅饭开启` 增加可选生效月参数；重复执行语义由"幂等拒绝"改为"设置/覆盖该月段" |
| 同上 | 5.4 收益模式行 | 规则改为"取 `effective_month <= 目标月` 的最新段；无段回落帮派行 `income_mode`" |
| 同上 | 6.2 第 8 项 | 枚举存储值改为大写 `COEFFICIENT`/`EQUAL`（与实现一致） |
| 同上 | 7.1 API 列表 | 增补 `getIncomeMode(factionId, month)`、`getModeTimeline(factionId)`、`resolveIncomeMode(...)` |
| 同上 | 7.2 / 7.5 | 开启编排改为"写段 + 同步基线列"；回执文案以本文 7.5 为准 |
| 同上 | 8.3 / 9.1 | `OcBenefitQueryStrategyImpl` 的展示判定改为按查询月与行帮派；新增文件与修改文件清单以本文第 8 章为准 |
| 同上 | 16 | "不引入数据库唯一约束"限定为"不在既有两张表上引入"；新增的模式表按本文 5.1 建同月唯一约束 |
| `oc_reassign_range_technical_design.md` | 文档头 | 增加一句：收益模式口径以《OC大锅饭收益模式时间线技术方案（1.6.7）》为准 |
| `file_location.md` | oc 知识库目录树 | 增加本文档路径一行 |

---

## 17. Review清单

### P0：口径与切换正确性

- [ ] 7 行模式段与 5.3 逐行一致；SH 9 月解析为 COEFFICIENT、10 月解析为 EQUAL；
- [ ] 写段事务内同步基线列，且同步值是**当月口径**（预置未来段时不得写成未来段的值）；
- [ ] 迁移不改写 `torn_setting_oc_reassign_faction`（SH 行保持数据先行写入的 EQUAL）；
- [ ] 同月覆盖仅一行（唯一约束 + 服务先查后写）；过去月被拒绝。

### P1：调用链与最小改动

- [ ] 结算/校验/推荐三处调用点保持 `getIncomeMode(factionId)`（当月口径），无行为变化；
- [ ] 展示判定收敛在 `TableDisplayConfig.of` 一处，原 `createDisplayConfig` 已删除；
- [ ] 段解析逻辑只在门面一份，无复制；
- [ ] 名单回执渲染时间线；开启回执按 7.5 逐字实现（半角标点）；
- [ ] 缓存键新增并纳入 `refreshCache` 与 `warmUpCache`。

### P2：质量

- [ ] 不新增测试类，测试范围限于第 9 章；无同规则重复矩阵；
- [ ] Javadoc、中文 `@DisplayName`、CRLF、`@version=1.6.7`；
- [ ] 编译、聚焦测试、读路径回归真实通过；`git diff` 与第 8 章清单一致；
- [ ] 第 16 章长期方案修改已落地。

---

## 18. 最终验收标准

1. 实现与本文表结构、种子、派生规则、指令行为、回执文案逐项一致；
2. 第 13 章验收 SQL 在生产/验收库全部符合预期；SH 9 月收益表 9 列、10 月 7 列；
3. 结算口径零漂移：10 月起 SH 新结算明细 `coefficient = 1.00`，9 月及以前明细与切换前完全一致；
4. 回滚应用包不改变 SH 的无工分状态（基线列=当月口径）；
5. 第 17 章无未闭环 P0/P1；AI 技术专家 Review 通过并更新本文实施状态与验收记录。
