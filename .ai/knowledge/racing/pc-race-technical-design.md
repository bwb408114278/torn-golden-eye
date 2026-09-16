# SMTHPC 每日赛车数据抓取与查询技术方案（1.6.3）

## 元信息

- 文档类型：技术方案 / 开发与验收契约
- 适用项目：Golden-Eye
- 目标版本：1.6.3（Liquibase changelog 批次目录）
- 参考实现：`race copy.py`（外部参考脚本，不在仓库内，只复用其业务口径，不复用其实现方式）；`/user/races` 真实响应报文（2026.09.15 用户提供）
- 风险等级：L3
- 最后更新：2026.09.15
- 维护人：Bai
- 状态：已确认待开发（列头与消息文案可后续微调）
- 增量记录：2026.09.15 新增「新人奖」并修复/补齐 `torn_user.register_time`（见 2.2、6.8）；表格与文案术语由「联盟」统一改为「家族」

---

## 1. 文档定位与使用方式

本文是 SMTHPC 赛车功能的开发、Review 与验收契约。

- 开发人员只实现 Java、资源、Liquibase 与测试，不得自行改变本文的业务口径、范围或验收结论。
- 第 6 章是文件级实施清单，未列出的文件不得改动。
- 第 11 章是 Review 与验收清单，逐条判定。
- 第 13 章为已确认决策记录。

设计约束（来自用户明确要求）：

1. 最小改动：不重构无关代码，不为未来能力预埋抽象。
2. 功能与测试收敛：禁止重复实现同一规则，禁止为凑数量新增测试。
3. 包内文件过多必须拆分子包。
4. 鼓励封装与既有设计模式的复用，不为单次使用发明抽象。

---

## 2. 需求与业务契约

### 2.1 赛事与时间口径

| 项 | 契约 |
|---|---|
| 赛事名称 | `SMTHPC`（匹配不区分大小写） |
| 开赛时间 | 每天北京时间 00:30 |
| 赛事时长 | 约 5 小时（约 05:30 结束） |
| 抓取时间 | 每天北京时间 **08:30**（晚于 08:10 的家族成员刷新，且赛事已结束） |
| 业务日期 | `business_date` = 赛事 `start_time` 对应的 **Torn 日** = `startTime.minusHours(8).toLocalDate()` |
| 默认查询 | `PC结果` 无参数 = `当前Torn日 - 1`（固定日期；该日未抓取时返回未抓取提示，**不回退**更早的已抓取日期） |

说明：北京 00:30 的赛事在 Torn 日口径下属于**前一天**（Torn 日在 UTC 00:00 = 北京 08:00 切换）。因此 08:30 抓取到的正是"昨日赛事"。业务日期一律按 `business_date` 计算与查询，不使用 `LocalDate.now()`。

### 2.2 数据口径

| 规则 | 契约 |
|---|---|
| 落库范围 | 该场赛事的**全部参赛选手**，不只是家族选手 |
| 查询范围 | 仅 `is_alliance = true` 的选手 |
| 家族判定 | 抓取时刻 `torn_user.faction_id ∈ torn_setting_faction.id` 全集（当前 10 个帮派，**禁止硬编码帮派ID或名称**） |
| 归属快照 | `nickname` / `faction_id` / `is_alliance` 存**抓取时的快照**，不随用户后续换帮派变化 |
| 榜单 | `is_alliance = true` **全员入图（含撞车）**：未撞车选手按 API 原始 `position` 升序展示；SMTH 名次 = 家族未撞车选手内按 `position` 排序的序号（1..N）；撞车行置底，名次列显示"—"；两列名次均为 API 原始口径，不重排 |
| 最快圈 | `is_alliance = true`、未撞车且 `best_lap_time` 非空，取最小者 |
| 参赛率 | 家族参赛人数 ÷ 总参赛人数 × 100，保留两位小数；**分子分母均含撞车选手** |
| Crash 名单 | `is_alliance = true` 且 `has_crashed = true`；`position` 为空者显示「昵称(未完赛)」 |
| 抽奖 | 池 = **全部**未撞车的家族选手（与图片展示行一致，按名次升序，顺序稳定）；种子 = `raceId + "Ciallo"`；抽 **1** 人；同一 raceId 结果恒定 |
| 新人奖 | 池 = **全部家族选手（含撞车）**中 `torn_user.register_time` 晚于「开赛时间 − NEWCOMER_DAYS(120) 天」者，按榜单顺序稳定排列；种子 = `raceId + "CialloNew"`；抽 **1** 人；`register_time` 为空者不具备资格；与普通抽奖使用不同盐、相互独立，**允许同一人重复中奖**；同一 raceId 结果恒定 |
| 注册时间基准 | 新人判定基准取**该场赛事开赛时间**（非查询时刻）；`register_time` 为账号不可变属性，故同一赛事结果可在任何时间复现 |
| 注册时间来源 | 实时读取 `torn_user.register_time`，**不落快照**到 `torn_racing_participant`；缺失时新人池不含该选手 |

展示分工（用户明确要求）：

- **图片表格**：家族全员榜单（8 列，见 6.5.6）。
- **文本消息**：最快圈、参赛率、Crash 名单、抽奖结果、新人奖结果（新人奖**只出现在文本**，不进图片）。

### 2.3 指令契约

| 指令 | 参数 | 行为 | 输出 |
|---|---|---|---|
| `PC结果` | 无 | 查询"昨日"赛事 | 榜单图片 + 汇总文本 |
| `PC结果` | `yyyy-MM-dd` | 查询指定业务日期 | 同上 |
| `PC结果` | 纯数字（可解析为 long） | 按 raceId 查询 | 同上 |
| `PC结果` | 其他（含超出 long 范围的数字） | 参数有误 | 文本提示 |
| `PC成绩` | 无 | 查询发送者本人 | 文本：近 10 场成绩 |
| `PC成绩` | 纯数字 | 查询指定 Torn userId | 同上 |
| `PC成绩` | `@某人` | 查询被 at 用户（已绑定时） | 同上 |

- 两条指令均**不需要管理权限**（`getRoleType()` 返回 `null`）。
- 无数据时不报错，返回文本提示。
- 指令不得触发抓取（抓取只由定时任务与启动补偿触发）。

### 2.4 抓取契约

| 场景 | 行为 |
|---|---|
| 同一业务日期已有赛事主行 | 记 info 日志并直接返回（幂等） |
| 优先级链全部未命中 | 记 warn 日志并返回，不重试、不告警 |
| 赛事 `status != finished` | 记 warn 日志并返回，**不写任何行** |
| `results` 为空 | 同上 |
| 单个候选 Key 调用异常 | 记 warn 日志，继续下一个候选 |
| 落库并发冲突（唯一索引） | 捕获 `DuplicateKeyException`，记日志并视为已抓取 |
| 抓取整体失败 | 只记日志；`PC结果` 返回"数据未就绪" |

调度与启动补偿（对齐 `TornFactionDataService` 模式）：每日抓取由 `DynamicTaskService` 一次性任务承载，执行完毕自续期次日 08:30（**失败也在 `finally` 中续期，不断链**）。应用启动时（仅 prod）：若已过当日 08:30 且当日目标业务日期无赛事主行，立即补抓；否则排定下一个 08:30。**停机跨多日的缺口按缺失处理，不回补**（用户已确认）。

---

## 3. 现状事实（已核实，作为设计依据）

