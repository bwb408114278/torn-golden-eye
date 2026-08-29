# 活跃度热力图功能设计与实现说明

## 元信息

- 文档类型：当前功能设计、实现与验收说明
- 适用项目：Golden-Eye
- 适用版本：1.5.0 及以上
- 最后更新：2026.08.29
- 维护人：Bai
- 状态：已实施，技术验收通过，待运维手动部署
- 实施基线：`a2adbc8..49bf811`
- 文档定位：本文件合并 V2 历史设计、V3 实施契约和两轮修复验收结论，是活动热力图功能唯一有效的设计、开发、Review、部署后验收依据。
- 版本语义：V2 是 Redis TTL 内可读取的 legacy 数据版本；V3 是当前采集、查询、归档和展示版本。本文中的“当前实现”均指 `49bf811` 的 V3 实现及其 V2 兼容边界。

---

## 1. 目标与范围

在保留既有“15 分钟 Torn 帮派成员采集 → Redis 槽数据 → BufferedImage PNG → NapCat 图片消息”主链路的前提下，完成以下四项能力：

1. 支持以一个明确日期参数查询历史范围：`从#日期` 或 `截至#日期`；
2. 将 `torn_setting_faction` 中的有效配置帮派与 HoF Gold+ 帮派合并采集；
3. 将长期 `Idle` 从主活跃度中降级为独立辅助证据，并仅以背景暗化表达，不增加单元格数字；
4. 以日终压缩归档支持超过 Redis 30 天 TTL 的 V3 历史查询，避免将每个 15 分钟成员采样逐行落 PostgreSQL。

本功能的首要目标是**可解释的趋势分析**，不是宣称 Torn 用户的精确实时在线状态。

### 1.1 版本演进与历史兼容

| 阶段 | 历史事实 | 当前处理 |
|---|---|---|
| V1 | 早期活跃度实现与 Key | 已废弃，不读取、不迁移、不回填 |
| V2 | `Online OR Idle OR recentAction`；Redis 日 Bitmap / 帮派槽快照；无法拆分 Idle | 仅在原始 Redis TTL 存续期兼容读取；显示 legacy 提示，`idleRatio=0`，不写入新 V2 数据 |
| V3 | `Online OR recentAction` 为有效活跃，Idle-only 独立记录；Redis 30 天 + PostgreSQL 日包归档 | 当前唯一写入版本和长期查询版本 |

V2 的“Idle 计入活跃”是当时的历史口径，不可被 V3 的颜色、比例或人数规则重新解释。V2 兼容只保证旧数据在自然过期前可读，不承诺其统计精度与 V3 相同。

### 1.2 当前明确不做

- 不查询 Torn API 来响应热力图指令；查询端仍只读 Redis 和 PostgreSQL 归档。
- 不修改 Torn API DTO、Key 负载均衡或采集线程池配置。
- 不回填、纠正或反推历史 V2 中已合并的 `Online/Idle` 数据。
- 不把 V2 全量迁移到新归档表；V2 仅在 Redis TTL 存续期间兼容读取，过期后不伪造历史。
- 不新增用户可配置的颜色、阈值、TTL、归档开关或管理指令。
- 不建设任意时分秒筛选、跨时区筛选、图表导出、对比 Idle 人数或新的前端页面。
- 不延长 Redis 原始采样 TTL；长期数据只由日终压缩归档承担。

---

## 2. 已核实的现状与设计依据

### 2.1 生产同步 Redis 只读证据

2026.08.28 从用户授权同步到本地的生产 RDB（DB 1）中，统计得到：

| 指标 | 结果 |
|---|---:|
| 活跃度相关 Key | 3,456,147 |
| V2 帮派有效采样槽 | 1,834,940 |
| 有有效采样的帮派 | 910 |
| V2 用户 observed 日 Key 覆盖日期 | 2026-07-29 ～ 2026-08-28（31 天） |
| 当前 Redis 内存 | 511.98 MiB / 512 MiB |
| 淘汰策略 | `volatile-lru` |
| 帮派单槽在线人数 P50 / P95 / P99 / 最大值 | 9 / 31 / 40 / 79 人 |
| 帮派单槽在线比例 P50 / P95 / P99 | 22% / 41% / 52% |

结论：不能通过延长原始 Redis Key TTL 实现长期查询；当前内存已接近上限，必须以 PostgreSQL 日终压缩数据承接 V3 历史。

### 2.2 当前实现边界

| 领域 | 当前实现 | 本方案处理方式 |
|---|---|---|
| 查询窗口 | 策略层固定 `DEFAULT_DAYS = 28`，服务层只接受 7～30 天 | 由明确的日期范围对象替代固定天数入口；旧无日期格式仍等价于最近 28 天 |
| 帮派采集对象 | `factionhof?cat=rank` 中 Gold+，遇到低段位停止翻页 | 与配置帮派按 ID 并集；HoF 和配置各自独立维护来源 |
| 活跃判定 | `Online OR Idle OR recentAction` | V3 改为 `Online OR recentAction`；`Idle AND !recentAction` 仅作为背景降级证据 |
| 帮派颜色 | 按在线比例使用个人图的 0～100% 连续 Viridis 色板 | 仅帮派单图改为平均有效活跃人数的 5 锚点强对比连续渐变色；对比图不变 |
| 历史留存 | V2 Redis 日 Key，TTL 30 天 | V3 继续短期 Redis，并每天归档昨天的压缩日包到 PostgreSQL |
| V2 数据 | `status-active` 无法区分 Online 与 Idle | Redis TTL 内按旧口径展示，强制标记 legacy，不暗化、不回填 |

---

## 3. 冻结业务与交互契约

### 3.1 指令格式与参数优先级

#### 普通热力图

```text
g#活跃度#帮派#{factionId}
g#活跃度#用户#{userId}
g#活跃度#帮派#{factionId}#从#{yyyy-MM-dd}
g#活跃度#用户#{userId}#从#{yyyy-MM-dd}
g#活跃度#帮派#{factionId}#截至#{yyyy-MM-dd}
g#活跃度#用户#{userId}#截至#{yyyy-MM-dd}
```

#### 帮派对比图

```text
g#活跃度对比#{targetFactionId}
g#活跃度对比#{targetFactionId}#从#{yyyy-MM-dd}
g#活跃度对比#{targetFactionId}#截至#{yyyy-MM-dd}
```

对比图的帮派 A 仍是发送人绑定 Torn 用户所在帮派，帮派 B 是 `targetFactionId`；原有“不能对比自身帮派”的提示保持不变。

