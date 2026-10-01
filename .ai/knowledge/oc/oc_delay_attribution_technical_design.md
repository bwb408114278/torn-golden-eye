# OC 延误归因（delay_cause）技术方案

## 元信息

- 文档类型：技术方案 知识库
- 适用项目：Golden-Eye
- 适用版本：1.6.6
- 最后更新：2026.09.30
- 维护人：Bai
- 状态：有效（待实施，同时作为实施后的验收与 Review 基线）

---

## 1. 需求

### 1.1 业务需求

OC 完成通知目前只播报「某个 OC 延误了多少分钟」，指挥官无法知道**是谁、因为什么**把 OC 拖住了。

本方案为完成通知里的延误段落补上**成员维度归因**：列出造成延误的成员（昵称+ID）、原因、各自让 OC 多等了多久，用于追责与罚款。

### 1.2 交付边界

- 只在**现有的 OC 完成通知**内追加原因信息，不新增群消息、不新增指令、不新增定时任务。
- 不改变现有「明显延误」判定（阈值 5 分钟）、不改变现有文案行与 @ 行为。
- 不做因果推断（不判断「谁先走导致谁跟着走」），只输出可观测事实。

---

## 2. 业务口径（实现必须唯一遵循）

### 2.1 原因集合与优先级

原因只有 4 类，展示名固定：`旅行`、`缺道具`、`住院`、`监狱`。

单次采样对单个成员，按以下顺序取**第一个命中**：

| 顺序 | 判定 | 结果 |
|---|---|---|
| 1 | `status.state` 为 `Traveling` 或 `Abroad` | 旅行 |
| 2 | `status.state` 为 `Hospital` 且 `status.description` 含旅行目的地 | **旅行**（海外住院） |
| 3 | `status.state` 为 `Hospital` | 住院 |
| 4 | `status.state` 为 `Jail` | 监狱 |
| 5 | `requiredItemId != null && requiredItemAvailable == false` | 缺道具 |
| 6 | 以上都不命中 | **不记录该成员** |

补充规则：

- **状态优先、缺道具补充**：成员同时命中状态类与缺道具时，主原因取状态，道具 ID 另存，展示为补充信息。
- **旅行不分子态**：起飞、滞留、返回统一记为「旅行」，不做文本方向解析。
- **海外住院归旅行**：帮战期间不会主动派人出国，因此海外住院的责任性质与主动出行一致；用 `TornTravelTargetEnum.textContain(description)` 判定目的地，不需新增枚举。
- **其余状态不归类**：`Federal`/`Awoken`/`Dormant`/`Fallen` 在本业务中不会出现在 OC 岗位上（成员一旦进入这些状态会被移出 OC），不为其新增枚举，统一落到「不记录」。
- **原因与补充道具均取首次观测值**，同一成员在后续采样中即使原因变化也不覆盖。

### 2.2 阻塞判定来源

| 输入 | 来源 |
|---|---|
| 成员状态 | 帮派成员接口（`GET /faction/{id}/members`，项目已有 `TornFactionMemberDTO`） |
| 缺道具 | `torn_faction_oc_slot` 的 `required_item_id` / `required_item_available`（OC 同步链每轮已写入） |

### 2.3 采样节奏

- **复用** OC 完成检测循环（`checkOcCompleted`，每分钟一轮），**不新增定时任务**。
- 采样节流：**同一帮派每 5 分钟最多采一次**。
- 采样范围：本轮仍为 `pending` 的 OC，且**该 OC 的计划执行分钟已经过去至少 1 分钟**。
  - 计划执行分钟 = `OcPreparationTimeCalculator.calculatePlannedTime(readyTime)`（复用，禁止重写 `plusMinutes(1)`）。
  - 加 1 分钟是"确认 Torn 本轮定时窗口已经错过"的判据，避免把 Torn 自身的执行排期误判成有人阻塞。
- 采样内容：该帮派待检 OC 的全部槽位成员状态 **一次接口调用覆盖所有 OC**，不按 OC 逐个调用。