| 事实 | 证据 |
|---|---|
| 渲染平台已就绪，无需新建 | `pom.xml` 固定 `playwright 1.55.0`；`TableDocument` / `TableImageRenderer` / `HtmlTableImageRenderer` / `HtmlTableMarkupRenderer` / `PlaywrightBrowserManager` |
| 现有主题是**单一硬编码 CSS** | `HtmlTableMarkupRenderer` 构造期一次性加载 `/table-image/oc-table.css`；`documentType` 目前仅透传给浏览器层，Markup 层不消费 |
| `styleClass()` 穷尽 switch 位于 `HtmlTableMarkupRenderer`（非枚举内） | 新增 `TableCellStyleEnum` 常量若不补 case 会编译失败，用于防止遗漏 |
| OC 已有迁移样板 | `OcTableDocumentAssembler`（只产语义文档）→ `TornFactionOcMsgManager`（注入 `TableImageRenderer`） |
| 家族成员数据现成 | `torn_setting_faction` 共 10 行；`torn_user.faction_id` 每帮约 90–100 人（家族用户共约 967 人），由 `TornFactionDataService` 每天 08:10 刷新 |
| 可用 Key 池充足 | `torn_api_key` 有效 405 条；PN/CCRC 共 153 条 |
| 赛事列表接口已有模型 | `TornUserRaceDTO`（`/user/races`，`limit=1` 与 `sort=DESC` 硬编码于 `buildReqParam`，当前无活跃调用方——`RacingNoticeChecker` 整类注释）、`TornUserRacesVO`、`TornRaceDetailVO`、`TornRaceScheduleVO` |
| **列表接口自带全量成绩** | 真实报文验证：`/v2/user/races?limit=20&cat=custom` 的列表项含完整 `results`（74 条全字段），支持 `cat` / `sort` / `limit` 参数；**无需详情接口** |
| P1 创建人必含赛事 | 真实报文中 `creator_id = 2554043` 本人为参赛选手（position 1），其 `/user/races` 必然包含该赛事 |
| 时间转换已有约定 | `DateTimeUtils.convertToDateTime(timestamp)` 按 UTC 转再 `+8`，与 JVM 时区无关，落库即北京时间 |
| Torn 日判定已有约定 | `DateTimeUtils.getTornLocalDate()` 在 00:00–08:00 返回前一天 |
| 动态任务基建现成 | `DynamicTaskService.updateTask(taskId, task, execTime)` 一次性任务 + 执行完自续期模式（`TornFactionDataService` 样板）；类级 `@Order(InitOrderConstants)` + `@EventListener(ApplicationReadyEvent)` 控制启动顺序 |
| 无赛车相关表与功能 | `information_schema` 中无 `*racing*` / `*race*` 表；`src/test` 无赛车测试 |
| Key 使用必须归还 | `TornApiKeyConfig.getKeyByUserId` 会把 Key 置为 in-use，返回 null 表示不可用；未 `returnKey` 会造成 Key 池泄漏 |
| Key 池可内存枚举 | `TornApiKeyConfig.getAllEnableKeys()` 返回全部 Key（候选用户ID从内存取，不查询 `torn_api_key` 表，不读取 `api_key` 列） |

---

## 4. 总体设计

### 4.1 链路

```text
08:30 动态任务（DynamicTaskService 自续期）/ 启动补偿（init 补抓）
        ↓
PcRaceCaptureService ── 幂等检查 ──→ 已有数据则返回
        ↓
PcRaceDiscoveryService  优先级候选链调 /user/races 定位赛事（响应自带全量成绩）
        ↓
PcRacePersistService  @Transactional 纯插入落库
        ↓
（查询侧，无抓取）
PC结果 ──→ PcRaceQueryService ──→ PcRaceDocumentAssembler ──→ TableImageRenderer ──→ 图片
                              ├──→ PcRaceTextAssembler ──────────────────────────→ 文本
                              └──→ TornUserDAO（批量取 register_time，构建新人奖池）
PC成绩 ──→ PcRaceQueryService ──→ PcRaceTextAssembler ──────────────────────────→ 文本
```

注册时间补齐链路（与抓取链路并行，见 6.8）：

```text
08:10 家族成员抓取 TornFactionDataService.spiderFactionMember
        ↓
TornFactionMemberManager.updateFactionMember（成员落库）
        ↓
TornUserService.backfillRegisterTime（只取 register_time IS NULL 的成员）
        ↓
/user/{id}/profile ──→ 回写 torn_user.register_time     # 长期入口

RegisterTimeBootstrapBackfill（启动一次性，临时组件，补齐后删除）
        ↓（在族且 register_time IS NULL 的全体用户）
TornUserService.backfillRegisterTime
```

### 4.2 优先级候选链（核心设计）

目标：在不支持按标题检索的 Torn API 下，用最少调用定位当天赛事。列表接口自带全量成绩，**命中即得全部数据，无需二次调用**。

| 级别 | 候选来源 | 说明 |
|---|---|---|
| P0 | 本地赛事主行 | 已有该 `business_date` 的记录则不定位（幂等） |
| P1 | 常任创建人 `2554043` | 创建人本人是参赛选手（报文证实），其 `/user/races` 必含赛事；Key 不可用时自动跳过 |
| P2 | 最近一场已抓取赛事的参赛选手 | 主行按 `business_date` 降序取一场，其选手按 `position` 升序（null 靠后）；"昨天参赛的人今天大概率参赛"，命中率最高，且随运行自动强化 |
| P3 | Key 池中 `faction_id ∈ {PN, CCRC}` 的用户 | 参赛主力帮派 |
| P4 | Key 池其余用户 | 兜底 |

约束：

- 候选去重保序，总扫描数上限 60（`DISCOVERY_SCAN_LIMIT`）。
- 命中即停，返回命中的完整 `TornRaceDetailVO`（含全量 `results`）。
- 每个候选使用 `getKeyByUserId` 取 Key（null 跳过），调用后在 `finally` 中 `returnKey`——**命中与异常路径同样归还，Key 不跨方法传递**（P0 级 Review 项）。
- 单候选异常只记日志，不中断整条链。
- 列表请求参数固定：`cat=custom`、`sort=DESC`、`limit=DISCOVERY_PAGE_SIZE(50)`（cat 过滤排除官方赛，缩小时间窗）。
- 候选用户ID来源为 `TornApiKeyConfig.getAllEnableKeys()`（内存），**不新增对 `torn_api_key` 的查询**。

### 4.3 落库提交边界

- 定位（HTTP 调用）**不在事务内**：事务方法中禁止执行耗时外部调用。
- 落库集中在 `PcRacePersistService.save(...)` 的单个 `@Transactional(rollbackFor = Exception.class)` 方法中：**纯插入**主行与明细，无任何删除语句；并发重复由全量唯一索引兜底，`DuplicateKeyException` 由 `PcRaceCaptureService` 捕获后按已抓取处理（事务回滚，无部分写入）。
- 不提供重抓/修数入口：数据修正依靠手工 SQL（用户已确认，见第 12 章）。
- 事务方法必须由**外部 Bean 调用**（`PcRaceCaptureService` → `PcRacePersistService`），避免 Spring AOP 自调用导致事务失效。

### 4.4 渲染主题化（用户明确要求）

当前 `HtmlTableMarkupRenderer` 在构造期加载唯一 CSS，无法按文档选主题。改为**主题注册表**：

```text
TableDocument.documentType  ──→  TableThemeEnum.of(type)  ──→  List<cssResource>  ──→  拼接后的单个 <style>
```

- 新增 `TableThemeEnum`（注册表模式），登记 `documentType` 与其 CSS 资源顺序。
- OC 主题：`table-base.css + oc-table.css`（渲染结果与现状等价，见 6.1.6 的层叠说明）。
- PC 主题：`table-base.css + pc-race-table.css`。
- 未注册的 `documentType` **快速失败**（`IllegalArgumentException`），不静默回退。
- CSS 拆分为「通用基础层 + 各文档主题层」，避免每个主题复制通用规则。

---

## 5. 数据库设计（Liquibase 1.6.3）

新增文件：`src/main/resources/db/changelog/1.0.1-2.0.0/1.6.3/racing.yaml`
主文件 `db.changelog-master.yaml` 追加 include。

要求：

- 每张表、每个字段必须有 `remarks`（含主键、逻辑删除、审计时间）。
- 不新建序列：`id` 使用 MyBatis-Plus 默认主键策略（与现有表一致）。
- 落库为**纯插入**（幂等 = 业务日期检查 + 唯一索引），不冗余最快圈、参赛率等派生列（查询时用筛选/聚合得到）。
- 时间列统一北京时间，类型 `TIMESTAMP`；用时/圈速秒数使用 `NUMERIC(10,2)`。