| 参数形态 | 解析范围（均为 `Asia/Shanghai` 自然日闭区间） |
|---|---|
| 无日期参数 | `[今天 - 27 天, 今天]`，兼容现有最近 28 天语义 |
| `从#yyyy-MM-dd` | `[startDate, 今天]` |
| `截至#yyyy-MM-dd` | `[endDate - 27 天, endDate]` |

#### 校验规则

1. 日期严格为 `yyyy-MM-dd`，不得接受时间、时区、Epoch、相对日期或空白替代值。
2. 只允许一个范围关键字，且只能是 `从` 或 `截至`；两个关键字同时出现、重复出现、关键字错误、参数段数不符均返回格式说明。
3. 起始/结束日期不得晚于 `Asia/Shanghai` 的今天；未来日期拒绝，不自动截断到今天。
4. `从` 日期不设置人为最大跨度。归档查询按目标对象和日期索引读取，个人为单用户日包、帮派为单帮派日包；Redis 原始数据只读取查询范围与最近30天窗口的交集，绝不为 TTL 前且无归档的理论缺失日构造 Redis key 或命令；不扫描全表。
5. 原“用户模式支持 at 用户”只适用于没有日期以外附加歧义的目标段：`用户#@目标#从#日期` 合法；`帮派` 模式仍拒绝 at 目标；at 标记和数字 ID 混用继续返回既有参数错误。
6. 范围解析是两个 Strategy 共用的纯组件；不得分别在两个 Strategy 中复制 `split`、日期解析和边界计算。

### 3.2 V3 证据判定

以单个成员、单个 15 分钟采样槽为单位，按以下互斥分类：

```text
recentAction = 0 <= collectedAt - lastAction.timestamp < 15 分钟
onlineActive = last_action.status 为 Online（忽略大小写和首尾空白）

有效活跃 active = onlineActive OR recentAction
Idle-only idle    = last_action.status 为 Idle AND !recentAction
静默 silent       = observed AND !active AND !idle
无数据             = API 本轮未成功返回该成员
```

补充规则：

- `Idle + recentAction` 归入 `active`，不重复记入 idle。
- `Offline + recentAction` 归入 `active`，保留对隐藏/隐私状态的兼容。
- `last_action` 缺失、时间戳为 0/负数/未来、未知状态均不能构成 active 或 idle，但 API 成功返回成员时仍是 observed。
- `Online` 即使操作时间过期仍为 active；这是产品保留的在线证据。
- `Idle` 不再计入 active；其只用于判断同一小时格是否应显示暗色版本。

### 3.3 帮派图显示与颜色

帮派格内数字保持单一数字：**平均有效活跃人数**。不展示 Idle 人数、斜杠、底条或额外文字。

```text
数字：平均有效活跃人数
主色：平均有效活跃人数在 5 个固定锚点色之间的连续渐变
暗化程度：Idle 在有效活跃与 Idle 总和中的占比
```

#### 3.3.1 固定 5 个人数渐变锚点

| 锚点 | 有效活跃平均人数 A | 图例标签 |
|---|---:|---|
| 0 | `A = 0` | `0` |
| 1 | `A = 25` | `25` |
| 2 | `A = 50` | `50` |
| 3 | `A = 75` | `75` |
| 4 | `A = 100` | `100+` |

主色按 A 在相邻锚点色之间线性插值（与个人图共用同一插值函数）：`A <= 0` 取首锚点色，`A >= 100` 取最右锚点色（`100+` 区段内颜色不再变化），A 恰为锚点值时等于该锚点色；不得按帮派成员数、在线比例、P95、当前图片、查询区间或用户配置自适应调整。图例为连续渐变条，标签按锚点等距标注。

> 变更记录：2026-08-29（1.5.1）起，原 `floor(averageActiveCount / 25)` 的 5 档离散选色改为上述锚点间连续插值；锚点 RGB、图例标签、Idle 暗化规则均不变。

#### 3.3.2 固定 RGB 锚点渐变主色与按 Idle 占比连续暗化

5 个锚点主色沿用可读性已验证的 Viridis 强对比锚点：

| 图例标签 | 锚点主色 RGB | Idle 100% 时的最大暗化色 RGB |
|---|---|---|
| `0` | `(68, 1, 84)` | `(37, 1, 46)` |
| `25` | `(59, 82, 139)` | `(32, 45, 76)` |
| `50` | `(33, 145, 140)` | `(18, 80, 77)` |
| `75` | `(94, 201, 98)` | `(52, 111, 54)` |
| `100+` | `(253, 231, 37)` | `(139, 127, 20)` |

每个有效 V3 格先聚合 `A = averageActiveCount`、`I = averageIdleCount`，再计算：

```text
mainColor = A 在锚点 0/25/50/75/100 对应色之间线性插值（A >= 100 钳制最右锚点色）
idleRatio = A + I > 0 ? I / (A + I) : 0
brightnessMultiplier = 1 - 0.45 × idleRatio
renderColor = round(mainColorChannel × brightnessMultiplier)
```

- `idleRatio = 0`：使用完整渐变主色；
- `idleRatio = 0.5`：使用渐变主色与最大暗化色之间的中间亮度；
- `idleRatio = 1`：使用表中的最大暗化色（渐变主色通道 × `0.55`，四舍五入）；
- 不使用 `I / memberCount`：暗化表达的是有在线证据群体中 Idle 对有效活跃的占比，而不是全体成员中的 Idle 占比；
- 渐变位置始终只由 `A` 决定，`I` 不改变格内数字、渐变位置或图例标签；
- 渐变主色和连续暗化后的颜色都继续通过既有亮度阈值选择黑白文字。

无数据始终为既有深灰 `(45,45,45)`，显示 `—`；已观测且有效活跃为 0 时，`I = 0` 使用锚点 `0` 主色，`I > 0` 按公式连续暗化，必须与无数据区分。

#### 3.3.3 其他图的边界

| 图片 | 有效活跃数值 | Idle 表达 | 色板 |
|---|---|---|---|
| 个人图 | 有效活跃比例 | `idleSamples / (activeSamples + idleSamples)` 按本节公式连续暗化；分母为 0 时不暗化 | 保留 Viridis 连续比例色板 |
| 帮派图 | 平均有效活跃人数 | `averageIdleCount / (averageActiveCount + averageIdleCount)` 按本节公式连续暗化，不改数字 | 本节固定锚点人数渐变色板 |
| 帮派对比图 | 双方平均有效活跃人数 | 不参与比较和色差 | 保留既有蓝—灰—紫发散色板 |

对比图副标题增加：`仅对比有效活跃人数；Idle 不计入对比`。

### 3.4 数据可用性与文案

旧 V2 的“少于 7 天即拒绝整张图”规则废止。只要范围内存在至少一个有效 observed 槽，就必须返回图片；缺失格显示 `—`，覆盖提示放在副标题。