### 2.4 时长口径（净阻塞累计）

- **段**：成员从"首次被观测到阻塞"到"首次被观测到解除"为一段；采样间隔内阻塞→解除的短促阻塞可能整段漏采，属于 5 分钟粒度的既定代价。
- **净阻塞累计** = 该成员所有段长度之和。
- **最后一段以 `executed_time` 封口**：OC 只有在全员可用时才会执行，因此最后仍处于阻塞的成员，其解除时刻就是 `executed_time`。
- **分钟口径**：一切按**分钟桶**计算（时刻截断到分钟后相减），与现有播报「延误约 X 分钟」保持一致。采样点固定落在每分钟的第 30 秒附近，桶差稳定为 5 的倍数。
- **最终阻塞者** = 净阻塞累计最大者；并列全部标记。

### 2.5 展示口径

在现有延误明细行下追加，每个成员一行、**按净阻塞累计降序**：

```text
以下OC完成时存在明显延误，请关注：

#8 Clinical Precision：计划17:00完成，实际18:56完成，延误约116分钟
原因：李四[2975823] 旅行(延误约115分钟)
张三[2455214] 缺道具(Reaper's Key，延误约60分钟)
王五[1111111] 监狱(另缺 Reaper's Key，延误约30分钟，最终阻塞)
```

- 成员格式：`昵称[ID]`，不 @ 人。
- 原因格式：`主原因(补充道具，延误约N分钟，最终阻塞)`；主原因为缺道具时道具名直接写在括号内；`最终阻塞` 仅对净阻塞累计最大者输出。
- **一条阻塞都没有时**输出：`原因：未知`。
- 不设条数上限。

---

## 3. 现状与证据

### 3.1 现有实现位置

| 关注点 | 位置 |
|---|---|
| 延误判定与提醒 | `torn/service/faction/oc/TornOcCompleteNoticeService.java`（`calculateDelayMinutes`、`buildDelayNotice`、`buildDelayDetail`） |
| 完成检测轮询 | 同文件 `scheduleOcCompleteCheck` / `checkOcCompleted` |
| 计划执行时间公式 | `torn/service/faction/oc/OcPreparationTimeCalculator.java`（唯一来源） |
| 缺道具快照 | `repository/model/faction/oc/TornFactionOcSlotDO.java` |
| 成员状态解析 | 同文件 `buildStatusWarnings` / `findBadStatusMap`（当前只用于"即将结束"预告） |

### 3.2 实测证据（本地库同步生产数据）

1. **Torn 执行秒数逐月漂移**：12062 条已完成 OC 的 `exec_second` 众数 2025-09 为 5 秒 → 2026-09 为 10 秒（约 +0.4 秒/月），当前 p90 为 13 秒。
   → 任何"plannned + 固定秒数"的采样阈值都会随月份失效，采样判据必须按 §2.3 用"计划分钟已过去"来表达。
2. **延误是常态且量级很大**：近 30 天 1062 个完成 OC 中 212 个（20%）延误 >5 分钟，延误分钟合计 42575，平均 200.8 分钟，最大 4783 分钟。
3. **延误不来自"晚加入"**：由 §3.3 的结构性事实保证，不需要额外判据。
4. **成员接口报文**：99 人约 58KB；`status.until` 仅住院/监狱有值，旅行恒为 `null`，因此本方案不依赖 `until`。
5. **成员状态与 OC 的对应关系**：快照中 14 个非 `Okay` 成员 14/14 均挂在未完成 OC 的岗位上，其中 3 个 OC 已是满员 `Planning`。
6. **现有播报会低估真实等待**：播报值 = `executed` 截断到分钟 −（`readyTime` 截断到分钟 + 1 分钟），真实等待比播报值最多再大 2 分钟。本方案的成员时长是**绝对净阻塞**，不与计划时刻相减，因此不受该偏差影响。

### 3.3 结构性事实：为什么延误不可能来自"晚加入"

Torn 的准备阶段递推为：