### 5.1 `torn_racing_race`（赛事主表）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGINT | PK | 主键ID |
| race_id | BIGINT | NOT NULL, UK | Torn 赛事ID |
| title | VARCHAR(64) | NOT NULL | 赛事名称快照 |
| business_date | DATE | NOT NULL | 业务日期（Torn 日） |
| start_time | TIMESTAMP | NOT NULL | 开赛时间（北京时间） |
| end_time | TIMESTAMP | NULL | 结束时间（北京时间） |
| status | VARCHAR(16) | NOT NULL | 抓取时赛事状态 |
| captured_time | TIMESTAMP | NOT NULL | 数据抓取完成时间（北京时间） |
| deleted / create_time / update_time | TINYINT / TIMESTAMP / TIMESTAMP | NOT NULL | 审计字段 |

索引：`uk_racing_race_id(race_id)`、`idx_racing_race_business_date(business_date)`。

### 5.2 `torn_racing_participant`（选手明细表）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGINT | PK | 主键ID |
| race_id | BIGINT | NOT NULL | Torn 赛事ID |
| user_id | BIGINT | NOT NULL | 选手 Torn 用户ID |
| nickname | VARCHAR(64) | NULL | 抓取时昵称快照（本地无记录的非家族选手为空） |
| faction_id | BIGINT | NULL | 抓取时帮派ID快照 |
| is_alliance | BOOLEAN | NOT NULL | 抓取时是否为家族选手 |
| position | INT | NULL | 赛事名次（API 原始值） |
| race_time | NUMERIC(10,2) | NULL | 完赛用时（秒），撞车为空 |
| best_lap_time | NUMERIC(10,2) | NULL | 最快圈用时（秒） |
| has_crashed | BOOLEAN | NOT NULL | 是否撞车 |
| deleted / create_time / update_time | TINYINT / TIMESTAMP / TIMESTAMP | NOT NULL | 审计字段 |

索引：

- `uk_racing_participant_race_user(race_id, user_id)` —— 用户明确要求的组合唯一索引。
- `idx_racing_participant_race_alliance(race_id, is_alliance)` —— 榜单/参赛率/最快圈筛选。
- `idx_racing_participant_user(user_id)` —— `PC成绩` 按人查询。

---

## 6. 文件级实施清单

### 6.1 渲染平台主题化（4 改 3 增）

#### 6.1.1 新增 `src/main/java/pn/torn/goldeneye/utils/image/document/TableThemeEnum.java`

```java
public enum TableThemeEnum {
    OC("oc-table", List.of("/table-image/table-base.css", "/table-image/oc-table.css")),
    PC_RACE("pc-race-table", List.of("/table-image/table-base.css", "/table-image/pc-race-table.css"));

    private final String documentType;
    private final List<String> cssResources;

    /** 按 documentType 解析主题；未注册时抛出 IllegalArgumentException（快速失败，不静默回退）。 */
    public static TableThemeEnum of(String documentType) { ... }
}
```

放在 `utils/image/document`（与 `TableDocument` 同层），避免业务包反向依赖渲染实现包。

#### 6.1.2 修改 `src/main/java/pn/torn/goldeneye/utils/image/document/TableCellStyleEnum.java`

新增 5 个**通用**（非赛车专有）语义样式，供所有未来主题复用：

| 常量 | CSS 类 | 用途 |
|---|---|---|
| `HEADER` | `.cell-header` | 表头行 |
| `BODY` | `.cell-body` | 普通数据行 |
| `RANK_FIRST` | `.cell-rank-first` | 第一名 |
| `RANK_SECOND` | `.cell-rank-second` | 第二名 |
| `RANK_THIRD` | `.cell-rank-third` | 第三名 |

注意：`styleClass()` 的穷尽 switch 位于 `HtmlTableMarkupRenderer`（非枚举内），新增枚举会**编译期强制**要求补 case，用于防止遗漏。

#### 6.1.3 修改 `src/main/java/pn/torn/goldeneye/utils/image/render/html/HtmlTableMarkupRenderer.java`

- 删除 `CSS_RESOURCE` 常量与 `private final String css`。
- 新增 `private final Map<TableThemeEnum, String> cssByTheme`，构造期按枚举逐主题拼接其 `cssResources`（顺序即层叠顺序）。
- `loadCss(String resource)` 参数化，资源缺失仍抛 `IllegalStateException`。
- `render` 内 `String css = cssByTheme.get(TableThemeEnum.of(document.documentType()))`。
- `styleClass` 增加 5 个 case。
- 保持无参构造（Spring 与现有测试直接 `new`，避免额外配置）。

#### 6.1.4 修改 `src/main/java/pn/torn/goldeneye/torn/service/faction/oc/image/OcTableDocumentAssembler.java`

- `DOCUMENT_TYPE` 常量改为 `TableThemeEnum.OC.getDocumentType()`，消除重复字面量。**除此之外不得改动本文件**。

#### 6.1.5 新增 `src/main/resources/table-image/table-base.css`

内容 = 现 `oc-table.css` 的**通用规则**（`*`、`html,body`、`body`、`.table-image`、`table`、`caption`、`td`、`.overflow-*`、`.cell-title`、`.cell-section`、`.cell-section-head`、`.cell-section-name`、`.cell-badge`、`.badge-*`、`.cell-footer`）+ 新增：

```css
.cell-header      { background: #1e1b4b; color: #ffffff; font-weight: 700; text-align: center; }
.cell-body        { background: #ffffff; color: #1f2937; text-align: left; }
.cell-rank-first  { background: #fef3c7; color: #92400e; font-weight: 700; }
.cell-rank-second { background: #f3f4f6; color: #374151; font-weight: 700; }
.cell-rank-third  { background: #ffedd5; color: #9a3412; font-weight: 700; }
```

#### 6.1.6 修改 `src/main/resources/table-image/oc-table.css`

仅保留 OC 专有规则（`.cell-team-*`、`.cell-slot-*`、`.slot-parts`、`.slot-part-*`、`.cell-member-*`、`.cell-current-*`），规则文本**逐字保留**（多选择器共享的规则组按**整组**迁移，不得拆散），不得改写、重排或格式化。

层叠等价说明：拆分后 OC 主题拼接为 `table-base.css + oc-table.css`，其规则行序与原文件**不完全相同**（`.cell-footer` 从原文件末尾移至基础层末尾），但通用层与 OC 专有层的选择器**没有任何属性冲突**，渲染结果与现状等价。此结论列为 Review 核对项，配合集成测试与人工比对。

#### 6.1.7 新增 `src/main/resources/table-image/pc-race-table.css`

仅放 PC 表格的版式差异，不复制通用规则（8 列从左至右；第 3 列昵称自适应，不指定宽度）：

```css
.table-image table td:nth-child(1) { width: 90px;  text-align: center; }                                                /* SMTH名次 */
.table-image table td:nth-child(2) { width: 120px; text-align: center; font-variant-numeric: tabular-nums; }            /* 选手ID */
.table-image table td:nth-child(4) { width: 120px; text-align: center; }                                                /* 帮派 */
.table-image table td:nth-child(5) { width: 150px; text-align: right;  font-variant-numeric: tabular-nums; }            /* 用时 */
.table-image table td:nth-child(6) { width: 130px; text-align: right;  font-variant-numeric: tabular-nums; }            /* 最快圈 */
.table-image table td:nth-child(7) { width: 90px;  text-align: center; }                                                /* 状态 */
.table-image table td:nth-child(8) { width: 110px; text-align: center; }                                                /* 全场名次 */
```

#### 6.1.8 修改测试（2 个）

- `HtmlTableMarkupRendererTest`：文档 `documentType` 由 `"test"` 改为 `TableThemeEnum.OC.getDocumentType()`；补一个"未注册主题快速失败"用例。
- `HtmlTableImageRendererIntegrationTest`：`documentType` 由 `"integration"` 改为 `TableThemeEnum.OC.getDocumentType()`。

### 6.2 常量与指令注册（3 改 1 增）

#### 6.2.1 新增 `src/main/java/pn/torn/goldeneye/constants/torn/RacingConstants.java`

