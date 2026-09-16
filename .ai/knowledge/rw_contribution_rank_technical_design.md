# RW真赛贡献榜技术方案

## 元信息

- 文档类型：功能技术方案（开发主依据+验收Review标准）
- 适用版本：1.6.4
- 最后更新：2026.09.16
- 维护人：Bai
- 状态：已定稿待实施
- 风险等级：L2（加列+新表DDL向后兼容、API字段接入、新指令；无资金/权限变更）

---

## 1. 背景与目标

统计最近RW真赛成员贡献。当前存在两个数据缺口：

1. `torn_faction_rw` 无分数字段——`/faction/rankedwars` 响应中 `target/winner/双方score` 已在 `TornFactionRwFactionVO`/`TornFactionRwVO` 解析但从未落库（`chain` 经用户比对真实数据确认无意义，明确丢弃）；
2. RW战神榜名次从不持久化，只存在于出图瞬间。

本方案补齐比分落库，并在战争结束时机自动结算名次落库，历史数据由一次性回填指令补齐。

## 2. 业务口径（唯一权威定义）

- **数据源**：`torn_faction_rw` 中本帮派 `end_time` 非空且 `opponent_score > 3000` 的已登记真赛，按 `end_time` 倒序取前3场。进行中、未登记、对手得分≤3000、得分为null的场次一律不入选。合格场次不足3场时全量入选（1~2场）；0场不渲染表格（见§6.6边界处理）。
- **名次口径**：与 `g#RW战神` 完全一致——`queryActiveTimeWindows`（3分钟滚动窗口、双方≥100次出手）+ `queryPlayerAttackStatByWindows` 聚合，按 `damage_score` 降序的行号。已用真实数据验证：48522场 NoZuoNoDie[3312605] = 第9名。
- **每场得分** = (101 − rank) × 系数。rank=1 时 100 分；上榜人数≤帮派成员数≤100，基础分恒≥1。
- **系数（对手得分 s）**：3000 < s < 6000 → 0.5；否则 1.0 + 0.1 × floor((s − 6000) / 6000)。验证：6000→1.0，12000→1.1，15000→1.1，18000→1.2，22625→1.2，3752→0.5，16126→1.1。
- **总分** = 3场得分之和，BigDecimal scale=1，HALF_UP。总分倒序；并列按参加场次数多者优先、再按用户ID升序。

## 3. 总体数据流

```
真赛登记(写target_score)
  → spider每2分钟采集
  → ended分支(写end_time + 4个终值字段 + 触发名次结算)
  → 指令查询(取3场 → join结算表 → 算分 → 渲染表格图片)

历史数据：SA一次性回填指令（API回填比分 + 回放结算名次）
```

## 4. 数据库设计

Liquibase 新建 `src/main/resources/db/changelog/1.0.1-2.0.0/1.6.4/rw.yaml`，`db.changelog-master.yaml` 末尾追加 include，author=Bai，表和全部字段带 remarks。

### 4.1 changeSet `alter_table_torn_faction_rw_add_war_result`

`addColumn` ×5，均可空，null=未结束/未知：

| 列 | 类型 | remarks |
|---|---|---|
| `target_score` | INT | 战争目标分数（登记时写入） |
| `winner_faction_id` | BIGINT | 胜方帮派ID |
| `faction_score` | INT | 我方最终战争分 |
| `opponent_score` | INT | 对手最终战争分 |
| `opponent_short_name` | VARCHAR(32) | 对手帮派简称（表头展示用；写入时按名称首字母生成默认值，支持人工改库修正，渲染时空值回退全名） |

### 4.2 changeSet `create_table_torn_faction_rw_rank`（名次结算表）

| 列 | 类型 | 约束 | remarks |
|---|---|---|---|
| `id` | BIGINT | PK | 主键ID（雪花） |
| `rw_id` | BIGINT | NOT NULL | RW ID |
| `user_id` | BIGINT | NOT NULL | 成员Torn用户ID |
| `nickname` | VARCHAR(64) | NOT NULL | 结算时昵称快照 |
| `rank_num` | INT | NOT NULL | 战神榜名次（输出评分降序行号） |
| `damage_score` | NUMERIC(14,2) | | 输出评分快照 |
| `deleted`/`create_time`/`update_time` | | | 同项目惯例 |

- 唯一约束 `uk_rw_rank_war_user(rw_id, user_id)`
- **禁用列名 `rank`（PostgreSQL保留字），一律用 `rank_num`**
- `torn_faction_rw` 不加索引（表仅几十行）