| 场景 | 行为 / 文案 |
|---|---|
| 范围内无任何有效 observed 槽 | 仅返回文本：`该时间范围暂无活跃度采样数据` |
| 有数据，但有效采样自然日少于 7 | 出图；副标题附加：`该时间范围仅覆盖 {actualDays} 个采样日，热力图仅供参考` |
| 有数据，且覆盖星期行不足 7 | 出图；副标题附加：`该时间范围覆盖不完整（已覆盖 {observedDowCount}/7 个星期），热力图仅供参考` |
| 同时满足前两种部分覆盖情况 | 优先显示“仅覆盖 {actualDays} 个采样日”文案，避免两条低价值重复提示 |
| 单格无 observed | 深灰格内显示：`—` |
| 查询结果含 V2 legacy 采样 | 副标题附加：`部分历史采样未区分 Idle，仅供趋势参考` |

渲染器必须支持副标题按换行分两行绘制：第一行为原有指标/覆盖率说明，第二行仅放“数据不完整”或“legacy”提示。两种提示都存在时按“数据不完整 → legacy”顺序拼接在第二行；布局高度随之明确增加，禁止文本重叠或截断。

---

## 4. 数据版本、Redis 与 PostgreSQL 日终归档

### 4.1 V2 / V3 兼容优先级

| 数据版本 | 查询来源 | 语义 | 长期保留 |
|---|---|---|---|
| V2 legacy | 既有 `activity:v2:*` Redis Key | `Online OR Idle OR recentAction`；无 Idle 分拆 | 仅保留至原 TTL 自然过期，不迁移 |
| V3 当天/未归档日 | 新 `activity:v3:*` Redis Key | active / idle-only / observed 三态 | Redis 30 天 |
| V3 已归档日 | PostgreSQL 日终归档 | 与 V3 Redis 相同的压缩位图/槽计数 | 长期保存，当前不设自动清理 |

按单个自然日选择数据源：**V3 PostgreSQL 归档优先 → 完整 V3 Redis → V2 Redis → 无数据**。不得对同一日期同时累计两个版本，避免重复采样。V3 Redis 日快照只有同时具备全部必要事实时才可采用：用户必须有 observed/active/idle 三个 Bitmap，帮派必须有 observed/active-count/idle-count/member-count 四项，且每个 observed 槽在对应 bitmap/计数数组中可访问；局部淘汰、部分写入或截断不得被解释为有效零活跃，应降级尝试 V2 或显示无数据。

V2 和 V3 混合日期允许在同一张图展示；V2 参与主数值，但 Idle 占比为未知，强制按 `idleRatio = 0` 使用完整主色，并按 3.4 输出 legacy 提示。V2 Redis Key 过期后不得以 0、Idle 或补采数据替代。

### 4.2 Redis Key 与版本隔离

所有 V3 Key 与 V2 前缀隔离，避免语义混算：

#### 4.2.1 V2 legacy Redis 兼容 Key

V2 仅由查询加载器读取，采集侧禁止继续写入。以下顺序是兼容契约的一部分：

```text
# 个人日 Bitmap（96 bit，TTL 自然过期）
activity:v2:user:observed:{userId}:{yyyy-MM-dd}
activity:v2:user:status-active:{userId}:{yyyy-MM-dd}
activity:v2:user:recent-action:{userId}:{yyyy-MM-dd}

# 帮派日槽快照（96 槽）
activity:v2:faction-snapshot-v2:online-count:{factionId}:{yyyy-MM-dd}
activity:v2:faction-snapshot-v2:member-count:{factionId}:{yyyy-MM-dd}
activity:v2:faction-snapshot-v2:observed:{factionId}:{yyyy-MM-dd}
```

- V2 个人 active：`status-active OR recent-action`。
- V2 帮派 Pipeline 的 key 构造、结果解包与 `FactionDay` 参数顺序都固定为：`online-count → member-count → observed`。
- V2 三项中任一 Key 缺失时，该自然日视为无可用 V2 数据；不得使用零数组补齐。
- V2 帮派日只携带 `online-count` 作为主人数、`member-count` 作为历史槽事实、`observed` 作为分母；`idleCounts=null`、`legacyV2=true`，聚合后的 `idleRatio=0`。

#### 4.2.2 V3 Redis Key

```text
# 个人每天 96 bit，TTL 30 天
activity:v3:user:observed:{userId}:{yyyy-MM-dd}
activity:v3:user:active:{userId}:{yyyy-MM-dd}
activity:v3:user:idle:{userId}:{yyyy-MM-dd}

# 帮派每天 96 槽，TTL 30 天
activity:v3:faction:active-count:{factionId}:{yyyy-MM-dd}  # 96 字节，每槽无符号人数
activity:v3:faction:idle-count:{factionId}:{yyyy-MM-dd}    # 96 字节，每槽无符号人数
activity:v3:faction:member-count:{factionId}:{yyyy-MM-dd}  # 96 字节，每槽无符号人数
activity:v3:faction:observed:{factionId}:{yyyy-MM-dd}      # 96 bit

# V3 日终归档索引，TTL 30 天
activity:v3:archive:users:{yyyy-MM-dd}     # 当日 observed 的 userId Set
activity:v3:archive:factions:{yyyy-MM-dd}  # 当日成功采集的 factionId Set
activity:v3:archive:dates                  # ZSET，member=yyyy-MM-dd，score=epochDay

# 继续复用、无需复制数据
activity:v2:user:names
activity:v2:faction:names
faction:members:{factionId}
faction:tracked

# Gold+ 来源快照，TTL 7 天；与最终并集 faction:tracked 分离
activity:v3:tracked-factions:gold-plus
```

采集侧所有 V3 bitmap、人数槽、归档索引 Set/ZSET 与成员快照必须使用同一 `collectionTime/date/slot`。`archive:users` / `archive:factions` 的 `SADD + EXPIRE` 及 `archive:dates` 的 `ZADD + EXPIRE` 必须放入既有单帮派 Redis Pipeline，增加 Redis 命令但不增加网络往返。

### 4.3 PostgreSQL 压缩归档模型

#### 4.3.1 设计原则

- 不保存“用户 × 15 分钟槽”的逐行事实；一个用户一天最多一行、一个帮派一天最多一行。
- 个人归档保留 96 bit observed/active/idle Bitmap；按查询时的星期/小时聚合，保持现有 15 分钟精度。
- 帮派归档保留 96 字节 active/idle/member 槽值及 observed Bitmap；不依赖当前成员 Set。
- 归档数据必须具备幂等唯一约束；任务中断后可重试，不产生重复日包。
- 日终归档只是 V3 Redis 的压缩副本，不改变采集、图片查询、名称缓存或 Torn API 调用职责。