| 常量 | 值 | 说明 |
|---|---|---|
| `RACE_TITLE` | `"SMTHPC"` | 赛事名称（忽略大小写匹配） |
| `RACE_FINISHED_STATUS` | `"finished"` | 结果可抓取的状态 |
| `RACE_CATEGORY_CUSTOM` | `"custom"` | `/user/races` 的 `cat` 参数值（自定义赛，排除官方赛） |
| `CREATOR_USER_ID` | `2554043L` | 常任创建人（P1 候选） |
| `DRAW_SEED_SUFFIX` | `"Ciallo"` | 抽奖种子后缀 |
| `NEWCOMER_DRAW_SEED_SUFFIX` | `"CialloNew"` | 新人奖种子后缀（与 `DRAW_SEED_SUFFIX` 不同，两次抽取相互独立） |
| `NEWCOMER_DAYS` | `120` | 新人奖注册时长阈值（天）：注册时间晚于「开赛时间 − 该天数」者具备资格 |
| `CAPTURE_TASK_ID` | `"pc-race-capture"` | 动态任务ID（自续期用） |
| `SCORE_HISTORY_LIMIT` | `10` | `PC成绩` 展示场次 |
| `DISCOVERY_PAGE_SIZE` | `50` | 定位时 `/user/races` 拉取条数（接口上限 100；cat=custom 过滤后 50 场冗余充分） |
| `DISCOVERY_SCAN_LIMIT` | `60` | 单次定位扫描候选 Key 上限 |

（`TORN_DAY_OFFSET_HOURS` 移至 `DateTimeUtils` 作全局常量，见 6.2.4；榜单无行数上限，全员展示。）

#### 6.2.2 修改 `constants/bot/BotCommands.java`

追加：

```java
// ====================PC赛车相关====================
/** PC赛车榜单查询 */
public static final String PC_RACE_RESULT = "PC结果";
/** PC赛车个人成绩查询 */
public static final String PC_RACE_SCORE = "PC成绩";
```

#### 6.2.3 修改 `constants/InitOrderConstants.java`

追加 `public static final int TORN_PC_RACE = 10010;`（晚于 `TORN_FACTION_DATA = 10003`，确保启动补抓时家族成员数据已就绪；以**类级 `@Order`** 注解生效，见 6.5.2）。

#### 6.2.4 修改 `utils/DateTimeUtils.java`

新增全局常量与通用方法（**不得改动 `getTornLocalDate()` 与 `convertToDateTime()` 的现有对外行为**）：

```java
/** Torn 日切换偏移小时数（Torn 日在 UTC 00:00 切换，即北京时间 08:00）。 */
public static final long TORN_DAY_OFFSET_HOURS = 8L;

/**
 * 将Torn时间戳转换为Torn业务日期（Torn日在UTC 00:00切换，即北京时间08:00）。
 *
 * @param timestamp 秒级或毫秒级Unix时间戳
 * @return Torn业务日期；timestamp为null时返回null
 */
public static LocalDate convertToTornDate(Long timestamp) {
    LocalDateTime dateTime = convertToDateTime(timestamp);
    return dateTime == null ? null : dateTime.minusHours(TORN_DAY_OFFSET_HOURS).toLocalDate();
}
```

`getTornLocalDate()` 内部的 8 点边界改为引用 `TORN_DAY_OFFSET_HOURS` 表达（行为不变）。

### 6.3 Torn API 模型（1 增 2 改，均在 `torn/model/user/racing/`）

| 文件 | 动作 | 内容 |
|---|---|---|
| `torn/model/user/racing/TornRaceResultVO.java` | 新增 | 成绩条目：`driver_id` / `position` / `race_time` / `best_lap_time` / `has_crashed`，逐字段 `@JsonProperty` 显式映射（项目未配置全局 snake_case 策略）；时间用 `BigDecimal`；报文中的 `car_*` / `time_ended` 字段不接入 |
| `torn/model/user/racing/TornRaceDetailVO.java` | 修改 | 新增 `private List<TornRaceResultVO> results;`（列表接口即返回全量成绩，真实报文已验证） |
| `torn/model/user/racing/TornUserRaceDTO.java` | 修改 | 新增 `private int limit = 1;` 与 `TornUserRaceDTO(int limit)`；`buildReqParam` 输出 `cat=RACE_CATEGORY_CUSTOM`、`sort=DESC`、`limit=字段值`；当前无活跃调用方，无参构造维持 limit=1 的请求形态 |

不再新增 `/racing/{raceId}/race` 详情接口的任何模型（列表直取，见 4.2）；若实现时发现列表 `results` 字段不完整，回落详情接口属后续预案（见第 10/12 章）。

### 6.4 持久层（6 增）

| 文件 | 内容 |
|---|---|
| `repository/model/racing/TornRacingRaceDO.java` | `@TableName("torn_racing_race")`，继承 `BaseDO`，字段与 5.1 一一对应，逐字段 Javadoc |
| `repository/model/racing/TornRacingParticipantDO.java` | `@TableName("torn_racing_participant")`，字段与 5.2 一一对应 |
| `repository/mapper/racing/TornRacingRaceMapper.java` | `@Mapper interface extends BaseMapper<TornRacingRaceDO>` |
| `repository/mapper/racing/TornRacingParticipantMapper.java` | 同上 |
| `repository/dao/racing/TornRacingRaceDAO.java` | `@Repository class extends ServiceImpl<...>` |
| `repository/dao/racing/TornRacingParticipantDAO.java` | 同上 |

约束：**不新增 Mapper XML**。所有查询用 MyBatis-Plus lambda 完成（榜单、SMTH 名次、参赛率、最快圈、按人历史）。单场赛事参赛行数量级为 10²，允许一次取回后在内存聚合，不构成 N+1。

### 6.5 服务层（7 增）

#### 6.5.1 `torn/service/racing/capture/PcRaceDiscoveryService.java`

```java
/** 按优先级候选链定位目标业务日期的赛事；命中时返回含全量成绩的赛事详情。 */
public TornRaceDetailVO findRace(LocalDate businessDate)
/** 构建保序去重的候选用户ID列表（截断到 DISCOVERY_SCAN_LIMIT）。包可见，便于测试。 */
List<Long> buildCandidateUserIds()
```

规则：

- 候选顺序：P1 创建人 → P2 最近一场已抓取赛事的参赛选手（主行按 `business_date` 降序取一场，选手按 `position` 升序、null 靠后）→ P3 `faction_id ∈ {FACTION_PN_ID, FACTION_CCRC_ID}` 的 Key 持有人 → P4 其余 Key 持有人；`distinct()` 保序。
- 逐个候选：`getKeyByUserId` 取 Key（null 跳过）→ `tornApi.sendRequest(new TornUserRaceDTO(DISCOVERY_PAGE_SIZE), key, TornUserRacesVO.class)` → 匹配 `RACE_TITLE`（忽略大小写）且 `DateTimeUtils.convertToTornDate(schedule.getStart())` 等于目标业务日期 → 命中即返回该 `TornRaceDetailVO`。
- 每个候选调用后 `finally { apiKeyConfig.returnKey(key); }` **必须存在**（含命中与异常路径），Key 不跨方法传递。
- 单候选异常 `catch (Exception)` 记 warn 后继续；不得向上抛出中断整条链。
- 依赖：`TornApi`、`TornApiKeyConfig`、`TornRacingRaceDAO`、`TornRacingParticipantDAO`、`TornConstants`。

#### 6.5.2 `torn/service/racing/capture/PcRaceCaptureService.java`

```java
// 类级注解：@Order(InitOrderConstants.TORN_PC_RACE)，启动顺序晚于 TORN_FACTION_DATA
@EventListener(ApplicationReadyEvent.class)
public void init()

/** 定时入口：抓取"昨日"赛事；finally 中自续期次日 08:30，失败不断链。 */
public void captureDaily()

/** 抓取指定业务日期的赛事；非事务编排，落库委托 PcRacePersistService。 */
public void capture(LocalDate businessDate)
```

规则：