## 5. 包规划与文件清单

业务归属RW攻击统计，新文件集中在两个子包，避免摊大饼：

```text
torn/service/faction/attack/contribution/          (5个新文件)
  RwContributionCalculator.java          得分纯函数
  RwRankSettleService.java               名次结算
  RwContributionQueryService.java        查询编排
  RwScoreBackfillService.java            一次性回填
  image/RwContributionDocumentAssembler.java   HTML表格文档组装

torn/model/faction/attack/contribution/           (3个新record)
  RwContributionWarBO / RwContributionRowBO / RwContributionReportBO

repository/model/faction/attack/TornFactionRwRankDO.java      (新)
repository/mapper/faction/attack/TornFactionRwRankMapper.java (新)
repository/dao/faction/attack/TornFactionRwRankDAO.java       (新)
resources/mapper/faction/attack/TornFactionRwRankMapper.xml   (新, 物理DELETE)

napcat/strategy/faction/attack/publish/FactionRwContributionStrategyImpl.java  (查询指令)
napcat/strategy/manage/RwScoreBackfillStrategyImpl.java                        (SA回填指令)

resources/table-image/rw-contribution-table.css  (新)
```

**修改文件（10个）**：

| 文件 | 修改内容 |
|---|---|
| `constants/bot/BotCommands.java` | +`RW_CONTRIBUTION="RW贡献榜"`、`RW_SCORE_BACKFILL="RW比分回填"`（名称交付文案时可微调） |
| `constants/torn/TornConstants.java` | +`RW_ACTIVE_WINDOW_MINUTES=3`、`RW_ACTIVE_MIN_BATTLE_COUNT=100` |
| `napcat/strategy/faction/attack/BaseRwStrategy.java` | 删除私有窗口常量，改引用 TornConstants |
| `repository/model/faction/attack/TornFactionRwDO.java` | +5字段（targetScore/winnerFactionId/factionScore/opponentScore/opponentShortName，包装类型可空） |
| `torn/model/faction/rw/TornFactionRwDTO.java` | 参数化（sort/limit/offset） |
| `torn/model/faction/rw/TornFactionRwVO.java` | `convert2DO` 补写 target/winner（score语义见§6.1） |
| `torn/service/data/TornRwDataService.java` | ended分支写终值+触发结算 |
| `utils/image/document/TableThemeEnum.java` | +`RW_CONTRIBUTION("rw-contribution-table", "/table-image/rw-contribution-table.css")`；通用基础层提升为私有常量`BASE_CSS`，由`getCssResources()`统一前置 |
| `db/changelog/db.changelog-master.yaml` | 末尾追加 1.6.4/rw.yaml include |
| `pom.xml` | shared.db.tests 白名单追加 `RwContributionSettleReplayItTest` |

## 6. 详细设计

### 6.1 API模型

- `TornFactionRwDTO`：加字段 `sort`(String)/`limit`(Integer)/`offset`(Integer)，加 `@NoArgsConstructor`+`@AllArgsConstructor`（现有无参调用点不受影响），`buildReqParam()` 仅输出非null参数（仿 `TornFactionAttackDTO`，LinkedMultiValueMap）。
- `TornFactionRwVO.convert2DO`：`target > 0` 时写 `targetScore`；`winner != 0` 时写 `winnerFactionId` 否则留null；**score字段不写**——终值只由 ended 分支/回填写入，保持"null=未知"语义，避免登记时写0污染回填的幂等判断。另写 `opponentShortName` 默认值。默认简称生成逻辑集中在单一工具方法供登记/结束/回填三处复用，规则：名称按空格与连字符分段取各段首字母大写拼接（Destructive Anomaly→DA、The Next Level - Forge→TNLF），仅为兜底默认值，用户可改库修正（PTA/MHY/CCRC 类非首字母简称靠人工维护）。

### 6.2 终值落库与名次结算

