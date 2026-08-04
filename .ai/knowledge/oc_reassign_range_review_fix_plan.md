# PN/NOV大锅饭OC范围实施后Review一次性修复方案

## 1. 文档信息

- 文档类型：一次性Review修复技术方案
- 适用项目：Golden-Eye
- 适用版本：1.2.12及以上
- 创建日期：2026-08-03
- 技术方案负责人：AI技术专家
- Review对象：`reassign-range`分支当前未提交实现
- 原始技术基线：`.ai/knowledge/oc_reassign_range_technical_design.md`
- 当前状态：待工程师修复
- 技术验收状态：Review未通过
- 生命周期：临时文档；下一轮Review通过后由AI技术专家删除

> 本文不修改、不替代原始技术实施方案。原始方案继续定义业务范围、名单、生效日期、查询口径、校准流程及上线验收；本文只定义本轮Review发现的实现缺陷、修复边界和追加验收项。工程师必须同时遵守两份文档，不得以本文扩大或缩小原方案。

---

## 2. 联合验收规则

下一轮Review使用以下两份文档联合验收：

1. `.ai/knowledge/oc_reassign_range_technical_design.md`
2. `.ai/knowledge/oc_reassign_range_review_fix_plan.md`

优先级与职责：

- 原始技术方案负责稳定业务与总体技术契约；
- 本文负责本轮Review缺陷修复契约；
- 两者不存在替代关系，必须全部满足；
- 如工程师认为两文档存在冲突，必须停止实施并交由AI技术专家裁决，禁止自行选择或改写口径。

下一轮Review通过后：

1. 原始技术方案保持冻结，不修改实现基线、状态或验收记录；
2. 由AI技术专家删除本文；
3. 删除前确认本文所有追加验收项已经并入实际实现和测试证据；
4. 同步移除`.ai/knowledge/file_location.md`中的临时索引；
5. 工程师不得提前删除、重命名或修改本文的验收结论。

---

## 3. 本轮Review结论

当前实现方向基本符合原始方案，但存在收益正确性阻断：

| 编号 | 级别 | 问题 | 发布影响 |
|---|---|---|---|
| R1 | P0 | 单条OC没有独立事务，批次catch可能提交残缺链 | 收益明细/汇总永久缺失 |
| R2 | P0 | 月度总奖励按金额`distinct()` | 同额不同OC被少算 |
| R3 | P1 | 链父节点只按名称匹配 | 同名不同等级可能永久等待 |
| R4 | P1 | 异常历史链只跳过，无闭环统计 | 校准无法明确证明完整 |
| R5 | P1 | 防重入测试缺少异常释放、不同帮派真实并发 | 并发契约证据不足 |
| R6 | P2 | 链回溯与历史income查询放大 | 校准事务时间随历史增长 |
| R7 | P2 | 测试全局状态和summary清理不完整 | 测试顺序依赖、污染开发库 |

在R1、R2及所有P1修复完成并通过联合验收前：

- 禁止上线；
- 禁止在正式环境执行`OC校准`；
- 禁止更新原始方案为“实施完成”或“技术验收通过”。

---

## 4. 修复范围与禁止事项

### 4.1 允许修改

根据最终实现需要，可修改或新增：

- `TornOcBatchIncomeService`
- `TornOcIncomeService`
- 一个独立的单链事务Worker/Service
- 与批量收益候选、链配置、income完整性有关的DAO/Mapper
- 本次相关测试类
- 必要的轻量内部DTO/record

### 4.2 原实现应保留

除非为修复本文问题确有必要，以下已实现内容应保持：

- PN与NOV名单；
- PN `2026-08-01 00:00:00`边界；
- NOV `2026-07-01 00:00:00`边界；
- 普通收益按帮派、名称、生效日期排除；
- 排行榜和个人明细复用统一SQL规则；
- `torn_setting_oc_chain`作为链关系唯一配置来源；
- 单实例按帮派JVM防重入；
- 不修改飞书普通收益同步；
- 不删除、迁移或重写`torn_faction_oc_benefit`。

