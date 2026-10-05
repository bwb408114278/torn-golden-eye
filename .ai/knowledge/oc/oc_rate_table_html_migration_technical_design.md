# 1.9.0 用户OC成功率表格HTML迁移技术方案

## 1. 文档定位

本文是 `feat-oc` 线上"用户OC成功率查询图片从 Java2D 迁移至 HTML/Chromium 渲染平台"的开发、Review 与验收契约。

- **目标版本：** `1.9.0`
- **视觉基准：** `.ai/design/oc-rate-table/oc-rate-table-final.html` 与同名 PNG（经用户逐轮确认的最终样张，布局与配色以本文第 5 章为准）
- **基线修订：** 本方案将 `table-image-rendering-technical-design.md` 第 12.1 节"保持 Java2D"名单中的 `OcRateQueryStrategyImpl` 移出；该文档其余结论继续有效。`OcMemberStrategyImpl`、OC欧皇榜/非酋榜仍保持 Java2D，不在本轮范围。
- **风险等级：** L2（公共文档模型受控扩展 + 共享分档口径抽取；无 Schema、无新外部依赖、无消息协议变更）
- **适用对象：** 开发人员、代码 Review 人员、发布验收人员。

本文中的"必须""禁止""验收"均为实现与 Review 标准。开发人员只实现 Java、资源与测试；不得自行改变业务契约、范围与验收结论。遵循最小改动原则：除本文列出的文件外不得改动其他文件。

---

## 2. 背景与已确认决策

### 2.1 业务背景

`g#OC成功率(#用户ID)` 由 `OcRateQueryStrategyImpl` 处理，当前用 `TableImageUtils`（Java2D）绘制纯黑白灰表格。用户要求迁移到 1.6.0 建成的 HTML 渲染平台，并补充以下展示语义（均经样张逐轮确认）：

1. **成功率按帮派要求分五档着色**，数值格附带要求值小字，"无记录"格同样附要求值；
2. **岗位重要程度双维度星级**：`torn_setting_oc_slot.priority`（影响成功率）与 `best_success`（影响大成功收益）各压 5 档，以 `⚔️★n` / `💰★n` 文本展示；
3. **OC级别分组着色**：入门（橙）、核心（蓝）、连锁（紫，对齐 Torn 官方对链式 OC 的紫色渲染）；8 级中作为链式前置的 OC 归入连锁组且岗位只展示 ⚔️（前置只关心成功，不关心收益）；
4. **单行制紧凑布局**：每 OC 一行，控制图片高度（满载 13 个 OC 时约 1300px，较旧三行制减约 40%）。

### 2.2 数据事实（口径依据）

1. `best_success` 全源码无消费方：仅在 DO、同步写库与 `OcPlanningSnapshotLoader:241` 装入 `OcPlanSlot`，`bestSuccess()` 访问器零调用。本轮作为展示数据源启用，不改变任何现有逻辑。
2. `priority` 与 `best_success` 均为"OC 内部占比、合计约 100"的人工字段，同步默认值 0（`TornSettingOcSyncManager` 的 `DEFAULT_SLOT_PRIORITY=0`、`DEFAULT_BEST_SUCCESS=0`）。**0 即未配置**：当前仅 Ship Happens（新 OC，无流程图）整 OC 未配置。
3. 回填后实测分布：`priority` 五档（≥25/≥20/≥15/≥10/其余）为 55/22/24/21/26 个岗位；`best_success` 非零值 143 个，按 ≥35/≥25/≥15/≥10/>0 分档为 24/35/32/23/29，接近五等分。
4. 链式前置关系来自 `torn_faction_oc.previous_oc_id` 的 110 条真实实例统计，映射完整无例外：Stacking the Deck→Ace in the Hole、Lock Stock→Hostile Takeover、Manifest Cruelty→Gone Fission（9级）→Crane Reaction（10级）。库中无静态前置配置字段，本轮以固定名单表达。
5. 帮派岗位要求：`torn_setting_faction_oc_slot` 按帮派覆盖（实测 0~75），未覆盖时回退 `torn_setting_oc_slot.pass_rate`（55~69）。同数值在不同帮派要求下可能落入不同档位，这是预期行为。
6. `TornOcRecommendManager.calcPriorityScore` 已持有 priority 五档压缩（≥25→5 … 其余→1），参与推荐评分。展示必须与其同口径，因此抽取为共享静态方法，推荐侧行为零变化。