- `TornRwDataService.spiderRwData(TornFactionRwVO, ...)` 的 `ended` 分支：现有 lambdaUpdate 追加 set 4个终值字段（取自 currentRw，按 factions[].id 区分我方/对手），`opponent_short_name` 为null时一并补默认值；update 后按主键重查 DO，调用 `rwRankSettleService.settle(rw)`；try/catch(RuntimeException) 只 log.error 不阻断主流程（缺结算行可由回填指令补）。
- `RwRankSettleService.settle(TornFactionRwDO rw)`：
  1. `@Transactional`；
  2. 先按 rw_id **物理DELETE** 该场结算行（Mapper XML 物理 DELETE，规避MP逻辑删除行占位唯一索引）；
  3. `queryActiveTimeWindows`（用 TornConstants 常量 + rw 起止时间）→ `queryPlayerAttackStatByWindows` → 按返回顺序 rank = index + 1 → 组装DO `saveBatch`。
  复用现有两条SQL，零修改。

### 6.3 一次性回填（RwScoreBackfillService）

入口 `backfillAll()`：

1. 遍历 `TornSettingFactionManager.getIdMap()` 全部帮派，各用各的key调 `/faction/rankedwars?sort=DESC&limit=100`；
2. 按war id匹配库内行，**仅更新仍为null的列**（幂等，重跑无副作用）；
3. 翻页兜底：`offset += 100` 循环，直到返回空页或本页最旧war早于该帮派库内最早 `start_time`（实测首页覆盖到16587，库内最早30329，单页即完成）；
4. 随后对该帮派"end_time非空且opponent_score>3000且结算表无行"的场次逐场调 settle 回放；
5. 返回逐帮派汇总文本（匹配数/更新数/缺漏rwId列表）。

策略类 `RwScoreBackfillStrategyImpl` 仿 `RefreshCacheStrategyImpl`：`isNeedSa()=true`、`getRoleType()=null`、放 manage 包。

**用完即弃**：生产验证后单独小提交删除策略类 + BotCommands 常量（Service保留，其幂等回填能力可被结算补漏场景复用）。

### 6.4 得分计算器（纯函数）

`RwContributionCalculator`（无状态）：

- `coefficient(int opponentScore)`：s≤3000 抛 IllegalArgumentException；s<6000 返回 0.5；否则 1.0 + 0.1 × ((s−6000)/6000 整除)，BigDecimal；
- `baseScore(int rank)`：101 − rank；
- `warScore(int rank, int opponentScore)`：base × coefficient，scale=1，HALF_UP；
- 门槛3000/6000、基数0.5、步进0.1、档宽6000 为私有常量。

### 6.5 查询编排（RwContributionQueryService）

`buildReport(long factionId)`：

1. lambdaQuery 取3场（§2口径，`.last("LIMIT 3")`）；
2. 结算表 `IN(rwIds)` 取全量行，内存join；
3. 按用户聚合 warScore、总分、场次数，排序按§2；
4. 某场无结算行时该场保留在报告 warMeta 中，行内标记"未结算"（不阻塞其余场次、不触发懒结算）；
5. 返回 `RwContributionReportBO(List<RwContributionWarBO> wars, List<RwContributionRowBO> rows, LocalDateTime buildTime)`。

record 组件逐个 `@param` 注释、一参数一行。

### 6.6 渲染（表格图片）

- `RwContributionDocumentAssembler` 仿 `PcRaceDocumentAssembler`：**4+N列**（N=合格场次数，1~3）= 排名/ID/昵称/总分 + N个场次列；宽度1600；标题 `{本帮派简称}最近RW真赛贡献榜`（简称复用 `torn_setting_faction.faction_short_name`，如 PHN，不加新列）。样式定稿已原样提取为 `resources/table-image/rw-contribution-table.css`（主题层，与通用基础层 `resources/table-image/table-base.css` 按序层叠；样式与出图结构逐字归档见附录A）：红色系主题区别于PC赛车紫；前三名无特殊底色与奖牌，名次列统一样式；总分区深红加粗。注意：场次列表头的两行渲染依赖 overflow-wrap 的 pre-line，主题层表头不得设置 white-space:nowrap。
- **场次列表头（用户定稿两行式）**：第一行 `{rwId} {对手简称}`，第二行 `{对手得分} 系数{系数}`，如 `48522 DA` 换行 `22625 系数1.2`。对手简称读取 `torn_faction_rw.opponent_short_name`（默认值生成与人工改库维护规则见§6.1）；空值回退渲染全名。
- 数据单元格：`第9名 92×1.2=110.4`；未参加 `—`；该场结算缺失 `未结算`。
- footer：`统计口径：对手得分>3000的最近3场已结束真赛（当前N场） ｜ 基础分=101−战神榜名次 ｜ 更新于 {时间}`。
- **输出形态（2026-09-16 用户二次确认）**：只发送一条表格图片消息，不再附带文本消息；原 `RwContributionTextAssembler` 已删除，`FactionRwContributionStrategyImpl` 只返回 `ImageQqMsg`。图片DOM结构逐字示例见附录A。