- `init`：`!BotConstants.ENV_PROD.equals(projectProperty.getEnv())` 直接返回（dev/test 不抓取）；当前时间早于当日 08:30 → `addScheduleTask(当日 08:30)`；目标业务日期（`getTornLocalDate().minusDays(1)`）已有主行 → `addScheduleTask(次日 08:30)`；否则立即执行 `captureDaily()`（补抓，内部自续期）。
- `captureDaily`：`try { capture(DateTimeUtils.getTornLocalDate().minusDays(1)); } finally { addScheduleTask(LocalDate.now().plusDays(1).atTime(8, 30, 0)); }`。
- `addScheduleTask`：`taskService.updateTask(RacingConstants.CAPTURE_TASK_ID, this::captureDaily, execTime)`（对齐 `TornFactionDataService` 自续期模式）。
- `capture`：幂等检查（主行 `count() > 0` 即返回）→ `findRace` 定位 → 校验 `status == finished` 且 `results` 非空 → `persistService.save(...)`。
- 任何失败分支只记日志，不重试、不推送、不写部分数据；停机跨多日的缺口按缺失处理。
- 捕获 `DuplicateKeyException` 记日志后按已抓取处理。
- 依赖：`PcRaceDiscoveryService`、`PcRacePersistService`、`TornRacingRaceDAO`、`DynamicTaskService`、`ProjectProperty`。

#### 6.5.3 `torn/service/racing/capture/PcRacePersistService.java`

```java
/**
 * 在同一事务内写入一场赛事的主行与选手明细（纯插入，无删除语句）。
 *
 * @param race 赛事详情（含全部参赛成绩）
 * @param businessDate 赛事业务日期
 */
@Transactional(rollbackFor = Exception.class)
public void save(TornRaceDetailVO race, LocalDate businessDate)
```

规则：

- **纯插入**：不执行任何删除；并发重复由唯一索引兜底，`DuplicateKeyException` 由 `PcRaceCaptureService` 捕获处理（事务回滚，无部分写入）。
- 主行字段：raceId、title、businessDate、start/end 时间（`DateTimeUtils.convertToDateTime`）、status、capturedTime（`LocalDateTime.now()`，项目约定即北京时间）。
- 明细构建：
  1. 收集全部 `driver_id`，一次 `userDao.lambdaQuery().in(id, ids).list()` 得到 `Map`（避免 N+1）；
  2. `allianceFactionIds = factionManager.getIdMap().keySet()`；
  3. `isAlliance = user != null && allianceFactionIds.contains(user.getFactionId())`；`nickname`/`factionId` 取快照（未知为 null）；
  4. `hasCrashed = Boolean.TRUE.equals(result.getHasCrashed())`。
- `saveBatch` 写入明细。

#### 6.5.4 `torn/service/racing/query/PcRaceQueryService.java`

```java
/** 按业务日期构建榜单结果；无赛事时返回 null。 */
public PcRaceResultBO buildResultByBusinessDate(LocalDate businessDate)
/** 按赛事ID构建榜单结果；无赛事时返回 null。 */
public PcRaceResultBO buildResultByRaceId(long raceId)
/** 构建指定用户的近 SCORE_HISTORY_LIMIT 场家族赛事成绩。 */
public PcRaceScoreBO buildScore(long userId)
```

规则：

- 三个方法共用私有 `buildResult(TornRacingRaceDO race)`，**禁止重复榜单口径**。
- 一次取回该场全部明细，在内存完成：全员榜单（含撞车）、SMTH 名次（家族未撞车选手按 `position` 升序的序号 1..N，撞车为 null）、最快圈、Crash 名单、参赛率、抽奖池。
- 展示文本在此处一次性生成（`raceTimeText` = `HH:mm:ss.SS`，`bestLapTimeText` = `mm:ss.SS`，null → null），避免两个展示类各自实现格式化。
- `factionShortName` 由 `TornSettingFactionManager.getIdMap()` 查快照 `faction_id` 得到（命中缓存，无额外查询）。
- 抽奖委托 `PcRaceDrawCalculator`。
- `buildScore`：按 `user_id + is_alliance = true` 取最近 `SCORE_HISTORY_LIMIT` 条明细（`orderByDesc(raceId)`），再一次 `in(raceId)` 取主行与当轮全部家族明细（回算每场 SMTH 名次），按 `startTime` 降序组装。

#### 6.5.5 `torn/service/racing/query/PcRaceDrawCalculator.java`

```java
/**
 * 在未撞车选手中按 raceId 与固定盐做确定性抽取。
 *
 * @param raceId 赛事ID
 * @param pool 未撞车的全部家族选手（调用方保证顺序稳定）
 * @return 中奖选手；池为空时返回 null
 */
public PcRaceParticipantVO draw(long raceId, List<PcRaceParticipantVO> pool)
/** 在具备新人奖资格的家族选手中按 raceId 与新人奖固定盐做确定性抽取。 */
public PcRaceParticipantVO drawNewcomer(long raceId, List<PcRaceParticipantVO> pool)
```

实现：两个公开入口委托同一个私有 `draw(raceId, seedSuffix, pool)`，仅盐不同：
`new Random((raceId + seedSuffix).hashCode()).nextInt(pool.size())`。
`String.hashCode()` 由 JLS 规定，跨 JVM 稳定，保证"任何人任何时候重算结果一致"。
普通抽奖使用 `DRAW_SEED_SUFFIX`，新人奖使用 `NEWCOMER_DRAW_SEED_SUFFIX`；池为空时返回 `null`。

#### 6.5.6 `torn/service/racing/image/PcRaceDocumentAssembler.java`

```java
/** 将榜单结果组装为PC主题表格文档；不访问DAO、API与渲染器。 */
public TableDocument assemble(PcRaceResultBO result)
```

- 宽度 1600，`documentType = TableThemeEnum.PC_RACE.getDocumentType()`。
- **8 列**，从左至右列内容为用户指定顺序；列头提案如下（列头文案可微调，不阻断开工）：

| 序 | 内容 | 列头提案 | 说明 |
|---|---|---|---|
| 1 | SMTH名次 | `SMTH名次` | 家族未撞车内序号；撞车行显示"—" |
| 2 | 选手ID | `选手ID` | Torn userId |
| 3 | 选手昵称 | `昵称` | 抓取时昵称快照 |
| 4 | 帮派简称 | `帮派` | `factionShortName` |
| 5 | 用时 | `用时` | `HH:mm:ss.SS`；缺失显示"—" |
| 6 | 最快圈 | `最快圈` | `mm:ss.SS`；缺失显示"—" |
| 7 | 是否撞车 | `状态` | 撞车行显示"撞车"，其余留空 |
| 8 | 全场名次 | `全场名次` | API 原始 `position`；缺失显示"—" |

- 行结构：标题行（`TITLE`，colspan=8）→ 表头行（`HEADER`×8）→ 榜单行（未撞车按 SMTH 名次序，**前三整行** `RANK_FIRST`/`RANK_SECOND`/`RANK_THIRD`，其余 `BODY`；**撞车行置底**）→ 页脚行（`FOOTER`，colspan=8，展示抓取时间与家族参赛人数）。
- **图内不使用 emoji**（容器字体无 emoji 字形，会渲染成方块），撞车标记一律用文字。
- 榜单为空时输出一行"暂无成绩记录"，不得抛异常。

#### 6.5.7 `torn/service/racing/image/PcRaceTextAssembler.java`

```java
/** 组装榜单汇总文本（最快圈 / 参赛率 / Crash / 抽奖 / 新人奖）。 */
public String assembleSummary(PcRaceResultBO result)
/** 组装个人成绩文本。 */
public String assembleScore(PcRaceScoreBO score)
```

输出示例（半角标点，逐字实现；文案可后续微调）：

```text
🏁 SMTHPC 2026-01-05
最快圈：Foo(第3名) 00:26.45
参赛率：45/62 = 72.58%
💥 Crash：Bar(第12名)、Baz(未完赛)
🎲 抽奖：Qux(第7名)
🎁 新人奖：New(第12名)
```

新人池为空时固定输出 `🎁 新人奖：今天没有新人参赛`（不输出 `无`）。

```text
🏎 PC成绩 Foo(近10场)
01-05 比赛第3 SMTH第1 04:52:16
01-04 比赛第1 SMTH第1 04:51:56
01-03 撞车
```

（个人历史日期为 `MM-dd`、用时到秒；调整为 `yyyy-MM-dd` 或保留百分秒在实现期微调。）

无数据分支必须以文本明确表达（如"无""无数据"），不得输出 null 或空串。

### 6.6 业务模型（3 增，放 `torn/model/racing/view/`）

#### 6.6.1 `PcRaceParticipantVO`