---

## 3. 范围与非目标

### 3.1 本轮范围

1. `OcRateQueryStrategyImpl` 图片出口迁移至 `TableDocument` + `TableImageRenderer`。
2. 受控内容模型新增 `StackedText`（多行堆叠文本）形态；样式枚举、主题注册表、HTML 渲染器对应扩展。
3. 新增 OC 成功率表主题 CSS、组装器、展示口径解析器；priority 五档抽取为共享计算器。
4. `pom.xml` 版本升至 1.9.0。

### 3.2 明确不做

1. 不迁移 `OcMemberStrategyImpl`、`OcSuccessRankTableBuilder`（欧皇榜/非酋榜）及其他 Java2D 调用方；不修改 `TableImageUtils`。
2. 不改命令字、@目标用户解析、7级资格过滤与 OC 展示范围过滤（`filterOcList`/`isQualifiedForRankSeven`）的行为；不发消息协议变更。
3. 不新增 Torn API 调用、DAO 查询次数、Liquibase、缓存或表。
4. 不为 `StackedText` 引入任意属性 Map、富文本、外部 class/CSS/URL；不建第二套主题系统或深色模式。
5. `build/docker-compose.yml` 的 `golden-eye:1.9.0` 标识按项目惯例由用户在开发前调整，开发人员不得改动。
6. 不重构 `OcPlanningSnapshotLoader` 的覆盖匹配逻辑（其职责与调用方不同，最小改动原则下不做跨包收敛）。

---

## 4. 包规划

```text
pn.torn.goldeneye.utils.image.document          # 既有，受控扩展
  TableCellContent.java                          # +StackedText
  TableCellStyleEnum.java                        # +5个数值档样式 +3个级别组样式
  TableThemeEnum.java                            # +OC_RATE 主题

pn.torn.goldeneye.utils.image.render.html       # 既有，受控扩展
  HtmlTableMarkupRenderer.java                  # +StackedText 分派、新样式类名映射

pn.torn.goldeneye.torn.service.faction.oc       # 既有
  OcSlotTierCalculator.java                     # 新增：priority→1~5档共享计算器

pn.torn.goldeneye.torn.service.faction.oc.image.rate   # 新增子包：用户OC成功率表展示域
  OcRateTierResolver.java                       # 展示口径解析（纯内存，无Spring/DAO）
  OcRateTableData.java                          # 组装输入 record
  OcRateTableDocumentAssembler.java             # TableDocument 组装器（@Component）

pn.torn.goldeneye.napcat.strategy.faction.crime # 既有
  OcRateQueryStrategyImpl.java                  # 图片出口替换
```

> `oc.image` 包已有 4 类，按"文件过多拆子包"惯例将本表独立为 `oc.image.rate` 子包；后续其他 OC 数据表迁移沿用该模式。

职责边界：

- `OcSlotTierCalculator`：唯一持有 priority 五档阈值。推荐侧与展示侧共用，防止双口径漂移。
- `OcRateTierResolver`：纯静态展示口径——数值五档、双维星级文本、级别分组（含前置名单）、帮派要求覆盖解析。无 DAO、时钟、Spring 依赖。
- `OcRateTableData`：不可变输入载体（封装 5 个组装原料，方法参数收敛）。
- `OcRateTableDocumentAssembler`：把已查好、已过滤、已排序的数据组装为 `TableDocument`。不查 DAO、不排序、不渲染。
- `OcRateQueryStrategyImpl`：命令入口、用户解析、DAO 查询、资格/范围过滤，然后委托组装器与渲染器。

---

## 5. 视觉契约（冻结）

以 `.ai/design/oc-rate-table/oc-rate-table-final.png` 为准，要点如下：

1. **标题行**：`{昵称}的OC成功率`，TITLE 样式跨全表。
2. **每 OC 一行**：第一格为级别+名称合并格（StackedText：`N级·分组` 小字行 + OC 名称主行，单行完整、超长省略号兜底），其后每岗位一格，不足最大列数的尾部用 `SLOT_EMPTY` 空白格补齐。
3. **岗位格**（StackedText 四行，自上而下）：岗位编码（MAIN）/ 星级行（NOTE，未配置或前置时整行省略）/ 数值或 `无记录`（EMPHASIS/SUB）/ `要求N`（NOTE，恒存在）。
4. **数值五档配色**（单元格样式即档位）：