- **边界处理**：0场合格——不渲染表格，直接回复文本 `暂无符合条件的真赛（需已结束且对手得分>3000）`；1~2场合格——正常渲染，列数4+N自适应，总分=现有场次之和，footer 标注"当前N场"；某场结算缺失不阻塞，按§6.5标记`未结算`。
- CSS 新文件参考 `pc-race-table.css` 精简。

### 6.7 查询指令（FactionRwContributionStrategyImpl）

- 继承 `BaseRwStrategy`（自动限5群、按发送者定帮派），`getRoleType()=null` 全员可用；
- handle：无合格场次（含该帮派从未登记真赛）回复 `暂无符合条件的真赛（需已结束且对手得分>3000）`；正常时返回 `List.<QqMsgParam<?>>of(ImageQqMsg.fromBase64(renderer.render(doc)))` 单条图片消息（不再附带文本消息，见§6.6）。

## 7. 测试方案（收敛原则：一条规则一个主证据层）

| 测试类 | 层级 | 覆盖 |
|---|---|---|
| `RwContributionCalculatorTest` | 纯单元（唯一完整边界层） | §2全部边界值 |
| `RwContributionSettleReplayItTest` | shared-db，@Rollback | **金标准**：对48522真实DO调settle，断言 NoZuoNoDie rank_num=9、Cinderine=1、总行数=75；登记进pom白名单 |
| `RwContributionQueryServiceTest` | Mockito | 3场过滤口径/不足3场/0场/未结算标记/并列排序 |
| `RwScoreBackfillServiceTest` | Mockito | 仅填null幂等/翻页终止条件/结算回放触发条件 |
| `RwRankSettleServiceTest` | Mockito轻量 | 调用链与物理DELETE先于插入的顺序，不重复SQL边界 |

策略层不新增测试（接线由现有分发框架保证）。禁止为同一规则跨层复制断言矩阵。

## 8. 编码规范硬性要求（验收时逐项检查）

- 新增/修改文件头 `@version=1.6.4`，`@since` 保持原值不动；内部类不写 @author/@version/@since；
- record 声明换行时一参数一行（首参数也另起一行）；
- CRLF 换行符（提交前对变更文件 unix2dos）；
- Sonar 门禁：复杂度≤15、参数≤7、无嵌套三元、约8行重复代码即打回；
- 方法/字段/record组件 Javadoc 业务语义齐全；
- Liquibase 表和字段 remarks 齐全；
- mvn 必须显式 `JAVA_HOME="C:/Program Files/Java/jdk-21"`。

## 9. 明确不做

不改战神榜现有SQL与渲染；chain不落库；不做人工补录指令；不做懒结算；不做飞书同步；新列不做超出需求的UI展示；`torn_faction_rw` 不加索引。

## 10. 验收与Review标准

- [ ] Liquibase 在共享库执行成功，5列（其中4列为战争终值列，`target_score` 为登记值）+新表+唯一约束与 remarks 逐字符合§4
- [ ] 48522金标准测试通过（NoZuoNoDie=9名=92×1.2）
- [ ] 计算器边界值全部命中§2表格
- [ ] 回填幂等：对已有非null值零覆盖；重跑结果一致
- [ ] 指令在5群可触发、非5群不可见；只发送一条表格图片消息（无文本消息）；无合格场次有兜底文案；不足3场列数自适应且footer标注当前场数
- [ ] 对手简称默认生成、人工改库后渲染生效、空值回退全名；标题带本帮派简称
- [ ] 聚焦测试全过（§7五类），全量未跑需说明
- [ ] 交付附图片逐字示例（DOM结构见附录A）
- [ ] 包结构符合§5，无文件放错包、无重复代码

## 11. 上线步骤

1. 发布（DDL随Liquibase自动执行，向后兼容，可先发库后发码）；
2. SA在主群执行 `g#RW比分回填#`；
3. 核对汇总（SMTH应回填至30329，最近3场=48522/46672/44155）；
4. 验证 `g#RW贡献榜#`；
5. 下个提交删除回填指令（用完即弃）。

## 12. 决策记录