### 4.3 禁止扩大范围

本轮修复禁止：

- 修改原始方案文档；
- 增加收益模式字段、迁移状态机或一次性数据迁移服务；
- 改变PN/NOV日期或OC名单；
- 改变收益分配公式和岗位系数；
- 删除现有历史重复income；
- 未经确认新增Liquibase changeSet或数据库唯一约束；
- 引入Redis锁、ShedLock或分布式任务框架；
- 修改个人收益图片布局和文案；
- 顺便重构非本需求代码。

---

## 5. R1：单链独立事务与原子回滚

### 5.1 问题模型

当前结构为：

```text
batchCalculateIncome() @Transactional
  → 循环候选OC
    → calculateAndSaveIncome() @Transactional(REQUIRED)
    → 外层catch异常后继续
```

内外事务默认合并为同一个事务。若一条链写入部分income后在后续步骤或summary阶段失败，异常被外层捕获，已写部分可能随批次事务提交。下一轮又可能因“任一节点已有income”而永久跳过。

### 5.2 目标结构

必须调整为：

```text
TornOcBatchIncomeService（非事务防重入门面）
  acquire faction lock
  try
    查询候选和准备批次上下文
    for each leaf
      TornOcIncomeTransactionWorker.processSingleChain(leafId)
        @Transactional(propagation = REQUIRES_NEW, rollbackFor = Exception.class)
        校验链完整性
        生成整链income
        重算受影响月份summary
      独立事务返回后统计成功
  finally
    release faction lock
```

类名可结合项目规范调整，但必须是独立Spring Bean，确保通过代理调用，不能把`REQUIRES_NEW`私有方法写回同一个Service进行自调用。

### 5.3 防重入锁与事务提交顺序

`batchCalculateIncome()`外层不得再持有覆盖整批的`@Transactional`。

必须保证：

```text
单链事务提交/回滚完成
→ Worker方法返回/抛异常
→ 外层处理结果
→ 批次结束
→ finally释放帮派锁
```

这样锁不会在Spring事务真正提交前释放。

### 5.4 单链事务职责

`processSingleChain`至少完成：

1. 根据叶子ID重新查询当前OC；
2. 重新校验其仍属于目标帮派、完成状态、大锅饭名单、扫描时间范围；
3. 重新校验当前叶子无真实后继；
4. 回溯完整`previousOcId`链；
5. 校验整链当前没有部分income异常；
6. 调用现有收益算法生成整链明细；
7. 重算该链最终结算月份的summary；
8. 方法正常返回后事务才允许提交。

批次查询只是候选快照，不能替代事务内最终校验。

### 5.5 异常语义

- 任一income批量写入失败：整链回滚；
- summary查询、更新或新增失败：整链income与summary修改全部回滚；
- 一条链失败：其他链的独立事务不受影响；
- 异常必须穿过单链事务边界，再由批量服务捕获并统计；
- 禁止在Worker内部吞异常后正常返回；
- 日志必须包含`factionId`、`leafOcId`、`leafOcName`、`chainOcIds`和异常。

### 5.6 已有部分income

事务内发现链中已有部分income时，不得自动追加、删除或重建。

本轮行为：

```text
识别为ABNORMAL_PARTIAL_INCOME
→ 不新增任何收益
→ 事务只读退出或抛出明确业务异常
→ 外层计入abnormalCount
→ 输出链ID和已有income节点
```

因为历史数据清理需要独立确认，本轮只识别和阻止扩大污染。

---

## 6. R2：月度奖励按结算单元去重

### 6.1 禁止金额去重

必须删除以下语义：

```java
.mapToLong(TornFactionOcIncomeDO::getTotalReward)
.distinct()
.sum()
```

`totalReward`不是业务唯一键。不同OC或不同链可以拥有相同奖励金额。

### 6.2 结算单元定义

月度总奖励按“实际奖励结算叶子”计一次：

- 单步OC：自身是结算叶子；
- 成功链：最终叶子是结算单元；
- 同一链所有步骤和所有成员共享该叶子的奖励，但月度只计一次；
- 不同叶子即使奖励金额相同，也分别计入；
- 失败终点奖励为0，道具成本仍按实际明细统计。