```java
/**
 * PC赛车选手展示模型。
 *
 * @param userId 选手Torn用户ID
 * @param nickname 抓取时昵称快照
 * @param factionShortName 帮派简称
 * @param smthRank SMTH内部名次（家族未撞车内序号）；撞车为null
 * @param position 赛事名次（API原始值）
 * @param raceTimeText 完赛用时文本，撞车或缺失为null
 * @param bestLapTimeText 最快圈文本，缺失为null
 * @param crashed 是否撞车
 */
public record PcRaceParticipantVO(long userId, String nickname, String factionShortName,
                                  Integer smthRank, Integer position, String raceTimeText,
                                  String bestLapTimeText, boolean crashed) {}
```

#### 6.6.2 `PcRaceResultBO`

字段：`raceId`、`businessDate`、`startTime`、`capturedTime`、`participants`（**全员**，含撞车）、`fastestLap`、`crashedList`、`allianceCount`、`totalCount`、`allianceRate`（`BigDecimal`）、`drawWinner`、`newcomerWinner`（新人奖中奖选手；新人池为空时为 `null`）。

为**扁平展示模型**，不得承载 DO，也不得被直接写入数据库。

#### 6.6.3 `PcRaceScoreBO`

```java
public record PcRaceScoreBO(long userId, String nickname, List<Item> items) {
    /**
     * 单场成绩条目。
     *
     * @param businessDate 赛事业务日期
     * @param participant 该场展示数据（含当场SMTH名次）
     */
    public record Item(LocalDate businessDate, PcRaceParticipantVO participant) {}
}
```

### 6.7 群指令策略（2 增）

#### 6.7.1 `napcat/strategy/racing/PcRaceResultStrategyImpl.java`

- 继承 `SmthMsgStrategy`（`getRoleType()` 为 null，公开可用）。
- `getCommand() = BotCommands.PC_RACE_RESULT`。
- 参数解析（**禁止嵌套三元**）：

| 参数 | 目标 |
|---|---|
| 空白 | `DateTimeUtils.getTornLocalDate().minusDays(1)` |
| 匹配 `yyyy-MM-dd` | 该业务日期 |
| 纯数字（可解析为 long） | raceId |
| 其他（含超出 long 范围的数字） | `buildTextMsg("参数有误")` |

- 命中：`PcRaceQueryService` → `PcRaceDocumentAssembler` → `TableImageRenderer.render` → 返回 `List.of(ImageQqMsg.fromBase64(...), TextQqMsg(汇总文本))`。
- 未命中：`buildTextMsg` 提示"未查询到SMTHPC赛事数据，可能尚未抓取"。
- 渲染异常无需策略层捕获：`HtmlTableImageRenderer.render` 已统一将非 `BizException` 异常包装为 `BizException`，由消息处理器转为文本回复。

#### 6.7.2 `napcat/strategy/racing/PcRaceScoreStrategyImpl.java`

- 继承 `SmthMsgStrategy`；`getCommand() = BotCommands.PC_RACE_SCORE`；`supportsAtUserTarget()` 覆写为 `true`。
- 目标用户复用基类 `getTornUser(sender, msg)`（支持 `@` / 数字ID / 默认本人），**禁止自行实现用户解析**。
- 无记录返回"暂无赛车成绩记录"。

### 6.8 注册时间修复与补齐（本次增量）

`torn_user.register_time` 长期为空，导致新人奖无法判定。根因与补齐口径如下。

#### 6.8.1 根因：`signed_up` 缺少 `@JsonProperty`

`TornUserProfileVO.signedUp` 缺少 `@JsonProperty("signed_up")`，而 `JsonUtils` 的 `ObjectMapper` 为默认 camelCase 命名策略（`FAIL_ON_UNKNOWN_PROPERTIES=false`），Torn 返回的 `signed_up` 被静默丢弃，`registerTime` 恒为 null。

- 同文件 `factionId`（`faction_id`）、`lastAction`（`last_action`）与 `TornUserStatusVO.planeImageType`（`plane_image_type`）均显式标注；项目约定即「每个多词 snake_case 字段必须显式 `@JsonProperty`」。
- 证据：2026-06-18 引入该字段后绑定 Key 的 104 个用户 `register_time` 全部为空；`torn_user` 1256 行中仅 4 行为 2025-08-04 首批导入自带数据。
- 口径确认：Torn v2 的 `signed_up` 属 `profile` 选择集（`UserProfileResponse.profile.signed_up`，秒级时间戳），basic 选择集不含该字段。

#### 6.8.2 修改清单

| 文件 | 动作 |
|---|---|
| `torn/model/user/profile/TornUserProfileVO.java` | `signedUp` 补 `@JsonProperty("signed_up")` |
| `repository/dao/user/TornUserDAO.java` | 新增 `queryIdListWithoutRegisterTime(Collection<Long>)` 与 `updateRegisterTimeIfAbsent(long, LocalDateTime)` |
| `torn/service/user/TornUserService.java` | 新增 `backfillRegisterTime(Collection<Long>)`：只对缺失用户调 `/user/{id}/profile` 并回写；单用户失败只记 warn，不阻断其余用户 |
| `torn/service/data/TornFactionDataService.java` | `spiderFactionMember` 成员落库后对该帮派成员调用补齐（**长期补齐入口**） |
| `torn/service/data/RegisterTimeBootstrapBackfill.java` | **新增临时组件**：启动时对「在族且注册时间为空」的用户做一次性补齐 |
| `constants/InitOrderConstants.java` | 新增 `TORN_USER_REGISTER_BACKFILL = 10010`，并将 `TORN_PC_RACE` 调整为 `10011`（补齐必须早于赛事补抓，见 6.8.4） |

#### 6.8.3 补齐范围与口径

- 只补**在族成员**：每日抓取按该帮派成员名单补齐；启动一次性补齐只取 `faction_id <> 0` 且 `register_time IS NULL` 的用户。
- **离族成员不补齐**（用户已确认）：`is_alliance` 为抓取时快照，赛后离族者其当场新人奖将无法判定，列为已知限制。
- 条件回写：`updateRegisterTimeIfAbsent` 带 `register_time IS NULL` 条件，避免覆盖并发写入。
- **不落快照**：新人判定在查询时实时读取 `torn_user.register_time`，不新增 `torn_racing_participant` 列（用户已确认）。
- 调用量与限速：`/user/{id}/profile` 仅需 public 权限；`TornApiKeyConfig.getEnableKey()` 按 `use_count` 选最空闲 Key，约 965 次请求均摊至 400+ Key，单 Key 每分钟远低于 100 次上限。
- **不改已应用的 Liquibase changelog**（`racing.yaml` 的 `remarks` 保留原措辞，避免 checksum 校验失败）。

#### 6.8.4 临时组件生命周期（必须执行）

`RegisterTimeBootstrapBackfill` 与 `InitOrderConstants.TORN_USER_REGISTER_BACKFILL` 是**临时组件**：

1. 仅在首次部署（1.6.3 上线）补齐历史缺失数据；
2. 生产确认在族成员 `register_time` 已补齐后，**必须删除该组件与对应 `InitOrderConstants` 常量**；
3. 删除后日常补齐仍由 6.8.2 的每日成员抓取入口承担，能力不缺失。

**启动顺序约束（P1）**：`TORN_USER_REGISTER_BACKFILL(10010)` 必须早于 `TORN_PC_RACE(10011)`。

- 理由：奖项在**查询时**计算（抓取不落任何中奖结果），而新人池依赖实时的 `torn_user.register_time`。`ApplicationReadyEvent` 由 `SimpleApplicationEventMulticaster` **同步按 `@Order` 串行派发**（未配置 taskExecutor），因此若先补抓赛事、后补注册时间，两步之间的 `PC结果` 查询会输出「今天没有新人参赛」，补齐后同一场再查又变正确，造成同一战报前后不一致。
- 10002–10009 已被占用，无法在 `TORN_FACTION_DATA` 之前插入，故采用与 `TORN_PC_RACE` 对调的方式。
- 生产日常路径无此问题：08:10 成员抓取（内含补齐）早于 08:30 赛事抓取。

---

## 7. 关键算法

### 7.1 业务日期

