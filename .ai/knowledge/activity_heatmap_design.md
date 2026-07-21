# 活跃度热力图设计方案

## 元信息

- 文档类型：功能设计知识库
- 适用项目：Golden-Eye
- 适用版本：1.2.10 及以上
- 最后更新：2026.07.21
- 维护人：Bai
- 状态：待实施

---

## 1. 目标

在保留“15 分钟采集 + Redis 紧凑存储 + BufferedImage 图片输出”总体架构的前提下，完成活跃度功能 V2 升级：

1. 修复标题和时间轴重合；
2. 修复个人活跃比例分母错误；
3. 修复一周数据被 28 天窗口稀释；
4. 同时兼容正常在线状态和隐藏在线状态的玩家；
5. 区分真实离线与采集缺失；
6. 修复当前成员名单污染帮派历史统计的问题；
7. 帮派图显示平均在线人数；
8. 帮派对比格内显示双方人数，而不是仅显示差值；
9. 使用固定 RGB 色板生成连续渐变；
10. 标题显示名称并保留 ID；
11. 保证采集和查询不存在 Redis N+1。

本方案不新增数据库表、不使用 Liquibase、不引入新 Maven 依赖。

---

## 2. 原始设计与可复用架构

### 2.1 产品定义

活跃度表示玩家在采样时段内的**估算在线/活跃状态**，不是攻击次数或战斗频率。

| 模式 | X 轴 | Y 轴 | 格内主数值 |
|---|---|---|---|
| 个人热力图 | 0～23 时 | 周一～周日 | 有效采样中的活跃比例 `%` |
| 帮派热力图 | 0～23 时 | 周一～周日 | 平均在线人数 |
| 帮派对比 | 0～23 时 | 周一～周日 | 双方平均在线人数 `A/B` |

### 2.2 原始技术方案

1. 从 `factionhof?cat=rank` 获取黄金及以上帮派；
2. 每 15 分钟调用 `/faction/{id}/members`；
3. 每个玩家每天使用一个 96 bit Redis Bitmap；
4. 每一位对应一个 15 分钟槽；
5. 查询最近 28 天，按星期几和小时聚合；
6. 使用 `BufferedImage + Graphics2D` 生成 PNG；
7. 通过 NapCat 发送图片。

### 2.3 继续复用的现有能力

- `TornActivityCollectService` 定时采集和 JVM 内防重入；
- 独立虚拟线程执行器；
- `TornApi.sendRequest()` 内置 API Key 负载均衡；
- Redis Pipeline 批量写入；
- 成员集合临时 key + `RENAME` 原子发布；
- 查询端 Pipeline 批量 `GET`；
- JVM 内按 Redis MSB-first 位序解析 Bitmap；
- `HeatmapImageRenderer` 的 BufferedImage 渲染能力；
- 个人、帮派、对比三条 Bot 指令链路。

V2 属于数据口径升级、模型修正和渲染重构，不推倒现有技术栈。

---

## 3. 当前问题与根因

### 3.1 标题与时间轴重合

标题区、时间轴区和网格区共用固定 `HEADER_HEIGHT`，普通图和对比图又使用不同的硬编码 Y 坐标，导致标题、副标题和时间轴重叠。

### 3.2 一周数据只有五种个人百分比

每小时每周只有 4 个 15 分钟样本，一周数据天然只能得到：

```text
0%、25%、50%、75%、100%
```

继续采集后粒度会改善：

| 完整周数 | 每个“星期几+小时”的样本数 | 最小步长 |
|---:|---:|---:|
| 1 | 4 | 25% |
| 2 | 8 | 12.5% |
| 3 | 12 | 8.33% |
| 4 | 16 | 6.25% |

但当前个人公式未严格除以实际有效采样槽，数据增加后可能超过 100%，不能仅靠等待更多数据解决。

### 3.3 帮派人数偏低

主要原因包括：

1. 查询窗口固定为 28 天，但实际仅有一周数据，仍按每个星期出现 4 次归一化，结果可能被约除以 4；
2. 只使用 `last_action.status` 会漏掉隐藏状态的玩家；
3. 只使用 `last_action.timestamp` 会漏掉长时间 Idle 但仍在线的玩家；
4. 使用当前成员名单回算历史数据，会错误处理新加入和已退出成员。