### 6.3 无Schema变更实现

本轮不增加字段，推荐使用现有`torn_faction_oc`关系计算：

1. 获取目标月份的income记录；
2. 提取涉及的`oc_id`；
3. 批量查询这些OC及链关系所需节点；
4. 构建`ocId → 结算叶子Id`映射；
5. 成功记录按结算叶子Id分组；
6. 每个叶子只取一次`totalReward`；
7. 对所有结算叶子的奖励求和。

如果当前月income包含跨月链父节点，必须将父节点归到最终叶子完成月份，沿用原方案和现有跨月测试口径。

### 6.4 一致性校验

同一结算叶子分组内如出现不同`totalReward`：

- 视为数据异常；
- 禁止任取一个值静默继续；
- 记录叶子ID、涉及OC ID和不同金额；
- 本次单链事务应失败并回滚，历史汇总重算应明确报错。

### 6.5 汇总重算次数

禁止每条链成功后无条件多次全月扫描并写summary。

低侵入选择二选一：

**方案A（推荐）**：每条链独立事务内重算其结算月份，优先保证原子性；接受历史补算期间同月多次重算，但应先完成R6范围优化。

**方案B**：单链事务只写明细，批次末按月份统一重算。仅当能够证明“明细提交成功但批次中断后summary仍有可靠补偿入口”时可用，否则不得为了性能牺牲明细与summary一致性。

本轮默认采用方案A。

---

## 7. R3：链配置按完整维度匹配

### 7.1 配置键

链父节点匹配必须使用：

```text
parentOcName + parentRank
```

推荐新增内部不可变值对象：

```text
OcKey(name, rank)
```

不得继续使用`Set<String> chainParentNames`。

### 7.2 配置过滤

加载链配置时必须只使用：

```text
enabled = true
AND deleted = 0
```

若MyBatis-Plus全局逻辑删除已自动追加`deleted = 0`，测试仍需通过真实DAO查询证明；业务方法命名和Javadoc应明确只返回有效配置。

### 7.3 等待规则

仅以下情况等待后继：

```text
状态 = Successful
AND OcKey(name, rank)属于有效链父节点
AND 当前数据库不存在真实后继
```

失败父节点仍按终点计算损失；同名但rank不匹配的成功OC作为独立终点处理。

---

## 8. R4：异常链统计和校准闭环

### 8.1 批次结果

批量计算至少统计：

- `candidateCount`
- `successCount`
- `failureCount`
- `waitingChainParentCount`
- `alreadyCalculatedCount`
- `abnormalPartialIncomeCount`

不允许将异常部分income归入普通`skippedCount`。

### 8.2 日志

批次结束日志必须包含：

```text
factionId
startTime
candidateCount
successCount
failureCount
waitingCount
alreadyCalculatedCount
abnormalCount
```

每条异常链单独输出：

```text
leafOcId
leafOcName
chainOcIds
existingIncomeOcIds
```

### 8.3 验收语义

OC校准后：

- `failureCount > 0`：补算失败；
- `abnormalCount > 0`：补算未闭环，需要人工处理；
- 等待父节点可等待后续分页/刷新，不算失败，但最终验收前必须确认其真实后继或终态；
- 只有待计算终点为0、failure为0、abnormal为0时才可进入排行榜验收。

本轮不实现历史异常自动修复，不删除当前数据库已有4组重复明细。

---

## 9. R5：防重入与事务测试

### 9.1 同帮派并发

必须真实调用批量入口，而非只测试锁辅助方法：

1. 第一线程获取锁并进入Worker前阻塞；
2. 第二线程调用同一帮派；
3. 断言第二线程直接返回且未执行Worker；
4. 释放第一线程；
5. 断言只产生一套完整income和summary。

### 9.2 不同帮派并发

使用PN和NOV或两个隔离测试帮派：

1. 两线程同时调用；
2. 两个线程均能进入各自Worker；
3. 任一线程释放前，另一个已经进入；
4. 最终各自产生一套收益；
5. 证明没有全局串行锁。