| 档位 | 判定（`rate`=记录值，`req`=帮派要求） | 样式 | 底/字色 |
|---|---|---|---|
| 超出要求 | `rate >= req + 10` | RATE_EXCEED | `#ccfbf1`/`#134e4a` |
| 达到要求 | `req <= rate < req + 10` | RATE_PASS | `#dcfce7`/`#14532d` |
| 低于要求·近 | `rate < req` 且 `req - rate < 10` | RATE_FAIL_NEAR | `#fef3c7`/`#78350f` |
| 低于要求·远 | `rate < req` 且 `req - rate >= 10` | RATE_FAIL_FAR | `#fee2e2`/`#991b1b` |
| 无记录 | 该岗位无 ocUser 记录 | RATE_NONE | `#f3f4f6`/`#6b7280` |

5. **级别组配色**（合并格样式）：入门 `OC_GROUP_ENTRY` `#ffedd5`/`#9a3412`；核心 `OC_GROUP_CORE` `#dbeafe`/`#1e40af`；连锁 `OC_GROUP_CHAIN` `#ede9fe`/`#5b21b6`。
6. **星级行文本**：`⚔️` = `U+2694 U+FE0F`，`💰` = `U+1F4B0`，星 = `U+2605` 重复 n 次，无空心星；两段以单空格连接，如 `⚔️★★★ 💰★`。⚔️ 档位 = `OcSlotTierCalculator.priorityTier(priority)`；💰 档位 = best_success ≥35→5、≥25→4、≥15→3、≥10→2、>0→1。
7. **分组判定**：前置判定与级别无关——前置名单 {`No Reserve`(5级)、`Stacking the Deck`/`Lock Stock`/`Manifest Cruelty`(8级)、`Gone Fission`(9级)} 内的 OC 一律归连锁前置（标签 `N级·连锁前置`、连锁紫、岗位星级行仅 ⚔️）；前置 OC 只决定后继 OC 能否开启，收益星级不展示。名单外 `rank <= 7` → 入门、`rank == 8` → 核心、`rank >= 9` → 连锁。名单来自链式实例统计（依次链向 Bidding War、Ace in the Hole、Hostile Takeover、Gone Fission、Crane Reaction），Torn 新增链式关系时需人工更新该名单（Javadoc 注明）。
8. **未配置省略**：`priority` 为 null 或 ≤0 时不显示 ⚔️；`bestSuccess` 为 null 或 ≤0 时不显示 💰（即 Ship Happens 全 OC 无星级行）。
9. **单行约束**：岗位编码、星级行、数值行一律单行（CSS `white-space:nowrap` + `text-overflow:ellipsis` 兜底）；**OC名称行例外**——级别合并格内的名称主行允许按词换行（`word-break:keep-all` + `overflow-wrap:break-word`），超长 OC 名换行完整展示而非省略，该规则不作用于其他主行。
10. **页脚图例**（FOOTER 样式，跨全表，固定三行）：

```text
色阶：超出要求≥10 ｜ 达到要求 ｜ 低于要求·差距<10 ｜ 低于要求·差距≥10 ｜ 无记录
级别分组：入门（7级及以下） ｜ 核心（8级） ｜ 连锁前置（各级别）与连锁（9~10级）　　⚔️ 影响成功率 ｜ 💰 影响大成功收益（各1~5级，未配置不显示；连锁前置岗位仅展示⚔️）
要求＝目标成员所在帮派的岗位要求（各帮派不同）
```

---

## 6. 详细文件修改清单

### 6.1 通用文档模型与渲染器

#### 修改 `utils/image/document/TableCellContent.java`

1. `sealed interface` 的 `permits` 追加 `TableCellContent.StackedText`；`readableText()` 的 switch 追加分支（各非空行以空格连接）。
2. 新增嵌套 record 与枚举：

```java
/**
 * 多行堆叠内容。每行携带受控强调级别，行样式由HTML渲染器按级别映射，
 * 用于单元格内"标题/指标/数值/附注"上下堆叠的紧凑展示。
 */
record StackedText(List<Line> lines) implements TableCellContent

/**
 * 堆叠行：文本 + 强调级别。空行应在构造时被过滤。
 */
record Line(String text, LineEmphasis emphasis)

enum LineEmphasis { SUB, MAIN, NOTE, EMPHASIS }
```