### 3.4 对比图信息不足

当前只保留 `faction1 - faction2` 差值矩阵，原始双方人数被丢弃，渲染器只能显示 `+5`、`-3`。

### 3.5 颜色不连续

现有颜色从绿色跳到橙色、红色，属于离散分类色，不是连续热力渐变；图例区间也与一周数据的实际值域不匹配。

### 3.6 标题只有 ID

`TornFactionHofVO.FactionHofEntry` 已包含帮派名，`TornFactionMemberVO` 已包含用户名，但当前没有持久化名称。

---

## 4. 活跃判定

### 4.1 双证据 OR 判定

不能只使用 status，也不能只使用 timestamp。V2 使用：

```text
statusActive = status 为 Online 或 Idle
recentAction = 0 <= collectedAt - lastAction.timestamp < 15分钟
estimatedActive = statusActive OR recentAction
```

| API 表现 | 判定 | 原因 |
|---|---|---|
| `Online` | 活跃 | API 明确提供在线证据 |
| `Idle` | 活跃 | API 明确提供空闲在线证据 |
| `Offline` + 15 分钟内有动作 | 活跃 | 兼容隐藏 Online/Idle 的插件或隐私设置 |
| `Offline` + 超过 15 分钟无动作 | 不活跃 | 没有足够的当前活跃证据 |
| status 缺失 + 15 分钟内有动作 | 活跃 | 使用动作证据降级 |
| status 缺失 + timestamp 无效 | 不活跃，但仍属于已观测 | API 成功返回成员，但没有活跃证据 |
| `last_action` 缺失 | 不活跃，但仍属于已观测 | 不把成功采集误判为漏采 |

### 4.2 15 分钟边界

采集周期本身为 15 分钟，因此最近动作证据使用同样的窗口。

实现时抽取纯函数：

```java
static ActivityEvidence classifyActivity(
        TornUserLastActionVO lastAction,
        long collectedAtEpochSecond)
```

`ActivityEvidence` 至少表达：

- `statusActive`；
- `recentAction`；
- `estimatedActive`。

必须为以下边界写测试：

- 恰好 15 分钟；
- timestamp 为 0；
- timestamp 轻微领先本机时间；
- timestamp 明显异常；
- status 大小写或未知值。

禁止因为未来时间戳而把玩家永久认定为活跃。

### 4.3 客观误差

该模型属于估算：

1. 玩家操作后立即退出，不满 15 分钟内仍可能被高估；
2. 玩家隐藏状态并持续在线，但超过 15 分钟没有动作，可能被低估；
3. API 没有字段明确区分真实 `Offline` 和隐藏状态。

产品文案使用“活跃度”或“估算在线人数”，不宣称精确实时在线。

---

## 5. V2 Redis 数据模型

### 5.1 版本隔离

旧数据和 V2 数据的判定方式、分母和历史帮派口径不同，不能直接混用。

```text
V1：activity:{userId}:{date}
V2：activity:v2:...
```

上线策略：

- V2 查询只读取当前有效 V2 key；
- 个人 V1 key 不主动删除，按 TTL 自然过期；
- 不迁移 V1，因为无法恢复 observed 和被漏记的证据；
- 帮派快照使用独立的 `activity:v2:faction-snapshot-v2:*` key，隔离早期 V2 整体覆盖写产生的损坏数据；旧帮派 V2 key 不迁移、不混算并按 TTL 自然过期；
- V2 满 7 个自然日并覆盖完整星期前，提示“V2 数据积累中”。

### 5.2 个人维度

```text
activity:v2:user:observed:{userId}:{yyyy-MM-dd}
activity:v2:user:status-active:{userId}:{yyyy-MM-dd}
activity:v2:user:recent-action:{userId}:{yyyy-MM-dd}
```

类型：Redis String Bitmap，每天 96 位，TTL 30 天。

语义：

- API 成功返回成员列表后，为每个有效成员设置 `observed`；
- `Online/Idle` 设置 `status-active`；
- 最近 15 分钟有动作设置 `recent-action`；
- 离线表示 `observed=1` 且两个活跃证据均为 0；
- 查询时在 JVM 对两个活跃 Bitmap 做 OR；
- 不重复保存派生的最终 active Bitmap。