### 9.3 异常后释放

故障注入让Worker抛异常：

1. 首次批次失败；
2. 断言锁在事务回滚后释放；
3. 取消故障；
4. 再次调用能够正常处理同一帮派。

### 9.4 提交窗口

若测试结构允许，应阻塞在单链事务返回前并启动第二调用，证明锁覆盖事务提交完成。至少要保证生产结构是非事务门面调用独立事务Worker，`finally`位于Worker返回之后。

---

## 10. R6：查询放大控制

### 10.1 禁止全历史income预加载

不得继续查询指定帮派全部历史income ID。

调整为：

1. 批量收集候选链涉及的OC ID；
2. 一次查询：

```text
faction_id = ?
AND oc_id IN (candidateChainOcIds)
```

3. 返回当前批次相关的existing income ID。

### 10.2 消除链回溯N+1

候选和Worker不应对每个父节点重复`getById()`。

推荐：

- 对当前候选批次批量加载涉及的OC节点；
- 构建`Map<Long, TornFactionOcDO>`；
- 在内存回溯；
- 单链事务内若需重新校验，允许对该链执行一次受控批量查询，但不得逐节点N次查询。

### 10.3 性能证据

工程师应提供目标真实数据规模：

- PN/NOV候选数量；
- 最大链长度；
- 单次批次SQL次数；
- 不存在“候选数 × 链长”的父节点查询。

---

## 11. R7：测试隔离和清理

### 11.1 静态Map恢复

测试修改`TornConstants.ROTATION_OC_NAME`前保存原值，`@AfterEach`中：

- 原本存在则恢复；
- 原本不存在则删除测试键。

不得污染后续测试。

### 11.2 数据库清理

非事务并发测试必须清理：

1. `torn_faction_oc_income_summary`
2. `torn_faction_oc_income`
3. `torn_faction_oc_slot`
4. `torn_faction_oc`

按外键/依赖顺序执行。当前遗留的测试summary：

```text
faction_id = 999002
year_month = 2026-04
user_id = 888002
```

由工程师在测试修复时清理；不得触碰正式帮派数据。

### 11.3 测试环境边界

Spring数据库测试应避免启动无关外部连接；如项目现有结构无法低成本隔离，可保留完整上下文，但飞书/NapCat错误日志不得影响断言。测试必须真实调用生产Service/DAO/Mapper，不能用SQL字符串断言替代。

---

## 12. 必须新增或加强的测试

### 12.1 单链原子回滚

至少覆盖：

- 第一节点写入后失败；
- 最终节点写入后、summary前失败；
- summary写入失败；
- 每个场景income与summary均无部分提交；
- 修复故障后重试成功。

### 12.2 月度奖励结算单元

至少覆盖：

- 同月两个单步OC奖励相同，两个都计入；
- 同月两条链奖励相同，两条都计入；
- 一条链多个节点、多个成员，只计一次奖励；
- 单步OC和链奖励相同，分别计入；
- 失败OC奖励为0，道具损失计入；
- 同一叶子出现不同`totalReward`时fail-closed。

### 12.3 链配置

至少覆盖：

- `name + rank`匹配时成功父节点等待；
- 名称相同、rank不同不等待；
- `enabled=false`不等待；
- 逻辑删除配置不等待；
- 失败父节点立即结算损失；
- 三条生产链均覆盖。

### 12.4 异常历史链

至少覆盖：

- 祖先部分income、叶子无income时识别为异常；
- 不新增任何明细；
- `abnormalCount`增加；
- 日志/结果含完整链信息；
- 不误报为成功或普通幂等跳过。

### 12.5 原方案回归

必须继续通过原始方案第14节全部用例，尤其：

- 日期精确边界；
- 排行榜五类入口一致性；
- 普通/大锅饭互斥；
- 跨月链归叶子月份；
- 其他大锅饭帮派行为不变。

---

## 13. 数据库只读验收

修复完成后提供以下只读结果。

### 13.1 重复明细基线