3. 构造校验：`lines` 非 null 且过滤空白行后至少一行；防御性复制；`text`/`emphasis` 非 null。类 Javadoc `@version` 更新为 `1.9.0`，说明真实语义（紧凑单行制表格的行内层级表达），不引用本文档名。

#### 修改 `utils/image/document/TableCellStyleEnum.java`

追加 8 个带中文 Javadoc 的枚举值（Javadoc 描述业务语义，如"成功率超出要求≥10的数值格"）：`RATE_EXCEED`、`RATE_PASS`、`RATE_FAIL_NEAR`、`RATE_FAIL_FAR`、`RATE_NONE`、`OC_GROUP_ENTRY`、`OC_GROUP_CORE`、`OC_GROUP_CHAIN`。`@version` 更新为 `1.9.0`。

#### 修改 `utils/image/document/TableThemeEnum.java`

追加 `OC_RATE("oc-rate-table", "/table-image/oc-rate-table.css")`，Javadoc 注明"OC成功率主题：通用基础层 + 用户OC成功率专有层"。`@version` 更新为 `1.9.0`。

#### 修改 `utils/image/render/html/HtmlTableMarkupRenderer.java`

1. `appendContent` 穷尽 switch 追加 `StackedText` 分派：输出 `<span class="cell-stacked">` 包裹，每行输出 `<span class="stacked-line stacked-{sub|main|note|emphasis}">` + 转义文本；每行独立 `escape`。
2. `styleClass` 追加 8 个映射：`RATE_EXCEED→cell-rate-exceed`、`RATE_PASS→cell-rate-pass`、`RATE_FAIL_NEAR→cell-rate-fail-near`、`RATE_FAIL_FAR→cell-rate-fail-far`、`RATE_NONE→cell-rate-none`、`OC_GROUP_ENTRY→cell-oc-entry`、`OC_GROUP_CORE→cell-oc-core`、`OC_GROUP_CHAIN→cell-oc-chain`。
3. 不新增任何接受外部 class/属性/URL 的入口。`@version` 更新为 `1.9.0`。

#### 新增 `src/main/resources/table-image/oc-rate-table.css`

完整内容如下（色值与第 5 章一致，通用基础层由主题注册表前置）：

```css
/* 用户OC成功率表专有层；基础排版与色板工具见 table-base.css */
table {
    width: 100%;
    table-layout: auto;
}

td {
    padding: 7px 10px;
    line-height: 1.3;
}

/* 级别+名称合并格：入门橙 / 核心蓝 / 连锁紫（浅底深字） */
.cell-oc-entry,
.cell-oc-core,
.cell-oc-chain {
    width: 246px;
    text-align: center;
}

.cell-oc-entry { background: #ffedd5; color: #9a3412; }
.cell-oc-core  { background: #dbeafe; color: #1e40af; }
.cell-oc-chain { background: #ede9fe; color: #5b21b6; }

/* OC名称行例外：允许按词换行，超长OC名（如Window of Opportunity）换行展示而非省略；
   该规则只作用于级别合并格内的主行，岗位编码等其余主行不受影响 */
.cell-oc-entry .stacked-main,
.cell-oc-core .stacked-main,
.cell-oc-chain .stacked-main {
    white-space: normal;
    word-break: keep-all;
    overflow-wrap: break-word;
    text-overflow: clip;
}

/* 成功率数值档（整格即档位，文字继承档位深色） */
.cell-rate-exceed    { background: #ccfbf1; color: #134e4a; text-align: center; }
.cell-rate-pass      { background: #dcfce7; color: #14532d; text-align: center; }
.cell-rate-fail-near { background: #fef3c7; color: #78350f; text-align: center; }
.cell-rate-fail-far  { background: #fee2e2; color: #991b1b; text-align: center; }
.cell-rate-none      { background: #f3f4f6; color: #6b7280; text-align: center; }

/* 堆叠行：一律单行，超长省略号兜底（OC名称行由上面的例外规则放开换行） */
.cell-stacked {
    display: block;
}

.stacked-line {
    display: block;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}

.stacked-sub      { font-size: 13px; opacity: 0.82; }
.stacked-main     { font-size: 14px; font-weight: 700; }
.stacked-note     { font-size: 11px; opacity: 0.8; }
.stacked-emphasis { font-size: 21px; font-weight: 700; line-height: 1.1; }
```