```text
businessDate(startTime) = startTime.minusHours(8).toLocalDate()
```

北京 D 日 00:30 → 业务日期 D-1；08:30 抓取时 `getTornLocalDate() = D`，默认查询日 `D-1`，二者一致。

### 7.2 定位伪代码

```text
candidates = distinct(P1 + P2 + P3 + P4).take(60)
for userId in candidates:
    key = apiKeyConfig.getKeyByUserId(userId)
    if key == null: continue
    try:
        resp = tornApi.sendRequest(TornUserRaceDTO(50), key, TornUserRacesVO)   # cat=custom&sort=DESC&limit=50
        for race in resp.races:
            if race.title equalsIgnoreCase "SMTHPC"
               and convertToTornDate(race.schedule.start) == businessDate:
                return race                    # 命中即返回完整赛事（含全量 results）
    catch e: log.warn
    finally: apiKeyConfig.returnKey(key)
return null
```

### 7.3 抽奖

```text
# 普通抽奖
pool   = 全部未撞车家族选手（按名次升序）   # 与图片展示行一致；顺序稳定，禁止使用无序集合
seed   = (raceId + "Ciallo").hashCode()
winner = pool[new Random(seed).nextInt(pool.size())]

# 新人奖
threshold = raceStartTime - NEWCOMER_DAYS(120) 天
pool      = 全部家族选手（含撞车，按榜单顺序）中 register_time > threshold 者
seed      = (raceId + "CialloNew").hashCode()
winner    = pool[new Random(seed).nextInt(pool.size())]   # 池为空返回 null
```

两次抽取使用**不同盐**，互不影响，且都只依赖「赛事ID + 池顺序」，满足可复现要求。

---

## 8. 测试方案（收敛）

### 8.1 新增测试（7 个，每个 3–6 个用例）

| 测试类 | 覆盖的**唯一**主证据 |
|---|---|
| `PcRaceDiscoveryServiceTest` | 候选链顺序与截断；命中即停（返回完整 VO）；全部未命中返回 null；单候选异常不中断；**每个候选的 Key 必被归还（含命中与异常路径）**；请求参数含 cat=custom |
| `PcRaceCaptureServiceTest` | 已有主行跳过；`status != finished` 不落库；正常路径调用一次落库；非 prod 不抓取；失败后仍自续期次日 |
| `PcRacePersistServiceTest` | 家族判定与快照；撞车/用时映射；纯插入（无删除调用） |
| `PcRaceQueryServiceTest` | SMTH 名次口径（家族未撞车内序）；全员榜单（含撞车置底）；最快圈（排除空圈速）；参赛率（含撞车）；抽奖接入；**新人奖池含撞车且按阈值排除** |
| `PcRaceDrawCalculatorTest` | 同种子结果稳定；池顺序影响结果；空池返回 null；**新人奖同赛事结果恒定** |
| `PcRaceDocumentAssemblerTest` | 8 列行结构（标题/表头/榜单/页脚）；前三（按 SMTH 名次）样式；撞车行置底与"—"；空榜单分支 |
| `PcRaceResultStrategyImplTest` + `PcRaceScoreStrategyImplTest` | 参数解析（默认/日期/raceId/非法含超 long）；消息组装；无数据分支 |

（上表最后一行为 2 个测试类，总数为 8。）

本次增量新增 2 个测试类（不属赛车功能，独立列出）：

| 测试类 | 覆盖的**唯一**主证据 |
|---|---|
| `TornUserProfileVOTest` | 真实 `profile` 报文经 `JsonUtils` 解析后 `signed_up` → `registerTime` 映射成立（锁定 6.8.1 根因） |
| `TornUserServiceTest` | 注册时间补齐：无缺失时不发请求；只为缺失用户请求并回写；单用户失败不阻断其余用户 |

### 8.2 修改测试（2 个）

`HtmlTableMarkupRendererTest`、`HtmlTableImageRendererIntegrationTest`：`documentType` 改为已注册主题；前者补"未注册主题快速失败"。

### 8.3 明确不做的测试

- 不新增 DAO/Mapper 的 `shared-db` 集成测试（无 XML、无复杂 SQL，口径由 Service 层测试保护）。
- 不做图片像素级断言（Chromium 跨平台渲染差异见第 10 章）。
- 不测试 getter/setter、Spring 框架行为、私有方法。
- 不为 `PcRaceTextAssembler` 单独建测试类，其输出由两个指令策略测试覆盖。
- 不重复同一口径在多层测试矩阵中验证。

### 8.4 必跑命令

```text
mvn -q -DskipTests compile
mvn -q test -Dtest='PcRace*Test,HtmlTableMarkupRendererTest,TornUserServiceTest,TornUserProfileVOTest'
TABLE_IMAGE_RENDER_INTEGRATION=true mvn -q test -Dtest=HtmlTableImageRendererIntegrationTest
```

---

## 9. 验证与验收步骤

1. 本地启动，确认 Chromium 正常（OC 指令出图不回归）。
2. 手工触发 `capture(LocalDate)`（临时用测试触发），确认 `torn_racing_race` 与 `torn_racing_participant` 行数与赛事一致。
3. 重复触发一次，确认幂等检查直接返回、**不产生重复行**。
4. 群内执行 `PC结果`，确认：图片为家族**全员 8 列**榜单、前三（按 SMTH 名次）高亮、撞车行置底；文本含最快圈/参赛率/Crash/抽奖。
5. 群内执行 `PC成绩`（无参、数字ID、`@某人`）三种形态。
6. 断言抽奖：同一 raceId 重复执行结果一致。
7. 断网/无 Key 场景下确认只有日志、无异常外溢、无部分写入。
8. 确认在族成员注册时间已补齐：`torn_user` 中 `faction_id <> 0 AND register_time IS NULL` 为 0 行。
9. 群内执行 `PC结果`，确认文本新增 `🎁 新人奖` 行；构造无符合条件选手的赛事确认输出「今天没有新人参赛」。
10. 同一 raceId 重复执行，确认新人奖结果一致，且与普通抽奖互不影响。
11. **补齐完成后删除临时组件** `RegisterTimeBootstrapBackfill` 与 `InitOrderConstants.TORN_USER_REGISTER_BACKFILL`（见 6.8.4）。

---

## 10. 风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| `getKeyByUserId` 未归还导致 Key 池泄漏 | P0 | 每候选 `finally` 归还（含命中与异常路径），Key 不跨方法传递；Review 逐点核对 |
| 落库部分写入 | P0 | 单事务纯插入；事务由外部 Bean 调用；`DuplicateKeyException` 回滚兜底 |
| 定位链调用过多消耗 Key 配额 | P1 | 扫描上限 60；命中即停；cat=custom 缩小时间窗；P2 动态收敛候选 |
| `/user/races` 列表 `results` 字段被截断/不完整 | P2 | 真实报文已验证全字段；实现时以真实响应核对，不完整则回落 `/racing/{raceId}/race` 详情接口（后续预案，本批不实现） |
| 08:10 成员刷新延迟导致 `is_alliance` 误判 | P2 | 抓取时间取 08:30；误差仅限当日换帮派者，记录为已知限制 |
| 服务 08:30 未运行导致当天无数据 | P1 | 动态任务启动补抓；失败 `finally` 自续期；停机跨多日缺口按缺失处理（用户已确认） |
| CSS 拆分导致 OC 图片回归 | P1 | 规则组整组迁移、规则逐字保留；拼接行序变化处（`.cell-footer`）无属性冲突，渲染等价（见 6.1.6）；跑集成测试并人工比对 |
| 容器与本地 Windows 渲染差异（字体/Emoji/滚动条） | P2 | 本方案图内不使用 emoji；其余不处理，记录为后续建议 |
| `torn_racing_participant` 增长（每日约 60–100 行） | P3 | 三年约 10 万行，当前无需分表 |

---

## 11. Review 与验收清单

结构与规范：