执行前后分别统计：

```text
GROUP BY oc_id, user_id, position
HAVING COUNT(*) > 1
```

已知基线为4组历史重复。修复和测试不得增加新的重复组。

### 13.2 部分链检测

对目标日期范围中的叶子链检查：

- 无income；
- 全链完整income；
- 部分income异常。

三类必须互斥统计。最终正式补算验收要求：

```text
待计算终点 = 0
部分income异常 = 0
本次新增重复 = 0
```

历史已知异常如需清理，另行申请数据修复授权。

### 13.3 奖励守恒

按帮派和月份检查：

```text
成功结算叶子原始奖励总和
=
income_summary.total_reward代表的月度总奖励
```

比较必须按结算叶子，不得按奖励金额去重。

### 13.4 测试数据清理

测试后以下测试ID数据必须为0：

- 测试帮派OC；
- 测试income；
- 测试summary；
- 测试普通收益。

---

## 14. 代码质量要求

1. 新增Worker、DTO、record、公共方法具备完整Javadoc；
2. 测试类和每个`@Test`有中文`@DisplayName`；
3. 不使用抑制注解规避Sonar；
4. 不新增魔法日期、OC名称或状态字符串；
5. 不复制链配置；
6. 不引入N+1；
7. 不吞异常；
8. 不在一个大事务中循环catch并继续；
9. 不以“任一income存在”等同于完整结算；
10. 不修改无关代码或原始技术方案。

---

## 15. 工程师验证命令

至少执行：

```text
JAVA_HOME="C:\Program Files\Java\jdk-21" mvn.cmd compile -q -DskipTests -Dmaven.compiler.showDeprecation=true
```

聚焦测试必须包含：

```text
TornOcBatchIncomeServiceTest
TornOcBatchIncomeReentrancyTest
TornOcIncomeServiceTest
OcBenefitRankingQueryTest
TornFactionOcBenefitMapperTest
新增的单链事务Worker测试
```

随后执行：

```text
JAVA_HOME="C:\Program Files\Java\jdk-21" mvn.cmd test -q
```

报告：

- 聚焦测试数量、失败、错误、跳过；
- 完整测试数量、失败、错误、跳过；
- 编译退出码；
- `git diff --check`结果；
- 只读SQL验收结果；
- 测试数据清理结果。

---

## 16. 下一轮Review门禁

### P0

- [ ] 单链明细与summary在独立事务中原子提交；
- [ ] 故障注入证明部分写入全部回滚；
- [ ] 重试同一链成功；
- [ ] 月度奖励按结算叶子去重；
- [ ] 同额不同OC/链不会少算；
- [ ] 所有原方案收益互斥与入榜要求继续成立。

### P1

- [ ] 链配置按`name + rank`匹配；
- [ ] 有效配置过滤正确；
- [ ] 异常部分链单独统计且不永久静默；
- [ ] 同帮派真实并发只执行一次；
- [ ] 不同帮派真实并发；
- [ ] 异常后锁可再次获取；
- [ ] 防重入锁覆盖单链事务提交完成。

### P2

- [ ] 无全帮派历史income预加载；
- [ ] 无逐节点链回溯N+1；
- [ ] 测试恢复静态Map；
- [ ] 测试summary清理完整；
- [ ] Javadoc、DisplayName、编译与全量测试通过；
- [ ] 无新依赖、无无关改动。

### 原始方案联合门禁

还必须逐项通过`.ai/knowledge/oc_reassign_range_technical_design.md`第21节和第22节。本文通过不能替代原始方案验收。

---

## 17. 删除条件

本文只有在以下条件全部满足后才能删除：

1. 工程师完成本文全部修复；
2. 聚焦和全量测试通过；
3. 数据库只读验收通过；
4. AI技术专家完成下一轮Review；
5. 原始技术方案与本文联合验收通过；
6. 原始技术方案保持原文不变；
7. 删除本文后同步移除`.ai/knowledge/file_location.md`中的临时索引项。

在此之前，本文必须保留为下一轮Review的追加验收基线。