### 5.3 帮派维度

历史帮派统计不再依赖当前成员 Set，而是在采集时保存当时的聚合快照：

```text
activity:v2:faction-snapshot-v2:online-count:{factionId}:{yyyy-MM-dd}
activity:v2:faction-snapshot-v2:member-count:{factionId}:{yyyy-MM-dd}
activity:v2:faction-snapshot-v2:observed:{factionId}:{yyyy-MM-dd}
```

建议数据格式：

- `online-count`：定长 Redis String，每槽保存一次估算在线人数；
- `member-count`：定长 Redis String，每槽保存该次响应的有效成员数；
- `observed`：96 bit Bitmap，表示该帮派该槽采集成功。

若每槽使用 1 个无符号字节，实施前必须证明人数上限不超过 255；无法保证时直接使用每槽 2 字节，禁止静默截断。

该模型解决：

- 新成员加入前的数据误算；
- 退帮成员的历史贡献丢失；
- 帮派查询读取大量个人 Bitmap；
- 查询性能随当前成员数线性增长。

### 5.4 名称缓存

```text
activity:v2:user:names     Hash<userId, latestUserName>
activity:v2:faction:names  Hash<factionId, latestFactionName>
```

- 帮派名在刷新 `factionhof` 时批量写入；
- 用户名在成功采集成员列表时批量写入；
- 查询端只读 Redis，不调用 Torn API；
- 名称不存在时回退为 ID；
- 标题使用 `名称 [ID]`，避免重名或改名歧义。

### 5.5 当前成员缓存

`faction:members:{factionId}` 可以继续保留给即时用途，但 V2 帮派历史热力图不再依赖它。

成员快照必须继续使用临时 key + `RENAME` 原子发布，禁止回退到 `DEL -> SADD`。

---

## 6. 聚合公式

### 6.1 基本原则

分母必须来自实际成功采集槽，不能来自：

- 查询请求的理论天数；
- 理论定时任务次数；
- active key 是否存在；
- 当前成员人数。

### 6.2 个人热力图

对于星期 `d`、小时 `h`：

```text
observedSamples(d,h)
  = 所有匹配日期中 observed Bitmap 对应4个槽的置位数

activeSamples(d,h)
  = 所有匹配日期中 (statusActive OR recentAction) 对应4个槽的置位数

personalRate(d,h)
  = activeSamples / observedSamples
```

边界：

- `observedSamples == 0`：显示无数据，不显示 `0%`；
- 结果必须在 `[0,1]`；
- 如果超界，应暴露聚合错误，禁止仅在渲染层截断掩盖问题。

### 6.3 帮派热力图

```text
averageOnlineCount(d,h)
  = Σ 每个成功采样槽的 onlineCount
    / 成功采样槽数量

onlineRatio(d,h)
  = Σ onlineCount
    / Σ memberCount
```

展示：

- 格内显示 `averageOnlineCount`；
- 背景颜色使用 `onlineRatio`；
- 不同人数规模的帮派颜色具有相同含义；
- 可显示采样期平均成员数，但不能替代格内在线人数。

### 6.4 帮派对比

服务层保留：

```text
faction1AverageOnline[7][24]
faction2AverageOnline[7][24]
```

格内显示：

```text
23/18
```

副标题明确顺序：

```text
帮派A [ID] / 帮派B [ID]
```

颜色输入：

```text
diff = faction1AverageOnline - faction2AverageOnline
```

- `diff > 0`：向 A 方紫色加深；
- `diff < 0`：向 B 方蓝色加深；
- 接近 0：中性灰；
- 格内不再显示 `+/-`。

颜色归一化上限使用本图 `abs(diff)` 的 P95，避免单个异常峰值压缩其他格子的颜色。P95 的计算方式必须通过固定测试，不允许渲染时随意替换算法。

### 6.5 对比共同有效采样

同一个格子只有在双方都有有效观测时才进行对比。

禁止将“一方有数据、另一方无数据”当作 `A/0` 或 `0/B`，否则会把漏采错误解释为人数差异。

### 6.6 数据充分性

建议规则：

1. 至少覆盖 7 个不同自然日；
2. 周一至周日每一行至少存在一个有效采样；
3. 未完整覆盖的小时格显示无数据；
4. 副标题可显示总体覆盖率：

