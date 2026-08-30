# OC 串行准备状态机

## 1. 文档定位

本文是 OC 串行准备、`status`、`readyTime`、`progress` 和空转/停转语义的领域基准文档。

后续 OC 规划、分配、通知、表格图片和数据验收必须复用本文定义，不得根据字段名称自行推导另一套含义。

## 2. 核心机制

OC 准备不是所有成员同时准备，而是严格串行：

- 同一时刻只有一个成员处于准备流程。
- 每个成员的准备周期为 24 小时。
- 成员按照加入顺序进入准备队列。
- 当前成员的 `progress` 从 0 逐步增加到 100。
- 当前成员完成后，准备流程才推进到下一名成员。
- `readyTime` 表示当前串行准备链的结束时间。
- `readyTime` 不是实际执行完成时间。

因此，成员加入越晚、前面排队的准备阶段越多，最终 `readyTime` 越晚。

## 3. OC 状态

### 3.1 Recruiting

人员尚未全部加入时，OC 状态为：

```text
Recruiting
```

Recruiting 可能包含三种业务情况：

1. 无人，尚未开始准备。
2. 有人且当前准备链正常推进。
3. 当前准备窗口已结束，但后续成员还没有加入，发生停转。

Recruiting 不等于“没有空转”。当 `readyTime - now > 24h` 时，说明已经有多个后续成员加入，当前准备链中存在空转。

### 3.2 Planning

人员全部加入后，OC 状态为：

```text
Planning
```

Planning 仍然可能存在空转。比如一个 6 人 OC 在同一时刻加入 6 人：

- 第 1 人需要准备 24 小时。
- 后续 5 人需要依次等待前序准备阶段。
- 最终 `readyTime` 比首人加入时间晚 6 天。
- 因此满员后仍需要空转 5 天。

Planning 只有在剩余准备时间不超过 24 小时、且接近执行时，才显示预计执行时间。

## 4. readyTime 推进公式

按加入顺序记：

```text
J1, J2, ..., Jn
```

其中 `Ji` 是第 i 名成员的加入时间；按准备队列记：

```text
R1, R2, ..., Rn
```

其中 `Ri` 是第 i 名成员加入后形成的 `readyTime`。

计算公式：

```text
R1 = J1 + 24h
Ri = max(Ji, R(i-1)) + 24h    (i > 1)
```

实际数据中 OC 表保存的是当前最新的 `Rn`。

无人 OC：

```text
readyTime = null
```

不得使用 `createTime`、最后更新时间或任意默认时间替代无人 OC 的 `null`。

## 5. 空转与停转

### 5.1 空转

空转表示后续成员已经加入，但必须等待前序准备阶段结束。它既可能发生在 Recruiting，也可能发生在 Planning。

判断当前 OC 是否存在空转：

```text
delta = readyTime - now

delta > 24h => 存在空转
```

标题文案：

```text
还需空转xx小时xx分钟
```

空转的剩余时长使用当前 `readyTime - now`，不计算成员历史等待时长总和。

### 5.2 停转

停转表示 Recruiting 状态下，当前准备窗口已经结束，但下一名成员还未加入：

```text
status == Recruiting
now > readyTime
```

标题文案：

```text
已停转
```

停转不是空转，不能继续展示“后停转”。

### 5.3 停转前的倒计时

Recruiting 状态下，尚未超过 `readyTime` 且剩余时间不超过 24 小时时，表示没有后续成员造成新的排队空转，展示下一次停转倒计时：

```text
status == Recruiting
now <= readyTime
delta <= 24h
```

标题文案：

```text
xx小时xx分后停转
```

`delta == 24h` 作为严格大于空转的边界，归入停转倒计时；只有严格 `delta > 24h` 才进入空转文案。`delta < 0` 不属于该倒计时分支，应按 Recruiting 已停转处理。

## 6. 预计执行

Planning 状态下：

```text
0 <= delta < 24h
```

表示当前串行准备链已进入最后 24 小时，不再展示空转，展示预计执行时间：

```text
预计HH:mm开始执行
```

预计执行时间统一为：

```text
plannedExecuteTime = readyTime 截断到分钟 + 1分钟
```

这与 OC 完成延误通知的计划时间口径一致。

`plannedExecuteTime` 是计划时间，不是 Torn 返回的独立 `execute_at`，不能描述为实际执行时间。