#### 4.3.2 表 `torn_activity_user_daily`

| 字段 | 类型 | 约束 / 语义 |
|---|---|---|
| `id` | `BIGINT` | 自增主键 |
| `user_id` | `BIGINT` | 非空；Torn 用户 ID |
| `activity_date` | `DATE` | 非空；`Asia/Shanghai` 自然日 |
| `observed_bitmap` | `BYTEA` | 非空；96 bit observed |
| `active_bitmap` | `BYTEA` | 非空；96 bit 有效活跃 |
| `idle_bitmap` | `BYTEA` | 非空；96 bit idle-only |
| `data_version` | `VARCHAR(16)` | 非空，固定 `V3` |
| `deleted/create_time/update_time` | 与 `BaseDO` 一致 | 保持项目审计规范 |

唯一约束：`uk_activity_user_daily_user_date(user_id, activity_date)`。

索引仅保留该唯一索引；个人查询固定以 `user_id + activity_date range` 访问，不新增宽泛索引。

#### 4.3.3 表 `torn_activity_faction_daily`

| 字段 | 类型 | 约束 / 语义 |
|---|---|---|
| `id` | `BIGINT` | 自增主键 |
| `faction_id` | `BIGINT` | 非空；Torn 帮派 ID |
| `activity_date` | `DATE` | 非空；`Asia/Shanghai` 自然日 |
| `observed_bitmap` | `BYTEA` | 非空；96 bit 成功采样标记 |
| `active_counts` | `BYTEA` | 非空；96 字节有效活跃人数 |
| `idle_counts` | `BYTEA` | 非空；96 字节 idle-only 人数 |
| `member_counts` | `BYTEA` | 非空；96 字节有效成员数 |
| `data_version` | `VARCHAR(16)` | 非空，固定 `V3` |
| `deleted/create_time/update_time` | 与 `BaseDO` 一致 | 保持项目审计规范 |

唯一约束：`uk_activity_faction_daily_faction_date(faction_id, activity_date)`。

#### 4.3.4 表 `torn_activity_archive_day`

此表只表达“某 V3 自然日已完整归档”，不是运行日志或任务平台。

| 字段 | 类型 | 约束 / 语义 |
|---|---|---|
| `activity_date` | `DATE` | 主键；完成归档的 `Asia/Shanghai` 自然日 |
| `data_version` | `VARCHAR(16)` | 非空，固定 `V3` |
| `archived_at` | `TIMESTAMP` | 非空；归档完成的本地审计时间 |

归档服务以当天 V3 索引是否存在决定所需数据面：用户索引与帮派索引均为空时不写 marker；任一索引非空时，其索引内所有对象都必须通过完整性校验并成功批量 UPSERT。只有**每个非空索引侧**均完整成功后，才插入 marker。因此合法的“仅用户索引”或“仅帮派索引”日可完成，但任一非空侧出现缺失 Key、跳过对象、Redis 读取或数据库写失败时不得写 marker；下次启动补偿或定时执行必须重试整天。日包 UPSERT 可重复，marker 的主键保证完成状态不重复。

### 4.4 日终归档流程

1. 每天 `00:10:00 Asia/Shanghai` 调度 `ActivityDailyArchiveService.archiveRecentUnarchivedDays()`。
2. `ApplicationReadyEvent` 后使用项目既有 `virtualThreadExecutor` 异步提交同一入口；提交被拒绝时仅记录 warning，不阻断启动，也不在监听器线程同步回退。
3. 两个入口通过 `activity:v3:archive:dates` ZSET 在 `[today-29天, today-1天]` 内发现候选日；候选为空时立即返回。对候选日期只执行一次数据库 marker 范围查询，再处理 marker 缺失的日期。禁止固定枚举 29 天、`KEYS` 或全库 `SCAN`。
4. 入口通过 JVM `AtomicBoolean` 防重入；当前单实例部署，不增加分布式锁。
5. 对每个候选日期读取 V3 用户/帮派索引 Set；禁止用 `KEYS` 或全库 `SCAN` 枚举用户数据。
6. 使用 Redis Pipeline 分批读取每个索引对象对应的 V3 Key；每批固定上限（建议 500 个对象）以控制单次请求、内存和 JDBC batch 大小。
7. 缺少 observed 或某个 V3 必需 Key 的对象视为不完整对象：记录对象数的 warn 日志并跳过该对象；不得用零字节补齐。任一非空索引侧出现不完整对象时不写 marker。
8. 对通过校验的对象，调用自定义 Mapper 的 PostgreSQL `INSERT ... ON CONFLICT (业务唯一键) DO UPDATE`，更新为来自当前 Redis 的同一日完整 V3 包。此操作保证首次写入、重试及此前部分写入后重新执行的幂等性。
9. 每个非空索引侧全部成功后，在独立的短事务内写入 `torn_activity_archive_day` marker。
10. 记录 `date / userIndexed / userArchived / factionIndexed / factionArchived / skippedIncomplete / elapsedMs`，不得记录用户 ID、成员名称、Redis value、API Key 或 Redis 密码。

归档读取和 Redis 批处理不在数据库事务中执行；只有短暂的 mapper 写入与最终 marker 写入采用事务，避免长事务持有 Redis/网络等待。

### 4.5 容量与性能边界

本地 RDB 的 31 天 observed 数据约有 48,467 名用户、每日约 1.6 万～4.3 万用户日包。即使持续一年，归档表写入量远低于“用户 × 96 个槽”的明细模型；但用户日包仍会持续增长。

本轮采取如下边界：

- 不自动物理清理归档数据；历史留存时长由后续容量治理单独决定。
- 不为未来分区、冷存储、导出或清理预建任务框架。
- 上线后以 `torn_activity_user_daily` / `torn_activity_faction_daily` 的行数、表大小、索引大小、单用户范围查询计划和日终耗时作为容量观测证据。
- 如果实际日终归档或长期表增长超过当前 PostgreSQL 容量预算，后续再单独设计分区/保留期；本轮不能通过缩短数据、伪造汇总或放弃唯一约束规避问题。

---

## 5. 帮派采集范围

### 5.1 来源与并集

```text
trackedFactionIds
= 最近一次成功 HoF 刷新的 Gold+ factionId 集合
  ∪ TornSettingFactionManager.getList() 的当前有效配置 factionId 集合
```