```text
coverage = 实际 observed 槽数 / 查询窗口理论槽数
```

查询窗口仍可为最近 28 天，但归一化只使用 V2 observed 槽。

---

## 7. 固定 RGB 色板

> 本节 RGB 为方案固定值。实施时直接定义为常量并编写像素测试，禁止再次凭感觉选择颜色。

### 7.1 通用界面颜色

| 用途 | HEX | RGB | Java |
|---|---|---|---|
| 图片背景 | `#1E1E1E` | `(30, 30, 30)` | `new Color(30, 30, 30)` |
| 无数据格 | `#2D2D2D` | `(45, 45, 45)` | `new Color(45, 45, 45)` |
| 网格线 | `#3C3C3C` | `(60, 60, 60)` | `new Color(60, 60, 60)` |
| 主文字 | `#DCDCDC` | `(220, 220, 220)` | `new Color(220, 220, 220)` |
| 次文字 | `#A0A0A0` | `(160, 160, 160)` | `new Color(160, 160, 160)` |
| 无数据符号 | `#8A8A8A` | `(138, 138, 138)` | `new Color(138, 138, 138)` |
| 深色背景文字 | `#FFFFFF` | `(255, 255, 255)` | `Color.WHITE` |
| 浅色背景文字 | `#101010` | `(16, 16, 16)` | `new Color(16, 16, 16)` |

### 7.2 个人图与帮派图：8 锚点连续渐变

个人活跃比例和帮派在线成员比例共用固定的 Viridis 风格色板。该色板亮度基本单调递增，比绿→橙→红的离散跳色更适合表达连续强度。

| 比例锚点 | HEX | RGB | Java |
|---:|---|---|---|
| 0.000 | `#440154` | `(68, 1, 84)` | `new Color(68, 1, 84)` |
| 0.143 | `#46327E` | `(70, 50, 126)` | `new Color(70, 50, 126)` |
| 0.286 | `#365C8D` | `(54, 92, 141)` | `new Color(54, 92, 141)` |
| 0.429 | `#277F8E` | `(39, 127, 142)` | `new Color(39, 127, 142)` |
| 0.571 | `#1FA187` | `(31, 161, 135)` | `new Color(31, 161, 135)` |
| 0.714 | `#4AC16D` | `(74, 193, 109)` | `new Color(74, 193, 109)` |
| 0.857 | `#A0DA39` | `(160, 218, 57)` | `new Color(160, 218, 57)` |
| 1.000 | `#FDE725` | `(253, 231, 37)` | `new Color(253, 231, 37)` |

建议常量：

```java
private static final Color[] ACTIVITY_GRADIENT = {
        new Color(68, 1, 84),
        new Color(70, 50, 126),
        new Color(54, 92, 141),
        new Color(39, 127, 142),
        new Color(31, 161, 135),
        new Color(74, 193, 109),
        new Color(160, 218, 57),
        new Color(253, 231, 37)
};
```

映射方式：

1. 将比例限制为 `[0,1]`；
2. 计算其位于哪两个相邻锚点之间；
3. 对 RGB 三通道做线性插值；
4. 无数据格不进入渐变函数，固定使用 `(45,45,45)`。

图例只标：

```text
0%    25%    50%    75%    100%
```

图例的每个像素必须调用与格子相同的颜色函数生成，不能另写一套近似颜色。

### 7.3 帮派对比：9 锚点蓝—灰—紫连续渐变

帮派 B 优势使用蓝色，持平使用中性灰，帮派 A 优势使用紫色。

归一化值范围：

```text
-1.0 = B 方达到本图负向颜色上限
 0.0 = 双方持平
+1.0 = A 方达到本图正向颜色上限
```