```text
无人加入：readyTime = null
首位成员于 T1 加入：R1 = T1 + 24h
后续成员于 Tn 加入：Rn = max(Tn, Rn-1) + 24h
```

由 `Rn >= Tn` 可知 **任何成员的加入时间恒不晚于 readyTime**，因此 `max(join_time) > ready_time` 在结构上不可能出现。它保证了：满员 OC 一旦越过 readyTime 仍未执行，只可能由"成员不可用或进程停转"造成，正是本方案要归因的对象。

（该口径与 `.ai/knowledge/oc/oc-new-team-final-design.md` 第 5 节、`.ai/knowledge/oc/oc-new-team-technical-design.md` 第 2.2 节一致。）

---

## 4. 风险等级

**L2（普通业务修改）**。

依据：改动为通知文案 + 局部持久化 + 一次向后兼容的可空加列，不涉及金额、资金守恒、权限、并发状态机或不可逆流转；`delay_cause` 为新增派生列，既有读写路径不读取它。

SQL/Schema 变更按 L2 要求用真实库行为验证（迁移可回滚、加列后既有查询不受影响）。

---

## 5. 方案设计

### 5.1 数据流

```text
每分钟 完成检测(checkOcCompleted)
  ├─ refreshOc 同步 Torn
  ├─ 查本批 OC → 分离 pending / completed
  ├─ pending 且节流允许
  │    ├─ 查这批 OC 的 slots（一次）
  │    ├─ 查本帮派成员状态（一次接口，覆盖全部 OC）
  │    └─ 逐 OC 归因合并 → 有变化才 UPDATE torn_faction_oc.delay_cause
  ├─ completed
  │    ├─ 用 executed_time 封闭未闭合段 → UPDATE 一次
  │    └─ 发送完成通知（读取 delay_cause 渲染原因行）
  └─ pending 非空 → 重新调度下一分钟
```

### 5.2 采样与段结算算法

```text
采样时刻 T（分钟桶 t）：
  对每个成员 u：
    命中原因 → blocked = true，否则 blocked = false
    e = 已记录条目[u]
    if blocked:
        if e 不存在:            记录 (u, 原因, 道具, segmentStart = t, accumulated = 0)
        else if e 无开放段:     只把 segmentStart 置为 t（原因保持首次值）
        else:                   继续当前段，不做任何修改
    else:
        if e 存在且 e 有开放段: accumulated += (t - e.segmentStart); segmentStart = 0

完成时刻 E（分钟桶 e）：
  对所有仍有开放段的条目: accumulated += (e - segmentStart); segmentStart = 0
```

### 5.3 存储编码

`torn_faction_oc.delay_cause`，`TEXT`，可空。每个成员一个条目，条目用 `;` 分隔，字段用 `|` 分隔：

```text
userId|reason|itemId|segmentStartMinute|accumulatedMinutes
```

| 字段 | 说明 |
|---|---|
| `userId` | 成员 ID |
| `reason` | `TRAVEL` / `HOSPITAL` / `JAIL` / `ITEM` |
| `itemId` | 该成员同时缺失的道具 ID；无则 `0` |
| `segmentStartMinute` | 当前未闭合段的起点（epoch 分钟）；`0` 表示当前未阻塞 |
| `accumulatedMinutes` | 已闭合段的净阻塞累计分钟 |

示例（已完成结算）：

```text
2975823|TRAVEL|0|0|115;2455214|ITEM|1430|0|60
```

- 编码为空/null = 该 OC 没有任何 4 类阻塞（渲染为「未知」），不额外写入 UNKNOWN 占位。
- 列**永久保留**，供事后审计与罚款核对。

### 5.4 包规划

新增逻辑按职责拆到 `model` 与 `service` 两个既有包下的子包，与既有 `oc.image` 的组织方式一致；领域规则与编排分离，领域类不依赖 Spring 上下文之外的任何东西。