- Gold+ 来源与最终采集并集必须分别持久化：成功 HoF 刷新时将 Gold+ ID 集合以临时 Set + `RENAME` 原子发布到 `activity:v3:tracked-factions:gold-plus`，TTL 7 天；启动时先恢复该来源，再与当前配置来源合并。不得只恢复最终 `faction:tracked`，否则重启后 HoF 失败会丢失非配置 Gold+ 帮派。
- 以 `factionId` 去重、排序后发布不可变 List。
- `TornSettingFactionManager.getList()` 已复用 `TornSettingFactionDAO.list()` 的项目既有有效记录语义；不得新增 Mapper 或绕过 DAO 手写 SQL。
- 以配置表的 `factionName` 批量补充 `activity:v2:faction:names`，仅当 HoF 本轮未提供该名称或配置名称更可靠时覆盖；查询仍只读 Redis 名称缓存。

### 5.2 失败与刷新语义

| 情况 | 正确行为 |
|---|---|
| HoF 刷新成功 | 更新 Gold+ 来源、名称缓存；与当前配置来源合并后发布 |
| HoF 刷新失败 / 空响应 | 保留内存或 Redis 恢复的上次成功 Gold+ 来源；仍实时读取配置来源并合并，已配置低段位帮派不得停止采集，非配置 Gold+ 帮派也不得因重启消失 |
| 配置增加 | 下次日刷新合并后纳入采集 |
| 配置移除 | 下次日刷新从配置来源移除；若该帮派仍属于 Gold+，继续采集 |
| HoF 中途遇到非 Gold | 维持现有停止翻页优化；不能因配置帮派要求继续扫描整个低段位 HoF |

不新增配置开关、不持久化第二份配置清单、不在每个 15 分钟采集任务中查询 PostgreSQL。

---

## 6. 包与职责规划

### 6.1 现有包的受控拆分

当前 `pn.torn.goldeneye.torn.service.activity.ActivityHeatmapService` 已约 744 行，直接叠加 Redis/归档双源、范围解析和 V3 聚合会形成超级类。本方案仅按职责拆为以下子包；不迁移无关活跃度类。

```text
pn.torn.goldeneye
├── napcat.strategy.user
│   ├── ActivityHeatmapStrategyImpl                 # 修改：解析普通指令后调用范围对象
│   └── ActivityCompareStrategyImpl                 # 修改：解析对比指令后调用范围对象
├── repository
│   ├── model.activity
│   │   ├── TornActivityUserDailyDO                 # 新增：用户 V3 日包
│   │   ├── TornActivityFactionDailyDO              # 新增：帮派 V3 日包
│   │   └── TornActivityArchiveDayDO                # 新增：完整归档 marker
│   ├── mapper.activity
│   │   ├── TornActivityUserDailyMapper             # 新增：批量 UPSERT / 范围读
│   │   ├── TornActivityFactionDailyMapper          # 新增：批量 UPSERT / 范围读
│   │   └── TornActivityArchiveDayMapper            # 新增：已归档日期查询 / marker 写入
│   └── dao.activity
│       ├── TornActivityUserDailyDAO                # 新增：封装用户归档读写
│       ├── TornActivityFactionDailyDAO             # 新增：封装帮派归档读写
│       └── TornActivityArchiveDayDAO               # 新增：封装 marker 判定与写入
└── torn
    ├── model.activity
    │   ├── ActivityEvidence                         # 修改：V3 active/idle 证据结果
    │   ├── ActivityQueryRange                       # 新增：不可变日期范围 record
    │   ├── ActivityQueryRangeMode                   # 新增：DEFAULT/FROM/UNTIL 枚举
    │   ├── BaseActivityHeatmapVO                    # 新增：三种图片共同元数据
    │   ├── PersonalActivityHeatmapVO                # 修改：新增 idleRatio 矩阵，继承共同元数据
    │   ├── FactionActivityHeatmapVO                 # 修改：新增 idleRatio 矩阵，继承共同元数据
    │   └── ActivityComparisonHeatmapVO              # 修改：继承共同元数据
    └── service.activity
        ├── ActivityEvidenceClassifier               # 修改：V3 互斥证据分类
        ├── ActivityRedisKeys                        # 修改：明确 V2 legacy 与 V3 key 构造
        ├── TornActivityCollectService               # 修改：V3 采集、来源并集、归档索引写入
        ├── HeatmapColorScale                        # 修改：个人暗化 + 帮派 5 档/暗化颜色函数
        ├── HeatmapImageRenderer                     # 修改：副标题两行、帮派/个人暗化绘制、帮派图例
        ├── ActivityHeatmapService                   # 修改：保留三个公开查询门面与 VO 组装
        ├── query
        │   ├── ActivityQueryRangeParser             # 新增：命令参数 → ActivityQueryRange，纯函数
        │   ├── ActivityHeatmapDataLoader            # 新增：V3 archive / V3 Redis / V2 Redis 的单日优先级加载
        │   ├── ActivityHeatmapAggregator            # 新增：个人、帮派、对比的纯内存矩阵聚合
        │   └── ActivityDaySnapshot                  # 新增：loader 与 aggregator 使用的包内不可变日快照模型
        └── archive
            └── ActivityDailyArchiveService          # 新增：索引驱动日终归档、启动补偿和 JVM 防重入
```

### 6.2 复用与禁止重复

- `ActivityQueryRangeParser` 是两个 Bot Strategy 唯一允许的日期参数解析点。
- `ActivityHeatmapDataLoader` 统一封装三版本数据源优先级、Redis Pipeline、归档 DAO 读取和 legacy 标记；个人、帮派、对比分别调用同一加载边界，不在 Service 内复制读 Redis 逻辑。
- V2 帮派 legacy 的 Pipeline 三项顺序固定为 `online-count → member-count → observed`；key 构造、结果解包和 `FactionDay` 构造必须使用同一顺序。V2 未携带 Idle，因此只读兼容时 `legacyV2=true`、`idleCounts=null`、聚合 `idleRatio=0`，不得借由 V3 数据结构重解释旧值。
- `ActivityHeatmapAggregator` 只处理已加载的日快照和矩阵计算，不依赖 Spring、Redis、数据库、当前时间或消息对象；个人/帮派/对比都复用其按 observed 槽聚合的底层位序工具。
- `HeatmapColorScale` 是所有颜色映射、暗化和文字颜色的唯一来源；渲染器不能内嵌 RGB、暗化系数或人数档位。
- `ActivityDailyArchiveService` 只处理 V3；不得为了 legacy 兼容在该服务实现 V2 全库扫描、RDB 读取或 Torn API 补采。

---

## 7. 精确文件修改清单

### 7.1 配置、Schema 与持久层