| 归一化锚点 | 语义 | HEX | RGB | Java |
|---:|---|---|---|---|
| -1.00 | B 方强优势 | `#2166AC` | `(33, 102, 172)` | `new Color(33, 102, 172)` |
| -0.75 | B 方明显优势 | `#4393C3` | `(67, 147, 195)` | `new Color(67, 147, 195)` |
| -0.50 | B 方中等优势 | `#92C5DE` | `(146, 197, 222)` | `new Color(146, 197, 222)` |
| -0.25 | B 方轻微优势 | `#D1E5F0` | `(209, 229, 240)` | `new Color(209, 229, 240)` |
| 0.00 | 持平 | `#F2F2F2` | `(242, 242, 242)` | `new Color(242, 242, 242)` |
| +0.25 | A 方轻微优势 | `#E1D5EA` | `(225, 213, 234)` | `new Color(225, 213, 234)` |
| +0.50 | A 方中等优势 | `#C2A5CF` | `(194, 165, 207)` | `new Color(194, 165, 207)` |
| +0.75 | A 方明显优势 | `#9970AB` | `(153, 112, 171)` | `new Color(153, 112, 171)` |
| +1.00 | A 方强优势 | `#762A83` | `(118, 42, 131)` | `new Color(118, 42, 131)` |

建议常量：

```java
private static final Color[] COMPARISON_GRADIENT = {
        new Color(33, 102, 172),
        new Color(67, 147, 195),
        new Color(146, 197, 222),
        new Color(209, 229, 240),
        new Color(242, 242, 242),
        new Color(225, 213, 234),
        new Color(194, 165, 207),
        new Color(153, 112, 171),
        new Color(118, 42, 131)
};
```

映射步骤：

```text
scale = P95(abs(所有共同有效格子的 diff))
normalized = clamp(diff / max(scale, 最小安全值), -1, 1)
color = 在9个锚点间线性插值
```

如果所有共同有效格子差值均为 0，所有有效格统一使用 `(242,242,242)`。

无数据格仍使用 `(45,45,45)`，不能使用中性灰，否则无法区分“持平”和“双方无数据”。

### 7.4 文字颜色

统一使用 WCAG 相对亮度思想或现有亮度公式选择文字颜色。最低要求：

```java
boolean dark = color.getRed() * 0.299
        + color.getGreen() * 0.587
        + color.getBlue() * 0.114 < 150;
```

- 深色背景：`(255,255,255)`；
- 浅色背景：`(16,16,16)`。

阈值固定为 `150`，并为所有锚点写测试，避免改色后出现不可读文字。

---

## 8. 图片布局与交互

### 8.1 统一布局分区

图片纵向拆分为：

```text
顶部外边距
标题区
副标题/统计说明区
时间轴区
7×24 网格区
图例区
底部外边距
```

建议通过统一布局对象或常量公式计算坐标。普通图和对比图共用布局，禁止散落使用 `y=16/24/30/32`。

### 8.2 个人图

标题：

```text
用户名 [1234567] 活跃度热力图
```

格内显示整数百分比，例如 `38%`。

- 颜色：活跃比例；
- 无数据：`—` + `(45,45,45)`；
- 真实 0%：使用渐变 0% 的 `(68,1,84)`；
- 图例：连续渐变条，只标 0/25/50/75/100。

### 8.3 帮派图

标题：

```text
帮派名 [20465] 活跃度热力图
```

副标题：

```text
格内：平均在线人数｜颜色：在线成员比例｜最近28天有效采样
```

格内显示平均在线人数，背景按在线成员比例使用 `ACTIVITY_GRADIENT`。

### 8.4 对比图

标题：

```text
帮派活跃度对比
```

副标题：

```text
帮派A [ID] / 帮派B [ID]
```

格内显示 `A人数/B人数`，背景使用 `COMPARISON_GRADIENT`。

图例：

```text
B优势（蓝） ← 持平（灰） → A优势（紫）
```

---

## 9. 模型与职责拆分

### 9.1 VO

当前 `ActivityHeatmapVO` 同时承载个人、帮派、对比字段，不应继续增加互斥开关。

建议拆分：

```text
ActivityHeatmapVO                # 单图公共数据
PersonalActivityHeatmapVO        # 个人比例矩阵
FactionActivityHeatmapVO         # 在线人数矩阵 + 在线比例矩阵
ActivityComparisonHeatmapVO      # 双方人数矩阵 + 差异颜色输入
```

优先使用组合，不设计复杂继承层次。所有 POJO 字段必须有业务 Javadoc。

### 9.2 采集职责

`TornActivityCollectService` 保留调度和 API 编排职责，可按实际规模提取：

```text
ActivityEvidenceClassifier  # 纯函数判定
ActivityRedisWriter          # 组织单帮派一次 Pipeline 写入
ActivityRedisKeys            # V2 key 构造
```