```text
torn/model/faction/oc/delay/
    OcDelayReasonEnum.java      原因枚举（含展示名）
    OcDelayCauseEntry.java      单成员归因条目（record，负责段起点/累计的纯计算）
torn/service/faction/oc/delay/
    OcDelayReasonResolver.java  单成员单次采样的原因判定（纯函数）
    OcDelayCauseRecorder.java   编码/解码、采样合并、完成结算（无 IO）
```

编排（调接口、写库、渲染）仍留在 `TornOcCompleteNoticeService`。

---

## 6. 文件修改清单

### 6.1 新增文件

#### 6.1.1 `src/main/java/pn/torn/goldeneye/torn/model/faction/oc/delay/OcDelayReasonEnum.java`

```java
/**
 * OC 延误原因。
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.09.30
 */
@Getter
@RequiredArgsConstructor
public enum OcDelayReasonEnum {
    /** 成员在旅行（含起飞、滞留、返回，以及海外住院）。 */
    TRAVEL("TRAVEL", "旅行"),
    /** 成员在本土住院。 */
    HOSPITAL("HOSPITAL", "住院"),
    /** 成员在监狱。 */
    JAIL("JAIL", "监狱"),
    /** 成员缺少该岗位要求的道具。 */
    ITEM("ITEM", "缺道具");

    private final String code;
    private final String label;

    public static OcDelayReasonEnum codeOf(String code) { ... }   // 未知 code 返回 null
}
```

#### 6.1.2 `src/main/java/pn/torn/goldeneye/torn/model/faction/oc/delay/OcDelayCauseEntry.java`

```java
/**
 * 单个成员的 OC 延误归因条目。
 *
 * @param userId             成员ID
 * @param reason             首次观测到的延误原因
 * @param itemId             同时缺失的道具ID；无则为 null
 * @param segmentStartMinute 当前未闭合阻塞段的起点（epoch 分钟）；无开放段时为 {@link #NO_OPEN_SEGMENT}
 * @param accumulatedMinutes 已闭合阻塞段的净阻塞累计分钟
 */
public record OcDelayCauseEntry(long userId, OcDelayReasonEnum reason, Integer itemId,
                                long segmentStartMinute, int accumulatedMinutes) {
    /** 无开放阻塞段。 */
    public static final long NO_OPEN_SEGMENT = 0L;

    /** 是否存在尚未结算的阻塞段。 */
    public boolean hasOpenSegment();

    /** 开启阻塞段。 */
    public OcDelayCauseEntry startSegment(long minute);

    /** 闭合阻塞段并累加时长；无开放段时原样返回。 */
    public OcDelayCauseEntry closeSegment(long minute);

    /** 含开放段在内的净阻塞总分钟数。 */
    public int totalMinutes(long minute);
}
```

#### 6.1.3 `src/main/java/pn/torn/goldeneye/torn/service/faction/oc/delay/OcDelayReasonResolver.java`

```java
/**
 * OC 延误原因判定器。
 * <p>
 * 只按 §2.1 的优先级判定单次采样中单个成员的原因，不访问数据库与 Torn API。
 */
@Component
public class OcDelayReasonResolver {
    /**
     * 判定成员本次采样是否阻塞及原因。
     *
     * @param status                成员当前状态；查询不到时为 null
     * @param requiredItemId        岗位要求道具ID；无要求时为 null
     * @param requiredItemAvailable 成员是否持有该道具
     * @return 命中 4 类原因时返回原因与补充道具，否则返回空
     */
    public Optional<OcDelayReason> resolve(TornUserStatusVO status, Integer requiredItemId,
                                           Boolean requiredItemAvailable);

    /**
     * 命中结果。
     *
     * @param reason 主原因
     * @param itemId 同时缺失的道具ID；无则为 null
     */
    public record OcDelayReason(OcDelayReasonEnum reason, Integer itemId) {
    }
}
```

实现要求：