| 决策 | 结论 | 依据 |
|---|---|---|
| 名次数据源 | 战争结束结算落库+历史回放 | 口径冻结防SQL演进漂移；指令轻量 |
| 对手得分来源 | 仅API落库 | 明细反推不可行（DA respect合计21273 vs 真实22625） |
| 落库字段 | 5列，chain丢弃（第5列`opponent_short_name`为2026-09-16确认新增，原文“4列”是新增前的遗留措辞） | 用户比对真实数据结论 + 2026-09-16确认 |
| 回填形态 | SA一次性指令，用完即弃 | 用户确认通过 |
| 输出/权限 | 只发HTML表格图片单消息（不附带文本）；全员5群 | 用户确认（2026-09-16 二次确认） |
| 结算表幂等 | 物理DELETE+重插（事务） | MP逻辑删除会占位唯一索引 |
| 列名rank_num | 规避PostgreSQL保留字rank | 项目既有约定 |
| 前三名样式 | 无特殊底色、无奖牌，名次列统一样式 | 用户二次确认（2026-09-16） |
| 对手简称 | `opponent_short_name`列：默认首字母规则+人工改库修正，空值回退全名 | 用户确认（2026-09-16），简称非首字母规则可 derive（PTA/MHY/CCRC） |
| 榜单标题 | `{本帮派简称}最近RW真赛贡献榜`，复用faction_short_name | 用户确认（2026-09-16） |
| 场次列表头 | 两行式：`{rwId} {对手简称}` + `{得分} 系数{系数}` | 用户确认（2026-09-16） |
| 指令名 | 查询指令定名`RW贡献榜` | 用户确认（2026-09-16），回填指令名仍可在交付时微调 |
| 文本形态 | 只输出表格图片消息，删除 `RwContributionTextAssembler` | 用户确认（2026-09-16） |
| 样式预览文件 | 预览HTML（`.ai/preview/rw-contribution-preview.html`）已删除，样式与出图结构逐字归档进附录A | 用户确认（2026-09-16） |

---

## 附录A 样式与出图结构归档（原预览文件 `.ai/preview/rw-contribution-preview.html`，已删除）

原样式预览HTML是临时文件，已删除；其全部内容归档在本附录，方案不再依赖该文件。出图实际加载的样式是资源目录下的两个CSS文件，本附录为逐字副本以便脱离代码库完整复盘样式定稿，两者不一致时以资源文件为准。

- A.1 基础层CSS（= `src/main/resources/table-image/table-base.css`，140行逐字一致）
- A.2 主题层CSS（= `src/main/resources/table-image/rw-contribution-table.css`，82行逐字一致；预览原件仅"列宽与对齐"注释为`共7列`，正式文件改为`最多7列`以适应4+N列）
- A.3 正式图片区DOM结构（机器出图即此结构）
- A.4 占位单元格与样式说明示意（非正式图片内容）
- A.5 预览页复原方法

### A.1 基础层CSS（`resources/table-image/table-base.css`）

```css
* {
    box-sizing: border-box;
}

html,
body {
    margin: 0;
    padding: 0;
    background: #ffffff;
}

body {
    font-family: "Microsoft YaHei", "Noto Color Emoji", "Segoe UI Emoji", "Apple Color Emoji", sans-serif;
    color: #1f2937;
}

.table-image {
    display: block;
    width: 100%;
    padding: 16px;
    background: #ffffff;
}

table {
    border-collapse: collapse;
    table-layout: auto;
    width: 100%;
    font-size: 18px;
}

caption {
    padding: 0 0 12px;
    font-size: 26px;
    font-weight: 700;
    text-align: left;
    color: #111827;
}

td {
    min-width: 90px;
    max-width: 520px;
    padding: 9px 12px;
    border: 1px solid #d1d5db;
    vertical-align: middle;
    line-height: 1.35;
}

.overflow-wrap {
    white-space: pre-line;
    overflow-wrap: anywhere;
}

.overflow-ellipsis,
.overflow-clip {
    max-width: 520px;
    white-space: nowrap;
    overflow: hidden;
}

.overflow-ellipsis {
    text-overflow: ellipsis;
}

.cell-title {
    background: #eef2ff;
    color: #1e1b4b;
    font-weight: 700;
    text-align: center;
    font-size: 32px;
    padding: 18px 12px;
}

.cell-section {
    background: #e5e7eb;
    color: #111827;
    font-weight: 700;
    text-align: center;
}

.cell-section-head {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex-wrap: wrap;
    gap: 10px;
    max-width: 100%;
}

.cell-section-name {
    font-weight: 700;
}

.cell-badge {
    display: inline-block;
    border-radius: 9999px;
    padding: 2px 12px;
    font-size: 15px;
    font-weight: 700;
    white-space: nowrap;
}

.badge-success {
    background: #dcfce7;
    color: #14532d;
}

.badge-info {
    background: #e0e7ff;
    color: #3730a3;
}

.badge-warning {
    background: #fef3c7;
    color: #78350f;
}

.badge-danger {
    background: #fee2e2;
    color: #991b1b;
}

.badge-neutral {
    background: #f9fafb;
    color: #4b5563;
    border: 1px solid #d1d5db;
}

.cell-footer {
    background: #f9fafb;
    color: #4b5563;
    font-size: 14px;
    text-align: center;
    word-break: keep-all;
}

.cell-header      { background: #1e1b4b; color: #ffffff; font-weight: 700; text-align: center; }
.cell-body        { background: #ffffff; color: #1f2937; text-align: left; }
.cell-rank-first  { background: #fef3c7; color: #92400e; font-weight: 700; }
.cell-rank-second { background: #f3f4f6; color: #374151; font-weight: 700; }
.cell-rank-third  { background: #ffedd5; color: #9a3412; font-weight: 700; }
```