禁止为拆分类创建无职责空壳。

### 9.3 查询职责

`ActivityHeatmapService`：

- 只读 Redis；
- 单次 Pipeline 批量获取 key；
- JVM 内做 Bitmap OR 和矩阵聚合；
- 不调用 Torn API；
- 不按成员、日期或小时逐条同步查询 Redis；
- 帮派查询命令数量不随当前成员数增长。

### 9.4 渲染职责

`HeatmapImageRenderer` 只负责布局、颜色和绘制，不负责业务聚合或名称查询。

可在重复度明显时提取：

```text
HeatmapLayout
HeatmapColorScale
```

---

## 10. 预计修改文件

### 10.1 主要修改

- `src/main/java/pn/torn/goldeneye/torn/service/activity/TornActivityCollectService.java`
- `src/main/java/pn/torn/goldeneye/torn/service/activity/ActivityHeatmapService.java`
- `src/main/java/pn/torn/goldeneye/torn/service/activity/HeatmapImageRenderer.java`
- `src/main/java/pn/torn/goldeneye/torn/model/activity/ActivityHeatmapVO.java`
- `src/main/java/pn/torn/goldeneye/napcat/strategy/user/ActivityHeatmapStrategyImpl.java`
- `src/main/java/pn/torn/goldeneye/napcat/strategy/user/ActivityCompareStrategyImpl.java`

### 10.2 可能新增

- `src/main/java/pn/torn/goldeneye/torn/model/activity/PersonalActivityHeatmapVO.java`
- `src/main/java/pn/torn/goldeneye/torn/model/activity/FactionActivityHeatmapVO.java`
- `src/main/java/pn/torn/goldeneye/torn/model/activity/ActivityComparisonHeatmapVO.java`
- `src/main/java/pn/torn/goldeneye/torn/service/activity/ActivityEvidenceClassifier.java`
- `src/main/java/pn/torn/goldeneye/torn/service/activity/ActivityRedisKeys.java`
- `src/main/java/pn/torn/goldeneye/torn/service/activity/HeatmapColorScale.java`

### 10.3 测试

- `src/test/java/pn/torn/goldeneye/torn/service/activity/TornActivityCollectServiceTest.java`
- `src/test/java/pn/torn/goldeneye/torn/service/activity/ActivityHeatmapServiceTest.java`
- 新增 `ActivityEvidenceClassifierTest.java`
- 新增 `HeatmapImageRendererTest.java`

---

## 11. 实施顺序

### Phase 1：固定判定口径

1. 先写状态证据、最近动作证据和 OR 结果的失败测试；
2. 覆盖 `Online`、`Idle`、隐藏状态但近期动作、真实离线、未来时间戳和 null；
3. 固定 15 分钟边界；
4. 实现纯判定函数。

### Phase 2：建立 V2 采集模型

1. 新增 V2 key 构造；
2. 写个人 observed/status/recent-action；
3. 写帮派 online-count/member-count/observed；
4. 批量写名称；
5. 保持单帮派一次 Pipeline；
6. API 失败时不写 observed；
7. 验证空成员与 null 响应语义。

### Phase 3：重写查询聚合

1. 先写一周数据不被 28 天稀释的测试；
2. 写个人比例不超过 100% 的测试；
3. 写 observed=0 显示无数据的测试；
4. 个人查询 Pipeline 读取三组 Bitmap 并做 OR；
5. 帮派查询读取历史聚合快照；
6. 对比一次读取双方数据；
7. 删除对当前成员 Set 的历史聚合依赖；
8. 验证无 Redis N+1。

### Phase 4：拆分模型和适配指令

1. 新增个人、帮派、对比 VO；
2. 补齐字段 Javadoc；
3. 对比模型保留双方人数；
4. 适配 Bot Strategy；
5. 保持原指令参数格式兼容。

### Phase 5：重构渲染

1. 写布局不重叠测试；
2. 统一布局坐标；
3. 直接使用本文固定 RGB；
4. 实现相邻锚点线性插值；
5. 个人图使用五个刻度；
6. 帮派格内人数、颜色按在线比例；
7. 对比格内 `A/B`；
8. 区分无数据和真实 0；
9. 用像素采样验证所有锚点和中间插值；
10. 输出三张固定样例 PNG 人工验收。