- [ ] 新增/修改文件与第 6 章一致，无计划外改动。
- [ ] 包规划符合第 4/6 章，无单包文件堆积。
- [ ] 无重复实现：SMTH 名次/榜单/参赛率/最快圈口径只存在于 `PcRaceQueryService` 一处；展示格式化只有一处。
- [ ] 无硬编码魔法值：赛事名、创建人、盐、cat 参数、任务ID、页大小、扫描上限均来自 `RacingConstants`；Torn 日偏移来自 `DateTimeUtils.TORN_DAY_OFFSET_HOURS`；帮派不硬编码。
- [ ] 公开类型/方法/字段/record 组件具备符合 `.ai/knowledge/java_coding_style.md` 的 Javadoc。
- [ ] Liquibase 表与全部字段均有 `remarks`，与 DO、约束、默认值一致。

功能：

- [ ] `business_date` 按 Torn 日计算，默认查询为"当前 Torn 日 - 1"，未抓取不回退。
- [ ] 落库为全部参赛选手，查询仅取家族选手，`is_alliance`/`nickname`/`faction_id` 为快照。
- [ ] `(race_id, user_id)` 唯一索引存在且生效；重复抓取不产生重复行；`save` 无删除语句（纯插入）。
- [ ] SMTH 名次 = 家族未撞车选手内按原始 `position` 的序号；撞车行置底、名次列"—"。
- [ ] 图片为全员 8 列；前三按 SMTH 名次高亮；最快圈/参赛率/Crash/抽奖仅在文本；图内无 emoji。
- [ ] 抽奖种子为 `raceId + "Ciallo"`，池为全部未撞车家族选手，结果可复现。
- [ ] 新人奖池 = 全部家族选手中（**含撞车**）`register_time > 开赛时间 − NEWCOMER_DAYS` 者；基准取开赛时间而非查询时刻；`register_time` 为空者不入池。
- [ ] 新人奖种子为 `raceId + "CialloNew"`，与普通抽奖独立，允许同一人重复中奖。
- [ ] 新人奖只出现在文本汇总；新人池为空时固定输出「今天没有新人参赛」。
- [ ] `signedUp` 已显式标注 `@JsonProperty("signed_up")`，并由 `TornUserProfileVOTest` 锁定。
- [ ] 注册时间补齐只覆盖在族成员，回写带 `register_time IS NULL` 条件，单用户失败不阻断。
- [ ] 临时启动补齐组件已按 6.8.4 在补齐完成后删除。
- [ ] 表格与文案中 SMTH 的称呼统一为「家族」，表格页脚为「家族参赛人数：」。
- [ ] 主题按 `documentType` 选择；未注册类型快速失败；CSS 拆分层叠等价（`.cell-footer` 迁移无冲突）。
- [ ] 抓取失败只记日志，无重试、无推送、无部分写入；动态任务失败自续期次日。
- [ ] 非 prod 环境不抓取（`init` 门禁）。
- [ ] 无自动推送行为（不调用 `BotReplyService`/`Bot` 主动发送）。
- [ ] 未读取或打印 `torn_api_key.api_key`（候选用户ID取自内存 Key 池）。

测试：

- [ ] 第 8 章测试全部通过；未新增表外测试。
- [ ] 明确说明未执行的范围（如全量回归）。

---

## 12. 明确不做

- 不做赛事自动推送（用户已确认）。
- 不实现"幸运抽奖 N 人"（参考脚本的第二种抽奖）；如后续需要，复用 `PcRaceDrawCalculator` 扩展参数。
- 不实现赛前报名、赛事创建、赛程提醒。
- 不做赛事的**历史数据回填**；**停机跨多日的缺口不回补**（用户已确认）。
- 不为**离族成员**补齐注册时间（用户已确认）；由此导致的当场新人奖缺判列为已知限制。
- 不把 `register_time` 快照进 `torn_racing_participant`，也不为此改表结构（用户已确认）。
- 不修改**已应用**的 Liquibase changelog（`1.6.3/racing.yaml` 的 `remarks` 保留原措辞，避免 checksum 校验失败）。
- 不为其他子系统批量改写「联盟 / 帮派」措辞，本次术语变更仅限 SMTHPC 赛车功能与其技术方案文档。
- **不提供重抓/修数入口**：数据修正依靠手工 SQL（用户已确认）。
- 列表 `results` 不完整时的详情接口回落为后续预案，本批不实现。
- 不改造 OC 之外的既有表格渲染调用方；不删除 `TableImageUtils`。
- 不处理 Chromium 跨平台渲染差异（字体、Emoji、滚动条）。
- 不引入开关配置项（`sys_setting`）；抓取为只读且每日一次。
- 不新增依赖。
- 不修改 `pom.xml` 与 `build/docker-compose.yml` 的版本标识（由用户另行处理）。

---

## 13. 已确认决策记录（2026.09.15 讨论定稿）

1. P1 常任创建人固定 `2554043`：真实报文证实其本人参赛（position 1），Key 不可用自动降级。
2. 赛事名称匹配：忽略大小写精确匹配 `SMTHPC`（报文 `title` 字段一致）。
3. `PC成绩`：近 10 场、仅家族赛事（快照口径）；历史行含当场 SMTH 名次。
4. 榜单展示家族**全员**（含撞车），双名次列（SMTH 名次 + 全场名次）——取代早期"前 30 名"方案。
5. 抽奖保留 1 人；池为全部未撞车家族选手。
6. 表名 `torn_racing_race` / `torn_racing_participant` 可接受。
7. 启动补偿保留（动态任务 `init` 补抓，仅 prod）。
8. 抓取链路列表直取：`/user/races` 自带全量 `results`，不调详情接口。
9. 调度走 `DynamicTaskService` 一次性任务 + 自续期（弃 `@Scheduled`）。
10. 落库纯插入（幂等 = 业务日期检查 + 唯一索引 + `DuplicateKeyException` 兜底）。
11. 定位请求参数：`cat=custom`、`sort=DESC`、`limit=50`（接口上限 100）。
12. `TORN_DAY_OFFSET_HOURS` 为 `DateTimeUtils` 全局常量。
13. 列头文案（6.5.6 提案）与消息文案（6.5.7 示例）可在实现期微调，不阻断开工。

本次增量（2026.09.15 讨论定稿）：

14. 新增「新人奖」：注册时间 < 120 天具备资格；池**含撞车选手**；与普通抽奖相互独立，**允许同一人重复中奖**。
15. 新人判定基准取**该场赛事开赛时间**（非查询时刻），保证同一赛事结果可复现。
16. 新人池为空时文本固定输出「今天没有新人参赛」。
17. 注册时间补齐**只覆盖在族成员**，离族成员不补。
18. 新人判定在查询时**实时读取 `torn_user`**，不落快照、不改表结构。
19. 启动一次性补齐为**临时组件**，生产补齐完成后必须删除。
20. 表格与文案中 SMTH 的称呼由「联盟」统一改为「家族」。

---

## 14. 参考文件

- `race copy.py`（业务口径来源，外部脚本，不在仓库内）
- `/user/races` 真实响应报文（2026.09.15 用户提供，`limit=20&cat=custom`，含全量 `results`）
- `src/main/java/pn/torn/goldeneye/utils/image/render/html/HtmlTableMarkupRenderer.java`
- `src/main/java/pn/torn/goldeneye/utils/image/render/html/HtmlTableImageRenderer.java`
- `src/main/java/pn/torn/goldeneye/utils/image/render/html/PlaywrightBrowserManager.java`
- `src/main/java/pn/torn/goldeneye/utils/image/document/TableDocument.java`
- `src/main/java/pn/torn/goldeneye/torn/service/faction/oc/image/OcTableDocumentAssembler.java`
- `src/main/java/pn/torn/goldeneye/torn/manager/faction/crime/msg/TornFactionOcMsgManager.java`
- `src/main/java/pn/torn/goldeneye/torn/service/data/TornFactionDataService.java`（动态任务 + 自续期 + 启动补偿样板）
- `src/main/java/pn/torn/goldeneye/configuration/DynamicTaskService.java`
- `src/main/java/pn/torn/goldeneye/configuration/TornApiKeyConfig.java`
- `src/main/java/pn/torn/goldeneye/napcat/strategy/base/BaseMsgStrategy.java`
- `src/main/java/pn/torn/goldeneye/utils/DateTimeUtils.java`
- `.ai/knowledge/java_coding_style.md`
- `.ai/knowledge/table-image-rendering-1.6.0-technical-design.md`