### A.2 主题层CSS（`resources/table-image/rw-contribution-table.css`）

```css
/* ==========================================================================
   RW贡献榜主题层：只放RW真赛贡献榜的版式与配色差异，通用规则一律复用基础层。
   列数为 4+N（N=合格场次数，1~3）：排名/ID/昵称/总分 + N个场次列；
   下方 :nth-child 列规则按最多3场定义，合格场次不足时靠后的规则自然不生效。
   注意：本主题层表头必须保持换行能力（依赖 overflow-wrap 的 pre-line），
   不得为表头设置 white-space:nowrap，否则两行式场次表头会被压成一行。
   ========================================================================== */

/* 图片宽度：截图以 article.table-image 为根元素，此处宽度即最终PNG宽度 */
.table-image {
    width: 1600px;
    padding: 20px;
    background: #eef1f8;
}

.table-image table {
    width: 100%;
    background: #ffffff;
    box-shadow: 0 2px 12px rgba(15, 23, 42, 0.12);
}

/* ---- 列宽与对齐（榜单数据行，最多7列） ---- */
.table-image table td:nth-child(1) { width: 100px; text-align: center; font-weight: 700; font-variant-numeric: tabular-nums; }  /* 排名 */
.table-image table td:nth-child(2) { width: 130px; text-align: center; color: #475569; font-variant-numeric: tabular-nums; }  /* ID */
.table-image table td:nth-child(3) { width: 230px; max-width: 230px; text-align: left; }                                        /* 昵称 */
.table-image table td:nth-child(4) { width: 180px; text-align: center; font-weight: 700; color: #7f1d1d; font-variant-numeric: tabular-nums; } /* 总分 */
.table-image table td:nth-child(5) { width: 320px; text-align: center; font-variant-numeric: tabular-nums; }                     /* 最近一场 */
.table-image table td:nth-child(6) { width: 320px; text-align: center; font-variant-numeric: tabular-nums; }                     /* 第二场 */
.table-image table td:nth-child(7) { width: 320px; text-align: center; font-variant-numeric: tabular-nums; }                     /* 第三场 */

/* ---- 标题/表头/页脚覆盖：必须位于上方列规则之后 ---- */
.table-image table td.cell-title  { width: auto; max-width: none; text-align: center; }
.table-image table td.cell-header { width: auto; max-width: none; text-align: center; line-height: 1.5; }
.table-image table td.cell-footer { width: auto; max-width: none; text-align: center; }

/* ---- 边框与质感 ---- */
.table-image table td {
    border: 0;
    border-bottom: 1px solid #e2e8f0;
    padding: 11px 14px;
}

.table-image table td:nth-child(1) { border-left: 1px solid #e2e8f0; }
.table-image table td:last-child  { border-right: 1px solid #e2e8f0; }

.table-image table td.cell-title {
    background: linear-gradient(135deg, #7f1d1d 0%, #b91c1c 100%);
    color: #ffffff;
    border-top: 1px solid #b91c1c;
    border-bottom: 0;
    font-size: 30px;
    letter-spacing: 2px;
    padding: 22px 16px;
}

.table-image table td.cell-header {
    background: linear-gradient(180deg, #991b1b 0%, #7f1d1d 100%);
    color: #fee2e2;
    border-bottom: 2px solid #b91c1c;
    font-size: 17px;
    font-weight: 700;
    letter-spacing: 1px;
    padding: 13px 12px;
}

/* 斑马纹：只作用于榜单数据行，标题/表头/页脚不受影响 */
.table-image table tr:nth-child(even) td:not(.cell-title):not(.cell-header):not(.cell-footer) {
    background: #f1f5f9;
}

.table-image table td.cell-body {
    background: #ffffff;
    color: #1f2937;
}

.table-image table td.cell-footer {
    background: #fef2f2;
    color: #4b5563;
    border-top: 2px solid #fecaca;
    border-bottom: 0;
    padding: 12px 14px;
}
```