| 操作 | 文件 | 修改责任 |
|---|---|---|
| 已实施，不修改 | `src/main/resources/db/changelog/1.0.1-2.0.0/1.5.0/activity-heatmap-v3.yaml` | 已执行：创建 3 张归档表、全部字段 remarks、唯一约束；当前文件内容/路径/checksum 均为部署基线 |
| 已实施，不修改 | `src/main/resources/db/changelog/db.changelog-master.yaml` | 已注册现有 1.5.0 include；后续业务修复不得改动该已执行 include 或旧 changeSet |
| 新增 | `src/main/java/pn/torn/goldeneye/repository/model/activity/TornActivityUserDailyDO.java` | 映射用户日包；`@TableName(autoResultMap = true)`；每个字段完整 Javadoc |
| 新增 | `src/main/java/pn/torn/goldeneye/repository/model/activity/TornActivityFactionDailyDO.java` | 映射帮派日包；同上 |
| 新增 | `src/main/java/pn/torn/goldeneye/repository/model/activity/TornActivityArchiveDayDO.java` | 映射完成 marker；`activityDate` 作为业务唯一字段，不把审计时间误作活动日期 |
| 新增 | `src/main/java/pn/torn/goldeneye/repository/mapper/activity/*.java` | 声明批量 UPSERT、范围查询和 marker 查询接口，接口 Javadoc 完整 |
| 新增 | `src/main/resources/mapper/activity/TornActivityUserDailyMapper.xml` | `INSERT ... ON CONFLICT` 的用户日包批量写入和按用户日期范围读取 |
| 新增 | `src/main/resources/mapper/activity/TornActivityFactionDailyMapper.xml` | 帮派日包批量写入和按 faction/date 范围读取 |
| 新增 | `src/main/resources/mapper/activity/TornActivityArchiveDayMapper.xml` | 已归档日期范围查询与 marker 幂等写入 |
| 新增 | `src/main/java/pn/torn/goldeneye/repository/dao/activity/*.java` | 隔离 Mapper 调用；归档服务不得直接依赖 Mapper |

SQL 约束：范围读取必须显式列出字段，按 `activity_date ASC` 返回；写入使用一次 batch SQL，业务唯一键冲突时覆盖同日期完整 V3 包；不得循环逐条 `save()`，不得用 `SELECT → INSERT/UPDATE` 代替 PostgreSQL 原子 UPSERT。

### 7.2 采集与归档

| 操作 | 文件 | 修改责任 |
|---|---|---|
| 修改 | `src/main/java/pn/torn/goldeneye/torn/model/activity/ActivityEvidence.java` | 将 V2 `statusActive/estimatedActive` 模型替换为 V3 `onlineActive/recentAction/idleOnly/effectiveActive` 事实；record 组件 Javadoc 逐项更新 |
| 修改 | `src/main/java/pn/torn/goldeneye/torn/service/activity/ActivityEvidenceClassifier.java` | 严格实现 3.2 的互斥判定；保留未来时间戳 fail-closed 行为 |
| 修改 | `src/main/java/pn/torn/goldeneye/torn/service/activity/ActivityRedisKeys.java` | 新增 V3 用户、帮派、日索引 key；将旧构造方法标注为 V2 legacy 语义，调用点全部明确版本 |
| 修改 | `src/main/java/pn/torn/goldeneye/torn/service/activity/TornActivityCollectService.java` | 合并帮派来源；写 V3 observed/active/idle 与 active/idle/member/observed 槽；在同一 Pipeline 写日索引 Set；不再写 V2 新数据 |
| 新增 | `src/main/java/pn/torn/goldeneye/torn/service/activity/archive/ActivityDailyArchiveService.java` | 00:10 定时、虚拟线程启动补偿、ZSET 候选发现、AtomicBoolean、Redis Pipeline 批读取、批量 DAO UPSERT、各非空索引侧完整后 marker 最后写入 |

采集现有 `faction:members:{factionId}` 临时 Set + `RENAME` 原子替换必须保留；它不作为历史聚合来源，也不作为日终归档的用户枚举来源。

### 7.3 查询、模型与 Bot 指令

| 操作 | 文件 | 修改责任 |
|---|---|---|
| 新增 | `src/main/java/pn/torn/goldeneye/torn/model/activity/ActivityQueryRangeMode.java` | 固定范围模式枚举 |
| 新增 | `src/main/java/pn/torn/goldeneye/torn/model/activity/ActivityQueryRange.java` | `startDate/endDate/mode` 不可变 record；校验由 parser 完成 |
| 新增 | `src/main/java/pn/torn/goldeneye/torn/model/activity/BaseActivityHeatmapVO.java` | 收敛 `totalDays/coverage/hasData/noticeMessage/legacyDataIncluded` 共同字段 |
| 修改 | `src/main/java/pn/torn/goldeneye/torn/model/activity/PersonalActivityHeatmapVO.java` | 继承共同元数据，保留 activeRate/observedSamples，新增 `idleRatio[7][24]`，值域 `[0,1]`，legacy 格固定为 `0` |
| 修改 | `src/main/java/pn/torn/goldeneye/torn/model/activity/FactionActivityHeatmapVO.java` | 继承共同元数据，保留 averageOnlineCount 字段名以减小接线修改，但其业务 Javadoc 改为“平均有效活跃人数”；新增 `idleRatio[7][24]`，值域 `[0,1]`，legacy 格固定为 `0` |
| 修改 | `src/main/java/pn/torn/goldeneye/torn/model/activity/ActivityComparisonHeatmapVO.java` | 继承共同元数据；现有数字语义改为平均有效活跃人数 |
| 新增 | `src/main/java/pn/torn/goldeneye/torn/service/activity/query/ActivityQueryRangeParser.java` | 一次实现普通/对比参数尾部的日期解析与格式化帮助 |
| 新增 | `src/main/java/pn/torn/goldeneye/torn/service/activity/query/ActivityDaySnapshot.java` | 仅作为 loader/aggregator 内部日数据传输，不暴露到 Bot 层 |
| 新增 | `src/main/java/pn/torn/goldeneye/torn/service/activity/query/ActivityHeatmapDataLoader.java` | 批量加载 archive/V3 Redis/V2 Redis，按日优先级消重，返回 legacy 标记 |
| 新增 | `src/main/java/pn/torn/goldeneye/torn/service/activity/query/ActivityHeatmapAggregator.java` | 个人、帮派与共同 observed 对比聚合，显式 MSB-first 位序 |
| 修改 | `src/main/java/pn/torn/goldeneye/torn/service/activity/ActivityHeatmapService.java` | 保留 `queryPersonalHeatmap/queryFactionHeatmap/compareFactions` 门面，参数变为 `ActivityQueryRange`；组合 loader/aggregator/标题/文案/VO |
| 修改 | `src/main/java/pn/torn/goldeneye/napcat/strategy/user/ActivityHeatmapStrategyImpl.java` | 兼容旧格式，委派共用 parser，更新格式说明；不改变 at 用户绑定规则 |
| 修改 | `src/main/java/pn/torn/goldeneye/napcat/strategy/user/ActivityCompareStrategyImpl.java` | 兼容旧格式，委派共用 parser，更新格式说明 |