- 状态码复用 `TornUserStatusEnum.TRAVELING / ABROAD / HOSPITAL / JAIL`，目的地判定复用 `TornTravelTargetEnum.textContain(description)`，不新增状态枚举、不解析旅行方向文本。
- 缺道具判定与既有 `OcImageStatusResolver` 保持一致：`requiredItemId != null && Boolean.FALSE.equals(requiredItemAvailable)`。

#### 6.1.4 `src/main/java/pn/torn/goldeneye/torn/service/faction/oc/delay/OcDelayCauseRecorder.java`

```java
/**
 * OC 延误归因的编解码与段结算。
 * <p>
 * 只处理 {@link OcDelayCauseEntry} 的读写与累计，不访问数据库、不渲染文案。
 */
@Component
public class OcDelayCauseRecorder {
    /**
     * 解析归因编码。
     *
     * @param delayCause 数据库中的编码；空值返回空列表
     * @return 归因条目
     */
    public List<OcDelayCauseEntry> decode(String delayCause);

    /**
     * 合并一次采样结果。
     *
     * @param delayCause 现有编码
     * @param samples    本次采样命中的成员及其原因；未出现的成员视为本轮未阻塞
     * @param sampleTime 采样时刻
     * @return 合并后的编码；与入参相同表示无变化，调用方据此跳过写库
     */
    public String merge(String delayCause, Map<Long, OcDelayReasonResolver.OcDelayReason> samples,
                        LocalDateTime sampleTime);

    /**
     * 用实际完成时间封闭全部未闭合段。
     *
     * @param delayCause   现有编码
     * @param executedTime OC 实际执行时间
     * @return 结算后的编码
     */
    public String settle(String delayCause, LocalDateTime executedTime);
}
```

实现要求：

- 时间一律用 `time.truncatedTo(ChronoUnit.MINUTES).toEpochSecond() / 60` 转分钟桶，与现有播报口径一致。
- 编解码对损坏字段必须容错：无法解析的条目跳过并记 `log.warn`，不得抛异常中断通知链路。
- 不做排序，排序与最终阻塞者判定属于展示层。

### 6.2 修改文件

#### 6.2.1 `src/main/java/pn/torn/goldeneye/repository/model/faction/oc/TornFactionOcDO.java`

新增字段：

```java
/**
 * OC延误归因编码：每名阻塞成员以 ; 分隔，字段为 userId|原因|道具ID|当前段起始分钟|已累计分钟。
 */
private String delayCause;
```

#### 6.2.2 `src/main/java/pn/torn/goldeneye/torn/service/faction/oc/TornOcCompleteNoticeService.java`

新增依赖：无（`ocSlotDao`、`tornApi`、`itemsManager` 已在）。

新增常量：

```java
// 成员状态采样间隔：延误时长以分钟计，5 分钟粒度已足够识别主责，同时显著降低成员接口调用量
private static final int DELAY_SAMPLE_INTERVAL_MINUTES = 5;
// 计划执行分钟后需再等待的分钟数，用于确认 Torn 本轮的执行窗口已经错过
private static final int DELAY_CONFIRM_MINUTES = 1;
```

新增字段：

```java
// 帮派ID → 上次成员状态采样时间；重启后为空调度，最坏多采一次
private final Map<Long, LocalDateTime> delaySampleTimeMap = new ConcurrentHashMap<>();
```

改动点：