### A.3 正式图片区DOM结构

数据为 48522/46672/44155 三场真实重放结果；正式图片为全榜约75行，下列预览只展示前8行。

```html
<article class="table-image">
<table><tbody>
<tr><td class="cell-title overflow-wrap" colspan="7">PHN最近RW真赛贡献榜</td></tr>
<tr>
<td class="cell-header overflow-wrap">排名</td>
<td class="cell-header overflow-wrap">ID</td>
<td class="cell-header overflow-wrap">昵称</td>
<td class="cell-header overflow-wrap">总分</td>
<td class="cell-header overflow-wrap">48522 DA
22625 系数1.2</td>
<td class="cell-header overflow-wrap">46672 TNL-Forge
3752 系数0.5</td>
<td class="cell-header overflow-wrap">44155 Arcadia
16126 系数1.1</td>
</tr>
<tr>
<td class="cell-body overflow-wrap">1</td>
<td class="cell-body overflow-wrap">1864837</td>
<td class="cell-body overflow-wrap">Cinderine</td>
<td class="cell-body overflow-wrap">280.0</td>
<td class="cell-body overflow-wrap">第1名 100×1.2=120.0</td>
<td class="cell-body overflow-wrap">第1名 100×0.5=50.0</td>
<td class="cell-body overflow-wrap">第1名 100×1.1=110.0</td>
</tr>
<tr>
<td class="cell-body overflow-wrap">2</td>
<td class="cell-body overflow-wrap">2945480</td>
<td class="cell-body overflow-wrap">SimonSoooou</td>
<td class="cell-body overflow-wrap">272.7</td>
<td class="cell-body overflow-wrap">第4名 97×1.2=116.4</td>
<td class="cell-body overflow-wrap">第4名 97×0.5=48.5</td>
<td class="cell-body overflow-wrap">第3名 98×1.1=107.8</td>
</tr>
<tr>
<td class="cell-body overflow-wrap">3</td>
<td class="cell-body overflow-wrap">2554043</td>
<td class="cell-body overflow-wrap">Ciallo</td>
<td class="cell-body overflow-wrap">272.3</td>
<td class="cell-body overflow-wrap">第2名 99×1.2=118.8</td>
<td class="cell-body overflow-wrap">第3名 98×0.5=49.0</td>
<td class="cell-body overflow-wrap">第6名 95×1.1=104.5</td>
</tr>
<tr>
<td class="cell-body overflow-wrap">4</td>
<td class="cell-body overflow-wrap">2910635</td>
<td class="cell-body overflow-wrap">Koike</td>
<td class="cell-body overflow-wrap">269.9</td>
<td class="cell-body overflow-wrap">第5名 96×1.2=115.2</td>
<td class="cell-body overflow-wrap">第5名 96×0.5=48.0</td>
<td class="cell-body overflow-wrap">第4名 97×1.1=106.7</td>
</tr>
<tr>
<td class="cell-body overflow-wrap">5</td>
<td class="cell-body overflow-wrap">2687001</td>
<td class="cell-body overflow-wrap">lixu1020</td>
<td class="cell-body overflow-wrap">267.4</td>
<td class="cell-body overflow-wrap">第3名 98×1.2=117.6</td>
<td class="cell-body overflow-wrap">第6名 95×0.5=47.5</td>
<td class="cell-body overflow-wrap">第8名 93×1.1=102.3</td>
</tr>
<tr>
<td class="cell-body overflow-wrap">6</td>
<td class="cell-body overflow-wrap">2450323</td>
<td class="cell-body overflow-wrap">Cestbon</td>
<td class="cell-body overflow-wrap">264.4</td>
<td class="cell-body overflow-wrap">第7名 94×1.2=112.8</td>
<td class="cell-body overflow-wrap">第9名 92×0.5=46.0</td>
<td class="cell-body overflow-wrap">第5名 96×1.1=105.6</td>
</tr>
<tr>
<td class="cell-body overflow-wrap">7</td>
<td class="cell-body overflow-wrap">3312605</td>
<td class="cell-body overflow-wrap">NoZuoNoDie</td>
<td class="cell-body overflow-wrap">258.1</td>
<td class="cell-body overflow-wrap">第9名 92×1.2=110.4</td>
<td class="cell-body overflow-wrap">第8名 93×0.5=46.5</td>
<td class="cell-body overflow-wrap">第9名 92×1.1=101.2</td>
</tr>
<tr>
<td class="cell-body overflow-wrap">8</td>
<td class="cell-body overflow-wrap">2508470</td>
<td class="cell-body overflow-wrap">Gustavo7788</td>
<td class="cell-body overflow-wrap">256.3</td>
<td class="cell-body overflow-wrap">第10名 91×1.2=109.2</td>
<td class="cell-body overflow-wrap">第7名 94×0.5=47.0</td>
<td class="cell-body overflow-wrap">第10名 91×1.1=100.1</td>
</tr>
<tr><td class="cell-footer overflow-wrap" colspan="7">…… 第9名起略（预览只展示前8行，正式图片为全榜约75行）</td></tr>
<tr><td class="cell-footer overflow-wrap" colspan="7">统计口径：对手得分&gt;3000的最近3场已结束真赛 ｜ 基础分=101−战神榜名次 ｜ 更新于 2026-09-16 16:00</td></tr>
</tbody></table>
</article>
```