`ActivityHeatmapService` 的公开 API 与两个 Strategy 同步修改即可；仓库内不存在其他调用方时，不保留仅为兼容编译而存在的 `days` 重载。

### 7.4 图片渲染

| 操作 | 文件 | 修改责任 |
|---|---|---|
| 修改 | `src/main/java/pn/torn/goldeneye/torn/service/activity/HeatmapColorScale.java` | 固定帮派 5 档主色、按 `idleRatio` 计算 `1 - 0.45 × idleRatio` 的连续暗化函数、个人连续色暗化函数、统一文字颜色判断 |
| 修改 | `src/main/java/pn/torn/goldeneye/torn/service/activity/HeatmapImageRenderer.java` | 支持两行副标题；个人/帮派按 `idleRatio` 连续暗化；帮派图例改为 `0/25/50/75/100+`；对比图副标题补充 Idle 不参与对比 |

不得修改 `ActivityComparisonHeatmapVO` 的 P95 差值着色公式、对比图双方共同 observed 槽语义、图片 base64 编码方式或字体选择，除非为两行副标题的统一布局所必需。

---

## 8. 实施顺序

### 阶段 1：Schema 与持久化契约

1. 只读确认 `torn_setting_faction` 与现有 changelog 执行状态；数据库工具不可用时，记录阻塞，不臆造字段或执行状态。
2. 活动归档的 1.5.0 Liquibase 文件与 master include 已执行并成为基线；不得修改旧 changeSet。
3. 编写三类归档 DO、Mapper、DAO、XML；先实现 batch UPSERT 与范围查询。
4. 用一个真实 PostgreSQL Mapper 测试验证“首次写入 → 同业务键重试覆盖 → 范围读取”的实际 SQL 行为。

### 阶段 2：V3 采集事实

1. 先更新 `ActivityEvidenceClassifierTest`，固定 Online、recent Offline、Idle 无操作、Idle 有近期操作、未来时间戳和缺失 last_action 的分类。
2. 更新 `ActivityEvidence` 和 classifier 至测试通过。
3. 新增 V3 key 构造与 V3 采集上下文；保持 V2 key 只读可用。
4. 修改 `TornActivityCollectService`：通过 `TornSettingFactionManager` 合并配置来源；单 Pipeline 写 V3 个人/帮派数据与两个日归档索引 Set。
5. 验证 API 失败不写 observed/归档索引；单槽重采仍清理当前槽失效 active/idle 位；槽人数仍按无符号字节且越界 fail-fast。

### 阶段 3：日终归档

1. 先为 `ActivityDailyArchiveService` 编写“每个非空索引侧全部成功才写 marker；任一非空侧不完整或写入异常不写 marker”的单元测试。
2. 实现 ZSET 候选日发现、一次 marker 范围查询、索引 Set 读取、Redis Pipeline 分批、完整性校验和 DAO batch UPSERT。
3. 实现 `00:10 Asia/Shanghai` 定时与既有虚拟线程 executor 的启动补偿；两个入口必须复用同一 AtomicBoolean 入口，并在 `finally` 释放。
4. 不实现 V2 全库扫描或 V2 回填；归档服务发现只有 V2 数据时跳过，由查询 loader 直接读 V2 Redis。

### 阶段 4：日期范围与双源查询

1. 编写 `ActivityQueryRangeParserTest`：默认 28 天、从日期、截至日期、未来日期、错误关键字、重复范围、错误日期格式；at 解析只在既有 Strategy 接线测试覆盖一次。
2. 实现 parser 与 Strategy 接线，保持不带日期的现有命令可用。
3. 实现 `ActivityHeatmapDataLoader`：按“V3 archive → V3 Redis → V2 Redis”逐日选择，加载结果不得重复累计。
4. 将纯矩阵计算迁至 `ActivityHeatmapAggregator`；个人/帮派/对比继续以 observed 为分母，对比继续以共同原始槽为分母。
5. 用 `ActivityHeatmapServiceTest` 验证 V2 legacy 的 `idleRatio=0`、V3 idleRatio 按 `I/(A+I)` 聚合、无数据仅返回文本条件、部分范围仍可出图。

### 阶段 5：渲染与文案

1. 先在 `HeatmapImageRendererTest` 固定帮派 0/25/50/75/100+ 五个主 RGB、Idle 比例为 0/50%/100% 时的连续暗化 RGB、无数据灰不等于 0 档颜色；不重复测试 Service 聚合。
2. 实现 `HeatmapColorScale` 和 renderer 的选色、双行副标题、图例与文案。
3. 对比图只修改副标题说明，不修改颜色和差值算法。
4. 生成最少三张固定夹具 PNG（个人 V3 Idle、帮派主色/暗色、含 legacy 提示的混合范围）供人工视觉复核；夹具放在测试 `target/`，不得提交二进制图片。

### 阶段 6：规范、验证与文档闭环

1. 完成修改文件的 Javadoc、POJO 字段注释、record `@param`、Liquibase remarks、未使用 import/常量清理。
2. 运行聚焦测试与真实 Mapper 测试，分开报告 testCompile 与 Surefire 执行数。
3. 运行 JDK 21 Maven 编译，检查本次文件无新增 deprecation。
4. AI 已在 `49bf811` 后完成独立 Review；无 P0/P1，实施状态与实际验证证据见 10.5。后续仅在新的业务需求或生产验收异常出现时重新开启技术变更。

---

## 9. 测试与验证方案（收敛）

风险等级：**L3**。理由：新增长期持久化表、日终补偿、跨 Redis/数据库数据源优先级和历史数据口径；但不涉及资金、权限、外部写入或分布式并发。

### 9.1 保留并修改的快速测试

| 测试文件 | 主证据职责 | 本次最小调整 |
|---|---|---|
| `src/test/java/pn/torn/goldeneye/torn/service/activity/ActivityEvidenceClassifierTest.java` | V3 证据互斥性 | 更新旧 Idle=active 预期，覆盖 6 个关键分类即可 |
| `src/test/java/pn/torn/goldeneye/torn/service/activity/TornActivityCollectServiceTest.java` | 采集单槽写入事实与失败语义 | 更新 active/idle 并集与配置来源合并的主路径；保留现有重入/拒绝测试 |
| `src/test/java/pn/torn/goldeneye/torn/service/activity/ActivityHeatmapServiceTest.java` | observed 分母、legacy/V3 加载优先级、部分数据展示 | 移除“必须 7 天才可出图”的旧断言；不重复测试 renderer 像素 |
| `src/test/java/pn/torn/goldeneye/torn/service/activity/HeatmapImageRendererTest.java` | 固定颜色、暗化与可读性 | 增加 5 档主/暗色边界；保留对比图色板回归 |
| `src/test/java/pn/torn/goldeneye/napcat/strategy/user/ActivityHeatmapStrategyImplTest.java` | 原指令与 at 用户接线 | 增加一条日期参数传递断言，保留既有 at 边界 |