| 方法 | 改动 |
|---|---|
| `checkOcCompleted` | `pendingOcs` 非空时调用 `sampleDelayCause(faction, pendingOcs)`；`completedOcs` 非空时先调用 `settleDelayCause(completedOcs)` 再发送通知 |
| 新增 `sampleDelayCause(faction, pendingOcs)` | 按 §2.3 过滤可采样 OC、按帮派节流；查一次 slots + 一次成员状态；逐 OC 合并并**仅在编码变化时** `UPDATE`；整体 try/catch 记 `log.warn` 后返回，不影响完成检测链 |
| 新增 `settleDelayCause(completedOcs)` | 用 `executedTime` 结算并写库（一次/OC），把结算结果回填到 DO，供同批次渲染 |
| 重构 `findBadStatusMap` → `queryMemberStatusMap(factionId, userIdSet)` | 返回 `Map<Long, TornUserStatusVO>`（只保留目标成员）；`buildStatusWarnings` 改为在其结果上过滤 `isOcNotExecutable`，**保持"即将结束"预告行为不变**，并为采样复用同一次调用方式 |
| `buildDelayNotice` | 增加 `userMap` 入参；每个延误 OC 输出"明细行 + 原因行" |
| 新增 `buildDelayReasonLines(oc, userMap)` | 读取 `oc.getDelayCause()`，无条目返回单行 `原因：未知`；否则按净阻塞累计降序生成 `昵称[ID] 原因(…延误约N分钟…)` |
| 新增 `buildReasonText(entry)` | 拼装主原因、补充道具名（`itemsManager.getMap()`）、最终阻塞标记 |
| `sendOcCompleteNotice` | 把已有的 `userMap` 传给 `buildDelayNotice` |

约束：

- 计划执行时间必须经由 `OcPreparationTimeCalculator.calculatePlannedTime`，禁止在本类复制 `plusMinutes(1)`。
- 采样写库使用 `ocDao.lambdaUpdate()`，条件为 `id = oc.getId()`。
- 不修改 `scheduleOcTask` / `scheduleOcCompleteCheck` 的既有调度语义。

### 6.3 Liquibase

新增 `src/main/resources/db/changelog/1.0.1-2.0.0/1.6.6/oc-delay-cause.yaml`：

```yaml
databaseChangeLog:
  # OC 延误归因：记录每名成员造成延误的原因与净阻塞时长，供完成通知展示与事后审计。
  - changeSet:
      id: add_oc_delay_cause
      author: Bai
      changes:
        - addColumn:
            tableName: torn_faction_oc
            columns:
              - column:
                  name: delay_cause
                  type: TEXT
                  remarks: "延误归因编码：成员以 ; 分隔，字段为 userId|原因|道具ID|当前段起始分钟|已累计分钟；无阻塞时为空"
                  constraints:
                    nullable: true
      rollback:
        - dropColumn:
            tableName: torn_faction_oc
            columnName: delay_cause
```

并在 `src/main/resources/db/changelog/db.changelog-master.yaml` 末尾追加：

```yaml
  - include:
      file: db/changelog/1.0.1-2.0.0/1.6.6/oc-delay-cause.yaml
```

---

## 7. 测试方案

只新增本次行为所需的测试，不为既有延误判定重复用例矩阵。

| 测试 | 位置 | 覆盖 |
|---|---|---|
| 原因判定（参数化，纯单元） | `src/test/java/pn/torn/goldeneye/torn/service/faction/oc/delay/OcDelayReasonResolverTest.java`（新增） | 4 类命中；`Hospital`+目的地=旅行；状态与缺道具同中时以状态为主并带道具；`Okay`/未知状态不命中 |
| 归因记录（纯单元） | `src/test/java/pn/torn/goldeneye/torn/service/faction/oc/delay/OcDelayCauseRecorderTest.java`（新增） | 编码往返；多次采样的段累计与并集（原因首次定格）；`settle` 用完成时间封闭最后一段；非法编码容错 |
| 完成通知接线 | `src/test/java/pn/torn/goldeneye/torn/service/faction/oc/TornOcCompleteNoticeServiceTest.java`（改） | ①5 分钟节流：同一帮派连续两轮只采样一次；②完成时结算并渲染原因行（含最终阻塞标记）；③无阻塞记录时渲染「未知」 |

明确不做：不为图片渲染、推荐、分配等未改动链路补测试；不用源码字符串断言替代行为断言。

---

## 8. 验收与 Review 标准

实施完成后按以下条目逐条核对，全部满足即通过：

**口径**