> 表格必须撑满渲染视口（`width:100%` + `auto` 布局，与平台其他主题一致）。2026-10-05 首版曾误用 `1176px` 固定宽度：真实渲染视口为 1600px，导致表格右侧约 420px 留白；且 `fixed` 布局下列宽由跨全表的标题行平分（约 168px/列），合并格的 246px 声明失效，超长 OC 名被省略。满宽修复后的验证图见 `.ai/design/oc-rate-table/oc-rate-table-fullwidth-verify.png`。

### 6.2 领域层

#### 新增 `torn/service/faction/oc/OcSlotTierCalculator.java`

- 工具类（私有构造），唯一静态方法 `static int priorityTier(int priority)`：`>=25→5、>=20→4、>=15→3、>=10→2、其余→1`。
- Javadoc 说明真实需求：岗位权重连续值在消费侧统一压缩为 5 档（推荐评分与图片展示共用），阈值与 tornprobability 权重分布对齐。
- **修改 `torn/manager/faction/crime/recommend/TornOcRecommendManager.java`：** `calcPriorityScore` 方法体改为委托 `BigDecimal.valueOf(OcSlotTierCalculator.priorityTier(slotSetting.getPriority()))`，方法签名、调用点、返回语义零变化。禁止顺手改动该类其他逻辑。

#### 新增 `torn/service/faction/oc/image/rate/OcRateTierResolver.java`

- 工具类（私有构造），纯静态口径，无 Spring/DAO/时钟依赖。方法：
  - `OcRateValueTier valueTier(Integer passRate, int requiredPassRate)`：null→`NONE`，否则按第 5.4 节判定。返回嵌套枚举 `OcRateValueTier { EXCEED, PASS, FAIL_NEAR, FAIL_FAR, NONE }`。
  - `String successStars(Integer priority)`：null 或 ≤0 返回空串；否则 `⚔️` + `★`×`OcSlotTierCalculator.priorityTier(priority)`。
  - `String fortuneStars(BigDecimal bestSuccess)`：null 或 ≤0 返回空串；否则 `💰` + `★`×档位（≥35→5 … >0→1）。
  - `OcRateGroup group(int rank, String ocName)`：嵌套枚举 `OcRateGroup { ENTRY, CORE, CHAIN_PREREQUISITE, CHAIN }`，前置名单优先于级别判定，见第 5.7 节；常量 `Set<String> CHAIN_PREREQUISITE_OCS = Set.of("No Reserve", "Stacking the Deck", "Lock Stock", "Manifest Cruelty", "Gone Fission")`，Javadoc 注明来源（链式实例统计）与"新增链式关系需人工维护"。
  - `String groupLabel(int rank, OcRateGroup group)`：返回 `N级·入门`/`N级·核心`/`N级·连锁前置`（任意级别前置）/`N级·连锁`。
  - `int requiredPassRate(TornSettingOcSlotDO slot, List<TornSettingFactionOcSlotDO> factionSlots)`：按 `(factionId 由调用方预过滤)`、`rank+ocName+slotCode` 精确匹配帮派覆盖，未命中回退 `slot.getPassRate()`。真实需求：帮派可对各岗位单独设要求，展示与资格判定必须用同一覆盖规则。

#### 新增 `torn/service/faction/oc/image/rate/OcRateTableData.java`

```java
/**
 * 用户OC成功率表组装输入。
 *
 * @param user           目标成员（取昵称与帮派过滤基准）
 * @param ocList         已过滤、已排序（rank降序、名称升序）的展示OC清单
 * @param allSlotList    全量岗位设置
 * @param ocUserList     目标成员的全量OC成功率记录
 * @param factionSlots   目标成员所在帮派的岗位要求覆盖（已按factionId过滤）
 */
public record OcRateTableData(TornUserDO user, List<TornSettingOcDO> ocList,
                              List<TornSettingOcSlotDO> allSlotList,
                              List<TornFactionOcUserDO> ocUserList,
                              List<TornSettingFactionOcSlotDO> factionSlots)
```

构造器做 null 校验与防御性复制（复用项目既有 record 校验风格）。

#### 新增 `torn/service/faction/oc/image/rate/OcRateTableDocumentAssembler.java`