如果 `now > readyTime` 但仍然是 Planning，本文只定义其已经越过准备完成点；图片展示必须继续沿用既有 Planning/完成检测业务约定，不能把该场景改写为 Recruiting 停转。

## 7. 标题状态判定总表

标题时间文案只允许出现一个，按以下规则从上到下判断：

| 顺序 | 条件 | 文案 |
|---:|---|---|
| 1 | `readyTime == null` | 不展示时间 |
| 2 | `status == Recruiting && now > readyTime` | `已停转` |
| 3 | `status ∈ {Recruiting, Planning} && delta > 24h` | `还需空转xx小时xx分钟` |
| 4 | `status == Recruiting && now <= readyTime && delta <= 24h` | `xx小时xx分后停转` |
| 5 | `status == Planning && 0 <= delta < 24h` | `预计HH:mm开始执行` |

实现要求：

- 条件互斥，不能拼接多种文案。
- Recruiting 的停转优先于空转判断，因为 `now > readyTime` 时不能再使用未来倒计时。
- 空转判断优先于执行判断，因为 `delta > 24h` 说明 Planning 仍有排队准备阶段。
- 对不属于 Recruiting/Planning 的状态不推导当前准备文案。
- `readyTime == null` 时不进行时间减法。

## 8. progress 与成员图标

槽位是否有人由 `userId` 判断：

```text
userId == null => 空槽
userId != null => 已加入成员
```

空槽的本地 `progress` 可能为 0，因此不能使用 `progress` 代替 `userId`。

对已加入成员，`progress` 业务状态如下：

| progress | 状态 | 图标 |
|---:|---|---|
| `0` | 空转 | `💤` |
| `0 < progress < 100` | 准备中 | `⏳` |
| `progress = 100` | 准备完成 | `✅` |

道具状态为显式不可用时：

```text
requiredItemAvailable == false => 缺少道具
```

缺少道具图标：

```text
⚠️
```

成员状态优先级：

```text
空转 > 缺少道具 > 准备完成 > 准备中
```

因此：

- 已加入且 `progress = 0`：只显示 `💤`。
- 已加入、非空转且明确缺道具：只显示 `⚠️`。
- 已加入、无异常且 `progress = 100`：显示 `✅`。
- 已加入、无异常且 `0 < progress < 100`：显示 `⏳`。
- 空槽：不显示图标。
- 同一成员最多显示一个图标。

`progress` 为 `null`、小于 0 或大于 100 时不得猜测，不得自动归类为缺道具或空转。

## 9. 时间示例

### 9.1 单人 Recruiting

```text
now       = 08-30 16:00
readyTime = 08-31 16:00
status    = Recruiting
```

```text
24小时后停转
```

### 9.2 Recruiting 中发生空转

```text
readyTime - now = 30小时
status          = Recruiting
```

```text
还需空转30小时
```

### 9.3 Recruiting 已停转

```text
now       > readyTime
status    = Recruiting
```

```text
已停转
```

### 9.4 六人同时加入

```text
J1 = J2 = J3 = J4 = J5 = J6 = 08-30 16:00
```

```text
R1 = 08-31 16:00
R2 = 09-01 16:00
R3 = 09-02 16:00
R4 = 09-03 16:00
R5 = 09-04 16:00
R6 = 09-05 16:00
```

满员后：

```text
status = Planning
readyTime - now = 5天
```

标题：

```text
还需空转120小时00分钟
```

最终计划执行时间：

```text
09-05 16:01
```

### 9.5 Planning 接近执行

```text
status          = Planning
0 <= readyTime-now < 24h
```

标题：

```text
预计HH:mm开始执行
```

## 10. 禁止事项

- 不把 Planning 解释为立即执行。
- 不把 Recruiting 解释为没有空转。
- 不把 `readyTime - now` 小于 24 小时的 Recruiting 误判为空转。
- 不把已经超过 `readyTime` 的 Recruiting 继续显示为“后停转”。
- 不把历史空转累计值替代当前剩余空转时间。
- 不用 `progress != 0` 判断槽位是否有人。
- 不用用户在线、旅行、住院或监狱状态参与本文状态机。
- 不把计划执行时间称为实际执行时间。
- 不在不同 OC 功能中复制另一套状态判断。