### Phase 6：上线与观测

1. V1/V2 key 完全隔离；
2. V1 通过 TTL 自然过期；
3. V2 首周提示数据积累中；
4. 记录采集成功数、失败数、耗时和覆盖槽数；
5. 对 20465 进行只读核对；
6. 一周后评价星期模式，四周后达到 6.25% 的个人比例粒度。

---

## 12. 验收标准

### 12.1 判定

- `Online + stale timestamp` → 活跃；
- `Idle + stale timestamp` → 活跃；
- `Offline + recent timestamp` → 活跃；
- `Offline + stale timestamp` → 不活跃；
- `null status + recent timestamp` → 活跃；
- `null lastAction` → 不活跃但已观测；
- 15 分钟边界符合约定；
- 异常未来时间戳不会永久活跃。

### 12.2 聚合

- 一周数据查询 28 天不被除以 4；
- 两周完整在线仍为 100%；
- 全部已观测且不活跃为 0%；
- 未观测显示无数据；
- 定时任务漏采不会降低比例；
- 帮派 24 人样本仍聚合为约 24，不变成 6；
- 加入/退出成员不改变已保存的帮派历史；
- 对比格显示 `23/18`；
- 双方覆盖不同的格子不强行对比。

### 12.3 Redis 性能

- 单帮派采集为一次 API + 一次 Pipeline；
- 个人 28 天查询不按小时发送命令；
- 帮派查询命令数不随成员数增长；
- 对比不会重复完整读取双方数据；
- 不使用 `KEYS`；
- 不在循环中同步调用 `setBit/expire`；
- 成员快照继续原子发布。

### 12.4 图片

- 标题、副标题、时间轴和网格不重叠；
- 图像尺寸符合布局公式；
- 个人图例只标 0/25/50/75/100；
- 所有固定 RGB 锚点像素完全匹配本文；
- 中间值符合线性插值计算；
- 对比负值、0、正值分别为蓝、灰、紫；
- 无数据颜色与真实 0% 不同；
- 文字颜色按固定亮度阈值切换；
- Base64 可解码为合法 PNG；
- 中文名称在 Docker 字体环境下可显示。

### 12.5 构建

使用 JDK 21，通过 Windows `mvn.cmd` 执行：

```text
mvn test -Dtest=ActivityEvidenceClassifierTest,TornActivityCollectServiceTest,ActivityHeatmapServiceTest,HeatmapImageRendererTest
mvn test
mvn compile -DskipTests -Dmaven.compiler.showDeprecation=true
```

---

## 13. 风险与明确决策

### 13.1 Redis 内存

个人从 1 个 Bitmap 增加为 3 个，帮派增加三个日级 key。Value 仍然很小，但 Redis key 元数据可能大于数据本身。上线后监控：

- `used_memory`；
- `expired_keys`；
- `evicted_keys`；
- key 数量；
- TTL 是否正确。

### 13.2 时区

热力图小时必须使用显式产品时区，禁止依赖容器默认时区。实施前在配置或常量中确定：

- `Asia/Shanghai`；或
- Torn Time；或
- 其他明确时区。

### 13.3 今日未完整小时

当天和当前小时样本天然不完整。由于使用实际 observed 分母，不会造成比例错误，但样本量较少，副标题应显示覆盖率。

### 13.4 已确认的产品口径

- 活跃判定：`Online/Idle OR 最近15分钟有动作`；
- V2 重新积累，不混入 V1；
- 帮派格内显示平均在线人数，背景按在线成员比例着色；
- 对比格内显示 `A/B`，背景按人数差着色；
- 标题使用 `名称 [ID]`；
- 颜色直接使用本文固定 RGB，不在实施时重新选色。

---

## 14. 结论

V2 核心为：

```text
双证据活跃判定
+ observed 采集覆盖
+ 帮派槽位级历史快照
+ 实际采样分母
+ V2 Redis key
+ 名称缓存
+ 固定 RGB 连续渐变
+ 清晰的图片布局
```

该方案同时解决现有六项展示问题、20465 人数偏低、隐藏在线状态、历史成员污染和漏采分母问题，并保持查询纯 Redis、采集 Pipeline 化和无 N+1。