1. 原因只出现 旅行/缺道具/住院/监狱，且按 §2.1 优先级取首次命中。
2. 旅行不出现子态；海外住院计入旅行；`Federal` 等未定义状态不产生条目。
3. 成员的原因为首次观测值，后续采样不覆盖；补充道具与主原因同源。
4. 采样为每帮派 5 分钟一次，且只对"计划执行分钟已过去至少 1 分钟"的 pending OC 生效。
5. 净阻塞累计按分钟桶计算；最后一段以 `executed_time` 封闭。
6. 只列出实际阻塞过的成员；完全无阻塞时输出「原因：未知」。
7. 成员按净阻塞累计降序，最大者（可并列）带 `最终阻塞` 标记；不 @ 人、不设条数上限。

**实现**

8. 复用 `OcPreparationTimeCalculator`，全仓库无第二处 `plusMinutes(1)`。
9. 采样每轮只发一次帮派成员接口、只查一次 slots，无 N+1。
10. `delay_cause` 仅在编码变化时写库；完成时每 OC 最多再写一次。
11. 采样异常不影响完成通知发送与后续调度。
12. 「即将结束」预告的道具/状态提醒行为与文案不变。
13. 新增类字段、record 组件、公开方法均有中文 Javadoc；Liquibase 列有中文 remarks。
14. 单方法复杂度 ≤15、参数 ≤7、无复制粘贴的重复逻辑。

**迁移与数据**

15. 迁移为可空加列，可回滚；加列后既有 OC 查询、规划、收益链路不受影响。
16. `delay_cause` 不被既有同步链路（`updateAvailableOcData` / `updateCompleteData`）覆盖或清空。
17. 服务重启后归因仍能从库中续算，不因内存丢失而重算。

---

## 9. 长期技术方案基线更新

| 文档 | 冲突点 | 处理 |
|---|---|---|
| `.ai/knowledge/table-image-rendering-1.6.0-technical-design.md` | 第 2.3 节声明"不改变……完成通知……的业务语义" | 追加基线说明：该约束描述的是 1.6.0 图片化的变更范围；1.6.6 起完成通知新增延误原因行，归因口径以本文档为准 |
| `.ai/knowledge/oc/oc-new-team-technical-design.md` | 第 2.2 节列出 OC 数据表字段事实 | 追加基线说明：1.6.6 起 `torn_faction_oc` 增加派生列 `delay_cause`，不属于时间线原始事实，规划与收益链路不读取 |
| `.ai/knowledge/file_location.md` | 文件索引未包含新增类 | 补充 `model/faction/oc/delay` 与 `service/faction/oc/delay` 下的新文件 |

---

## 10. 风险点

1. **海外住院的文本依赖**：`description` 含目的地国家名才能判为旅行；该文本格式目前没有生产样例，若 Torn 使用别的写法会退化为「住院」。实现时对"住院"条目保留原始 `description` 到 debug 日志，便于上线后核对并按需补充判据（不新增枚举）。
2. **5 分钟粒度的漏采**：采样间隔内发生的短促阻塞会整段丢失，属于既定折中，仅影响短延误，不影响 >5 分钟播报场景的主责识别。
3. **成员接口调用量**：仅在帮派存在待检 OC 且距上次采样满 5 分钟时调用一次（99 人约 58KB）。按近 30 天延误分钟合计折算约 8500 次/月，属可接受量级。
4. **跨帮派并发**：采样状态按帮派隔离，`checkOcCompleted` 也按帮派调度，同一 OC 不会被并发写。

---

## 11. 明确不做

- 不做"谁先离开导致谁跟着离开"的因果推断。
- 不预测成员何时恢复（不解析 `until`），时长只统计已观测的净阻塞。
- 不为 `Federal`/`Awoken`/`Dormant`/`Fallen` 增加枚举或业务分支。
- 不改动延误阈值、合并窗口、@ 行为与任何图片。
- 不为未来统计需求预建表或字段（不建 `torn_faction_oc_delay` 明细表）。

---

## 12. 停止条件

1. 第 8 章全部条目通过；
2. 编译与静态检查通过；
3. 第 7 章列出的测试通过；
4. 无未闭环的 P0/P1；
5. 未发现本次改动引入的数据、安全或可用性风险。