### A.4 占位单元格与样式说明示意（非正式图片内容）

```html
<!-- ============ 特殊单元格样式示意（非真实数据，不在正式图片内） ============ -->
<div class="preview-note">
<h2>特殊单元格样式示意</h2>
<article class="table-image">
<table><tbody>
<tr><td class="cell-header overflow-wrap">排名</td><td class="cell-header overflow-wrap">ID</td><td class="cell-header overflow-wrap">昵称</td><td class="cell-header overflow-wrap">总分</td><td class="cell-header overflow-wrap">最近一场</td><td class="cell-header overflow-wrap">第二场</td><td class="cell-header overflow-wrap">第三场</td></tr>
<tr><td class="cell-body overflow-wrap">9</td><td class="cell-body overflow-wrap">1111111</td><td class="cell-body overflow-wrap">示例·未参加</td><td class="cell-body overflow-wrap">105.6</td><td class="cell-body overflow-wrap">—</td><td class="cell-body overflow-wrap">—</td><td class="cell-body overflow-wrap">第5名 96×1.1=105.6</td></tr>
<tr><td class="cell-body overflow-wrap">10</td><td class="cell-body overflow-wrap">2222222</td><td class="cell-body overflow-wrap">示例·未结算</td><td class="cell-body overflow-wrap">108.0</td><td class="cell-body overflow-wrap">第9名 92×1.2=110.4</td><td class="cell-body overflow-wrap">未结算</td><td class="cell-body overflow-wrap">—</td></tr>
</tbody></table>
</article>
<p>— = 该场未上榜（未参加）；未结算 = 该场结算数据缺失（异常态，正常流程战争结束自动结算）。</p>
<p>样式说明：标题带本帮派简称（取自 torn_setting_faction.faction_short_name，如 PHN）；表头两行式（第一行 RW号+对手简称，第二行 对手得分+系数）；对手简称取自 torn_faction_rw.opponent_short_name 列，默认按名称首字母生成、支持人工改库修正（DA/TNL-Forge 即人工维护后的效果）；前三名无特殊底色与奖牌，名次列统一样式；总分区深红加粗；数据为48522/46672/44155三场真实重放结果。</p>
<p>调整方法：直接改上方 style 中【主题层】段落，浏览器刷新即可；定稿后该段原样提取为 resources/table-image/rw-contribution-table.css。</p>
</div>
```

### A.5 预览页复原方法

把 A.1+A.2 的CSS放进 `<style>`、A.3 的DOM放进 `<body>`（A.4 可选），即得到与原 `.ai/preview/rw-contribution-preview.html` 等价的预览页，可直接用浏览器打开核对版式；正式出图由 `RwContributionDocumentAssembler` + `TableImageRenderer` 生成同一结构。