### 9.2 新增的聚焦测试

| 测试文件 | 必须覆盖 | 明确不覆盖 |
|---|---|---|
| `src/test/java/pn/torn/goldeneye/torn/service/activity/query/ActivityQueryRangeParserTest.java` | 默认、从、截至、未来、格式错误、重复关键字 | 不测试 renderer、Redis、数据库 |
| `src/test/java/pn/torn/goldeneye/torn/service/activity/archive/ActivityDailyArchiveServiceTest.java` | 各非空索引侧完整后 marker 最后写入、失败不写 marker、ZSET 候选驱动补偿、启动/定时共享防重入 | 不用 mock 模拟 PostgreSQL UPSERT 细节 |
| `src/test/java/pn/torn/goldeneye/repository/mapper/activity/ActivityDailyArchiveMapperTest.java` | 真实 PostgreSQL 用户/帮派日包 UPSERT 与范围读取 | 不建大规模历史数据、并发矩阵或性能基准 |

真实 Mapper 测试使用 `@SpringBootTest`、`@Tag("shared-db")`、同线程 `@Transactional + @Rollback`，由生产 Mapper 实际执行 SQL。不得在测试里复制 SQL、手动插入业务 ID、调用 Torn API 或使用隔离 profile/隔离库。

### 9.3 命令与通过标准

在 Windows git-bash 使用 JDK 21：

```bash
JAVA_HOME="C:\\Program Files\\Java\\jdk-21" mvn.cmd test -Dtest="ActivityEvidenceClassifierTest,TornActivityCollectServiceTest,ActivityHeatmapServiceTest,HeatmapImageRendererTest,ActivityQueryRangeParserTest,ActivityDailyArchiveServiceTest,ActivityHeatmapStrategyImplTest"

JAVA_HOME="C:\\Program Files\\Java\\jdk-21" mvn.cmd -Pshared-db-test test -Dtest="ActivityDailyArchiveMapperTest"

JAVA_HOME="C:\\Program Files\\Java\\jdk-21" mvn.cmd compile -q -DskipTests -Dmaven.compiler.showDeprecation=true
```

若本轮涉及 Liquibase 启动，运行前先按共享数据库预检规范确认 changelog 现状和数据约束；迁移阻塞时停止后续依赖该表的集成测试，不能通过清理真实数据绕过。

不默认运行全量 Maven 测试；其余模块与本次 V3 调用链无直接关系。若发布流程明确要求全量门禁，再串行执行一次，禁止与上述 Maven 命令并行。

---

## 10. 开发交付与 AI Review 验收清单

### 10.1 开发交付必须提供

1. 修改文件清单与对应需求映射；
2. Liquibase 追加 changeSet、master include 和实际变更集 ID；
3. 三类 Maven 证据：生产 compile、聚焦 Surefire、真实 Mapper Surefire，包含退出码和测试执行数；
4. 日终归档日志样例（仅总数和耗时，无敏感数据）；
5. 三张 `target/` 下临时 PNG 的人工可读截图/说明，确认主色、Idle 暗色、legacy 提示和无数据 `—`；
6. 不提交 Redis RDB、`.env`、生产数据、测试 PNG、`target/` 或同步脚本产物。

### 10.2 P0 / P1 阻断项

| 等级 | 阻断条件 |
|---|---|
| P0 | 归档任务可把 V3 数据覆盖为零值/截断值；日期范围能读到不属于目标用户或帮派的数据；迁移破坏既有表/已执行 changeSet |
| P1 | `Idle` 仍进入 V3 active 主数字；V2/V3 同日被重复累计；归档失败仍写完成 marker；配置低段位帮派不能稳定纳入采集；日期边界/未来日期错误；无数据时伪造 0；帮派颜色未按冻结锚点渐变或 `I/(A+I)` 连续暗化规则实现 |

### 10.3 非阻断项

| 等级 | 处理方式 |
|---|---|
| P2 | 文案微调、PNG 字体细微差异、额外归档监控指标、历史 V2 回填便利性；记录后续建议，不阻断本轮 |
| P3 | 分区自动化、长期清理策略、可配置阈值、多时区、导出、更多图片样式；不得混入本轮实现 |

### 10.4 完成与停止条件

只有同时满足以下条件，才可判定本方案实施完成：

- [x] V3 采集口径、Idle 暗化、配置帮派并集、日期参数、日终归档全部按本文件落地；
- [x] V2 Redis 数据在 TTL 存续期仍能展示，且展示 legacy 提示，不伪造 idle；V2 帮派 Pipeline 顺序固定为 `online-count → member-count → observed`。
- [x] 新增表和 Mapper 已经真实 PostgreSQL 验证 UPSERT 与范围读取；
- [x] 聚焦测试和 JDK 21 编译通过，测试执行数真实进入 Surefire；
- [x] 无 P0/P1，且本轮未新增依赖、未使用生产声明、过时 API 或不必要基础设施；
- [ ] 生产部署后以只读 Redis/数据库检查确认：V3 Key 与 archive date ZSET 被写入、昨日 marker 只在各非空索引侧完整归档后出现、三张归档表行数增加、默认/从/截至命令均返回预期结果。

达到停止条件后，不新增 V2 全量迁移、全库 Redis 扫描、自动分区、TTL 续期、更多色板或额外测试矩阵。

### 10.5 已实施技术验收记录（2026.08.28）

- 代码基线：`a2adbc8..49bf811`；`git diff --check` 的修复提交范围与累计范围均通过。
- JDK 21 聚焦 V3 测试：101 tests，0 failures，0 errors。
- 真实共享 PostgreSQL Mapper / Spring / Liquibase：`ActivityDailyArchiveMapperTest` 3 tests，0 failures，0 errors；三个活动归档 changeSet 的已执行路径均为 `1.5.0/activity-heatmap-v3.yaml`。
- 第二轮独立 Review 确认 V2 帮派 Pipeline 构造、解包与 legacy `FactionDay` 的顺序一致；日终归档根据 DAO 实际 UPSERT 行数决定 marker，不会将短写入伪认为完整。
- 当前全量 Maven 仍受非活动热力图的 OC/股票测试历史失败影响，不作为本功能通过结论的反向证据；运维部署前后验收范围按上一条未完成的生产只读检查执行。