- `@Component`，无注入依赖。单一公共方法 `TableDocument assemble(OcRateTableData data)`。
- 常量：`DOCUMENT_WIDTH = 1600`（与平台渲染视口宽度一致，表格撑满视口）、`DOCUMENT_TYPE = TableThemeEnum.OC_RATE.getDocumentType()`、标题模板 `"%s的OC成功率"`。
- 组装规则：
  1. 跳过 `ocUserList` 中无记录的 OC（与现行为一致）；列数 = 实际成行 OC 的岗位数最大值（与现 `calcMaxColumnSize` 语义一致，无记录 OC 不参与列数计算）。
  2. 标题行：TITLE 跨全表，`PlainText`。
  3. 每 OC 一行：合并格 `StackedText(SUB groupLabel, MAIN ocName)` + 组样式；每岗位格 `StackedText` 依序 `MAIN slotCode`、`NOTE 星级行`（success/fortune 星级均为空则整行省略；`CHAIN_PREREQUISITE` 组仅拼 ⚔️ 段）、数值行（有记录 `EMPHASIS rate`，无记录 `SUB "无记录"`）、`NOTE "要求" + requiredPassRate`；样式 = `valueTier` 映射的 5 档之一。岗位与记录的匹配沿用现口径：`ocName` 相等且 `position == slotShortCode`。尾部补齐格 `SLOT_EMPTY` + `PlainText("")`。
  4. 页脚行：FOOTER 跨全表，`PlainText` 三行以 `\n` 连接（WRAP 溢出策略），文案为第 5.10 节固定文本。
- 不查 DAO、不调用渲染器、不拼 HTML；不改变 OC 的业务排序（级别降序、名称升序由输入携带），岗位在 OC 内按编码排序属组装期的布局确定性职责。

### 6.3 策略层

#### 修改 `napcat/strategy/faction/crime/OcRateQueryStrategyImpl.java`

1. 构造依赖变更：移除 `TornFactionOcMsgTableManager`；新增 `OcRateTableDocumentAssembler`、`TableImageRenderer`。其余依赖保留。
2. `buildPassRateMsg` 重写：沿用现有 `filterOcList` 排序结果与 `settingOcSlotManager.getList()`、`settingFactionOcManager.getSlotList()`（按 `user.getFactionId()` 预过滤）构造 `OcRateTableData`，`assembler.assemble(...)` 后交 `tableImageRenderer.render(...)` 返回 Base64。
3. 删除只服务 Java2D 路径的私有方法与常量：`buildPositionRow`、`buildPassRateRow`、`calcMaxColumnSize`、`TITLE_STYLE`、`CONTENT_STYLE` 及 `java.awt`/`TableImageUtils`/`fillEmptyColumn` 相关引用。
4. `getRequiredPassRate` 改为委托 `OcRateTierResolver.requiredPassRate(...)`（先按 factionId 过滤帮列表再传入），`isQualifiedForRankSeven` 行为零变化；删除原私有实现。
5. `handle`、空结果文本 `暂未查询到记录的OC成功率`、命令字与 @目标用户支持不变。类 Javadoc `@version` 更新为 `1.9.0`。

### 6.4 构建与注释修正

#### 修改 `pom.xml`

`<version>` 由 `1.8.0` 改为 `1.9.0`，其余构建配置不动。

#### 修改 `torn/model/faction/crime/planning/OcPlanSlot.java`

仅修正 `bestSuccess` 参数 Javadoc：由错误的"该岗位当前可达到的最高成功率"改为"该岗位的大成功贡献占比（岗位设置快照）"。不改字段、构造与逻辑。

---

## 7. 测试方案（收敛）

| 规则 | 唯一主测试层 | 代表性场景 |
|---|---|---|
| 展示口径 | `OcRateTierResolverTest`（新增） | 数值档边界（`req+10` 恰好为超出、差距恰为 10 归远档）；⚔️ 档位委托计算器边界 25/20/15/10；💰 档位边界 35/25/15/10 与 0/null 不显示；分组映射（7级及以下、8级核心、三个前置名、9/10级）；帮派覆盖优先于全局默认 |
| 文档组装 | `OcRateTableDocumentAssemblerTest`（新增） | 一个含 6 岗位 OC + 一个 4 岗位 OC（断言补齐格）+ 一个前置 OC（断言无 💰）+ 一个无记录岗位（断言"无记录"与要求行）：验证行结构、单元格顺序、样式枚举、星级行省略、标题与图例文案；不建全 OC 矩阵 |
| 共享分档 | 既有 `TornOcRecommendManagerTest` | 既有用例不改即通过，证明推荐行为零变化；不新增用例 |
| HTML 安全映射 | 既有 `HtmlTableMarkupRendererTest`（修改） | 追加 2 个用例：StackedText 逐行 `escape`（含 `<>&"'`）、固定 `stacked-*` 类名与新样式类名映射、无动态属性 |
| 策略接线 | 既有 `OcRateQueryStrategyImplTest`（修改） | 既有 2 个用例适配：空结果文本不变；有记录路径断言委托 renderer 并透传 Base64；不复制口径矩阵 |
| 文档模型 | 既有 `TableDocumentTest`（修改） | 追加 StackedText 构造校验（空行过滤、空列表拒绝）1~2 个用例 |

禁止事项：

- 不做 OCR、全像素快照或 CSS 字符串断言来"验证视觉"；视觉验收以最终 Docker 内 PNG 人工比对第 5 章与 `.ai/design` 基准。
- 不新增浏览器集成测试、Spring 集成测试或真实 PostgreSQL 测试；平台渲染基线沿用既有 `HtmlTableImageRendererIntegrationTest`。
- 不为 5 个数值档、5 个星级档逐一复制组装器用例；档位判定收敛在 Resolver 层。

---

## 8. Review 阻断项

以下任一项为 P0/P1，禁止进入 1.9.0：

1. `OcRateQueryStrategyImpl` 图片请求新增 Torn API 调用、DAO 查询次数或 N+1。
2. 7级资格判定、OC 展示范围过滤、命令字、发送协议发生任何行为变化。
3. `TornOcRecommendManager` 推荐评分结果发生变化（共享分档计算器必须保持阈值与返回值完全一致）。
4. 业务层/组装器出现 HTML、CSS class、URL、颜色值或行列表拼字符串；颜色只允许出现在 CSS 与本方案。
5. `TableCellContent` 扩展超出 `StackedText` 语义，或引入属性 Map、嵌套 DSL、外部 class。
6. Emoji/星级字符与第 5.6 节 Unicode 定义不一致；前置 OC 岗位出现 💰；未配置 OC（priority/best_success 为 0）出现星级。
7. 要求值来源不是"帮派覆盖优先"；"无记录"格缺失要求行。
8. 违反单行约束（名称/编码/星级/数值换行）且无省略号兜底。
9. 修改 `TableImageUtils`、迁移名单外的 Java2D 调用方，或改动 `OcMemberStrategyImpl`、排行榜策略。
10. `pom.xml` 最终版本不是 `1.9.0`；`build/docker-compose.yml` 被 development 侧改动。
11. 代码注释出现"按技术方案第 X 章要求"等引用式表述，而非真实业务语义。

P2/P3（记录不阻断）：星级阈值口径未来校准、级别分组标签文案调整、其他表格迁移建议、深色主题。

---

## 9. 交付与验收清单

开发完成后串行执行并留痕：

```bash
JAVA_HOME="C:\Program Files\Java\jdk-21" mvn.cmd -q -DskipTests compile
JAVA_HOME="C:\Program Files\Java\jdk-21" mvn.cmd -q -Dtest=OcRateTierResolverTest,OcRateTableDocumentAssemblerTest,OcRateQueryStrategyImplTest,HtmlTableMarkupRendererTest,TableDocumentTest,TornOcRecommendManagerTest test
git diff --check
docker build -f build/Dockerfile -t golden-eye:1.9.0 .
```

（只执行上述聚焦测试；全量 Maven 由架构验收统一执行一次，避免消耗 Torn API Key。）

交付物：

1. 逐文件职责说明与最终 diff。
2. 聚焦测试真实执行数（testCompile/Surefire 计数）与通过率；`git diff --check` 结果。
3. `golden-eye:1.9.0` 镜像内以真实帮派成员执行 `g#OC成功率(#某用户ID)` 的 PNG 原图：须包含连锁/核心/入门三组、前置 OC（无💰）、至少一个"无记录"格与帮派覆盖要求值，人工比对 `.ai/design/oc-rate-table/oc-rate-table-final.png` 与第 5 章契约。
4. 展示 OC 数量最多的成员（7~10级满载 13 OC）的 PNG 一张，确认图片高度与单行约束。
5. 明确声明：未迁移名单内文件零改动；`TableImageUtils` 未改动；推荐评分用例零修改通过。
6. 不存在第 8 章阻断项。

满足以上后本轮停止；后续星级阈值校准、级别标签文案、其他表格迁移均需独立授权。
