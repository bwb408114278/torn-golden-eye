package pn.torn.goldeneye.torn.service.faction.oc.income;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcChainDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcReassignManager;
import pn.torn.goldeneye.torn.model.faction.crime.income.*;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * OC批量收益计算服务（非事务门面）
 *
 * <p>负责按帮派防重入、批量查询叶子候选、批量加载链与income键进行预分类、逐条调用单链
 * 事务Worker并统计结果。本身不持有覆盖整批的事务，每个叶子由独立Worker事务原子生成明细，
 * 全部链提交后统一重算受影响月份汇总。锁在Worker返回（事务提交/回滚完成）后于finally释放。</p>
 *
 * @author Bai
 * @version 1.6.3
 * @since 2025.11.03
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TornOcBatchIncomeService {
    /**
     * 帮派批量收益计算运行状态，Key为帮派ID。
     */
    private final ConcurrentHashMap<Long, FactionRunState> factionRunStates = new ConcurrentHashMap<>();
    private final TornOcIncomeTransactionWorker transactionWorker;
    private final TornFactionOcDAO ocDao;
    private final TornSettingOcChainDAO ocChainDao;
    private final TornFactionOcIncomeDAO incomeDao;
    private final TornOcIncomeService incomeService;
    private final TornSettingOcReassignManager reassignManager;

    /**
     * 触发一次帮派批量收益计算，运行期间的新触发会合并为一次最终重跑。
     *
     * <p>供分页校准事务提交后的异步触发使用：同一帮派计算运行中收到新触发时不并发执行，
     * 而是记录{@code rerunRequested=true}，当前批次结束后使用最新数据库快照再执行一次，
     * 保证最后一页历史OC不会被丢触发。重跑请求在异常路径也不会被静默丢弃。</p>
     *
     * @param factionId 帮派ID
     * @param execTime  执行时间
     */
    public void requestBatchIncome(long factionId, LocalDateTime execTime) {
        FactionRunState state = factionRunStates.computeIfAbsent(factionId, key -> new FactionRunState());
        while (true) {
            if (!state.running.compareAndSet(false, true)) {
                state.rerunRequested.set(true);
                return;
            }
            try {
                do {
                    state.rerunRequested.set(false);
                    doBatchCalculateIncome(factionId, execTime);
                } while (state.rerunRequested.get());
            } finally {
                state.running.set(false);
            }
            // 释放锁的瞬间若有新触发到达，再执行一次，避免最后一页丢触发
            if (!state.rerunRequested.compareAndSet(true, false)) {
                return;
            }
        }
    }

    /**
     * 执行批量收益计算主体逻辑。
     *
     * <p>流程：查询目标叶子候选 → 过滤等待后继父节点 → 批量加载链上下文与income业务键预分类
     * → 逐叶子调用单链事务Worker并累计统计 → 统一重算受影响月份。本身不持有覆盖整批的事务。</p>
     *
     * @param factionId 帮派ID
     * @param execTime  执行时间
     * @return 批次统计结果
     */
    private BatchIncomeResult doBatchCalculateIncome(long factionId, LocalDateTime execTime) {
        LocalDateTime startTime = resolveIncomeStartTime(factionId, execTime);
        List<String> rotationList = reassignManager.getRotationOcNames(factionId);
        if (CollectionUtils.isEmpty(rotationList)) {
            log.info("该帮派没有配置大锅饭OC名单，跳过批量收益计算: factionId={}", factionId);
            return BatchIncomeResult.empty();
        }
        Set<OcKey> chainParentKeys = loadChainParentKeys();
        List<TornFactionOcDO> ocList = queryLeafCandidates(factionId, startTime, rotationList);
        if (CollectionUtils.isEmpty(ocList)) {
            log.info("没有待计算收益的OC, factionId={}", factionId);
            return BatchIncomeResult.empty();
        }

        CandidatePartition partition = partitionWaitingParents(factionId, ocList, chainParentKeys);
        if (CollectionUtils.isEmpty(partition.candidates())) {
            log.info("过滤链式等待节点后没有待计算收益的OC, factionId={}", factionId);
            return BatchIncomeResult.waitingOnly(ocList.size(), partition.waitingCount());
        }

        BatchChainContext batchContext = buildBatchChainContext(factionId, partition.candidates());
        BatchInputs inputs = loadBatchInputs(factionId, batchContext);
        BatchCounters counters = new BatchCounters();
        Set<String> affectedYearMonths = new HashSet<>();
        CandidateProcessingContext processingContext = new CandidateProcessingContext(factionId, startTime,
                chainParentKeys, batchContext, inputs, counters, affectedYearMonths);
        for (TornFactionOcDO leaf : partition.candidates()) {
            processLeafCandidate(leaf, processingContext);
        }
        incomeService.recalcMonthlyIncomeSummaries(factionId, affectedYearMonths);

        log.info("批量计算收益完成: factionId={}, startTime={}, candidateCount={}, successCount={}, " +
                        "failureCount={}, waitingCount={}, alreadyCalculatedCount={}, abnormalCount={}, " +
                        "abnormalIncompleteCount={}, skippedCount={}",
                factionId, startTime, ocList.size(), counters.successCount, counters.failureCount,
                counters.waitingCount, counters.alreadyCalculatedCount, counters.abnormalPartialIncomeCount,
                counters.abnormalIncompleteChainCount, counters.skippedCount);
        return new BatchIncomeResult(ocList.size(), counters.successCount, counters.failureCount,
                counters.waitingCount, counters.alreadyCalculatedCount, counters.abnormalPartialIncomeCount,
                counters.abnormalIncompleteChainCount, counters.skippedCount, List.copyOf(counters.abnormalChains));
    }

    /**
     * 收集本批完整链涉及的月份。
     *
     * <p>所有单链事务提交完成后统一重算这些月份，避免每条链各自以不完整income快照覆盖同一月汇总。
     * 只有已完整结算或本次成功生成income的链才允许纳入集合，异常部分income链继续保持fail-closed。</p>
     *
     * @param chain 本批已通过完整性审计的OC链
     * @return 受影响年月集合
     */
    private Set<String> collectAffectedYearMonths(List<TornFactionOcDO> chain) {
        return chain.stream()
                .map(TornFactionOcDO::getExecutedTime)
                .filter(Objects::nonNull)
                .map(time -> time.format(DateTimeUtils.YEAR_MONTH_FORMATTER))
                .collect(Collectors.toSet());
    }

    /**
     * 查询当前帮派待计算的目标终点（叶子）候选。
     *
     * <p>R10不再用叶子任意income直接排除，完整性由后续预分类判定；R16对后继子查询显式过滤逻辑删除。
     * 完成时间早于该OC生效行时间的候选被剔除（生效时间语义是"该OC从那时起算大锅饭"，不改变
     * 帮派级扫描窗口，"始终"行不受影响）。</p>
     *
     * @param factionId    帮派ID
     * @param startTime    扫描起点（左闭区间）
     * @param rotationList 大锅饭OC名单
     * @return 叶子候选列表
     */
    private List<TornFactionOcDO> queryLeafCandidates(long factionId, LocalDateTime startTime,
                                                      List<String> rotationList) {
        Map<String, LocalDateTime> effectiveFromByName = reassignManager.getEffectiveFromByName(factionId);
        return ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getFactionId, factionId)
                .in(TornFactionOcDO::getStatus, TornOcStatusEnum.getCompleteStatusList())
                .in(TornFactionOcDO::getName, rotationList)
                .ge(TornFactionOcDO::getExecutedTime, startTime)
                .notExists("SELECT 1 FROM torn_faction_oc child WHERE child.previous_oc_id = torn_faction_oc.id AND child.deleted = 0")
                .list().stream()
                .filter(oc -> isWithinEffectiveFrom(oc, effectiveFromByName))
                .toList();
    }

    /**
     * 判断OC完成时间是否已达其生效行时间。
     *
     * @param oc                  叶子候选
     * @param effectiveFromByName OC名称到生效时间的映射，"始终"OC不在映射内
     * @return 完成时间不早于生效时间（或无生效行）返回true
     */
    private boolean isWithinEffectiveFrom(TornFactionOcDO oc, Map<String, LocalDateTime> effectiveFromByName) {
        LocalDateTime effectiveFrom = effectiveFromByName.get(oc.getName());
        return effectiveFrom == null || !oc.getExecutedTime().isBefore(effectiveFrom);
    }

    /**
     * 将叶子候选拆分为等待后继父节点与可处理候选。
     *
     * @param factionId       帮派ID
     * @param ocList          叶子候选
     * @param chainParentKeys 有效链配置父节点集合
     * @return 拆分结果
     */
    private CandidatePartition partitionWaitingParents(long factionId, List<TornFactionOcDO> ocList,
                                                       Set<OcKey> chainParentKeys) {
        int waitingCount = 0;
        List<TornFactionOcDO> candidates = new ArrayList<>();
        for (TornFactionOcDO oc : ocList) {
            if (isWaitingChainParent(oc, chainParentKeys)) {
                waitingCount++;
                log.info("等待链式后继节点: factionId={}, ocId={}, ocName={}, rank={}",
                        factionId, oc.getId(), oc.getName(), oc.getRank());
            } else {
                candidates.add(oc);
            }
        }
        return new CandidatePartition(candidates, waitingCount);
    }

    /**
     * 加载本批链节点income与岗位数据。
     *
     * @param factionId    帮派ID
     * @param batchContext 批量链上下文
     * @return 批次输入数据
     */
    private BatchInputs loadBatchInputs(long factionId, BatchChainContext batchContext) {
        Map<Long, List<TornFactionOcIncomeDO>> incomeByOcId = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, factionId)
                .in(TornFactionOcIncomeDO::getOcId, batchContext.allChainNodeIds())
                .list()
                .stream()
                .collect(Collectors.groupingBy(TornFactionOcIncomeDO::getOcId));
        Map<Long, List<TornFactionOcSlotDO>> slotsByOcId = incomeService.loadSlotsByOcIds(batchContext.allChainNodeIds());
        return new BatchInputs(incomeByOcId, slotsByOcId);
    }

    /**
     * 处理单个叶子候选：链回溯不完整直接fail-closed，否则按income完整性预分类并分发Worker。
     *
     * @param leaf    叶子候选
     * @param context 候选处理上下文
     */
    private void processLeafCandidate(TornFactionOcDO leaf, CandidateProcessingContext context) {
        ChainSnapshot snapshot = context.batchContext().snapshotByLeaf().get(leaf.getId());
        if (snapshot == null || !snapshot.complete()) {
            context.counters().recordIncompleteChain(context.factionId(), leaf, snapshot);
            return;
        }
        List<TornFactionOcDO> chain = snapshot.chain();
        List<Long> chainOcIds = snapshot.chainOcIds();
        Set<OcIncomeKey> expectedKeys = incomeService.buildExpectedIncomeKeys(chain, context.inputs().slotsByOcId());
        List<TornFactionOcIncomeDO> actualIncome = collectActualIncome(context.inputs().incomeByOcId(), chainOcIds);
        IncomeCompletenessEnum completeness = incomeService.classifyIncomeCompleteness(expectedKeys, actualIncome);
        switch (completeness) {
            case ALREADY_CALCULATED -> {
                context.counters().recordAlreadyCalculated(context.factionId(), leaf);
                context.affectedYearMonths().addAll(collectAffectedYearMonths(chain));
            }
            case ABNORMAL_PARTIAL_INCOME -> context.counters().recordAbnormalPartial(
                    context.factionId(), leaf, chainOcIds, actualIncome);
            case PENDING -> dispatchToWorker(leaf, chain, context);
        }
    }

    /**
     * 汇总链节点实际income记录。
     *
     * @param incomeByOcId 按OC分组的income映射
     * @param chainOcIds   链节点OC ID
     * @return 链上全部实际income
     */
    private List<TornFactionOcIncomeDO> collectActualIncome(Map<Long, List<TornFactionOcIncomeDO>> incomeByOcId,
                                                            List<Long> chainOcIds) {
        return chainOcIds.stream()
                .map(incomeByOcId::get)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .toList();
    }

    /**
     * 对待计算叶子调用单链事务Worker并累计结果。
     *
     * @param leaf    叶子候选
     * @param chain   预加载的完整链
     * @param context 候选处理上下文
     */
    private void dispatchToWorker(TornFactionOcDO leaf, List<TornFactionOcDO> chain,
                                  CandidateProcessingContext context) {
        try {
            SingleChainResult result = transactionWorker.processSingleChain(
                    context.factionId(), leaf.getId(), context.startTime(), context.chainParentKeys(), chain);
            context.counters().recordWorkerResult(context.factionId(), leaf, result);
            if (result.outcome() == SingleChainOutcomeEnum.SUCCESS) {
                context.affectedYearMonths().addAll(collectAffectedYearMonths(chain));
            }
        } catch (Exception e) {
            context.counters().recordFailure(context.factionId(), leaf, e);
        }
    }

    /**
     * 判断当前OC是否为配置链中仍要求成功后继节点的父节点。
     *
     * <p>只有状态为Successful且名称与等级均命中有效链配置的父节点才等待后继；
     * 失败父节点仍按终点计算损失，同名但等级不匹配的OC作为独立终点处理。</p>
     *
     * @param oc              候选OC
     * @param chainParentKeys 有效链配置父节点集合
     * @return 成功的配置链父节点返回{@code true}
     */
    private boolean isWaitingChainParent(TornFactionOcDO oc, Set<OcKey> chainParentKeys) {
        return TornOcStatusEnum.SUCCESSFUL.getCode().equals(oc.getStatus())
                && chainParentKeys.contains(new OcKey(oc.getName(), oc.getRank()));
    }

    /**
     * 一次性加载有效链配置中所有父节点，避免逐条查询。
     *
     * <p>仅加载{@code enabled=true}的有效配置；逻辑删除由MyBatis-Plus全局逻辑删除自动追加
     * {@code deleted = 0}过滤，并由测试通过真实DAO查询证明。</p>
     *
     * @return 父节点OC名称与等级键集合
     */
    private Set<OcKey> loadChainParentKeys() {
        return ocChainDao.lambdaQuery()
                .eq(TornSettingOcChainDO::getEnabled, true)
                .list()
                .stream()
                .map(chain -> new OcKey(chain.getParentOcName(), chain.getParentRank()))
                .collect(Collectors.toSet());
    }

    /**
     * 批量加载所有候选叶子的完整链，避免逐候选逐层查询放大。
     *
     * <p>复用批量门面与月度汇总共用的祖先批量加载，随后为每个叶子组装有序链并检测缺失祖先、
     * 帮派不一致与环形引用。</p>
     *
     * @param factionId  帮派ID
     * @param candidates 叶子候选
     * @return 批量链上下文
     */
    private BatchChainContext buildBatchChainContext(long factionId, List<TornFactionOcDO> candidates) {
        Map<Long, TornFactionOcDO> nodeMap = incomeService.loadAncestorNodes(factionId, candidates);
        Map<Long, ChainSnapshot> snapshotByLeaf = new HashMap<>();
        for (TornFactionOcDO candidate : candidates) {
            snapshotByLeaf.put(candidate.getId(), buildChainSnapshot(candidate, nodeMap));
        }
        return new BatchChainContext(snapshotByLeaf, nodeMap.keySet().stream().toList());
    }

    /**
     * 组装单个候选叶子的有序链并检测缺失祖先、帮派不一致与环形引用。
     *
     * @param candidate 叶子候选
     * @param nodeMap   已批量加载的节点映射
     * @return 叶子对应的链快照
     */
    private ChainSnapshot buildChainSnapshot(TornFactionOcDO candidate, Map<Long, TornFactionOcDO> nodeMap) {
        List<TornFactionOcDO> chain = new ArrayList<>();
        Long missingAncestor = walkCandidateChain(candidate, nodeMap, chain);
        List<Long> chainOcIds = chain.stream().map(TornFactionOcDO::getId).toList();
        return new ChainSnapshot(chain, missingAncestor == null, missingAncestor, chainOcIds);
    }

    /**
     * 从叶子向根回溯组装有序链，返回缺失祖先或环引用时的异常祖先ID。
     *
     * <p>{@code null}表示链完整回溯到链首；非{@code null}表示缺失祖先或环形引用，
     * 由调用方fail-closed处理。</p>
     *
     * @param candidate 叶子候选
     * @param nodeMap   已批量加载的节点映射
     * @param chain     输出参数，组装完成的链节点（从叶子向根）
     * @return 缺失或环引用时的异常祖先ID；链完整返回{@code null}
     */
    private Long walkCandidateChain(TornFactionOcDO candidate, Map<Long, TornFactionOcDO> nodeMap,
                                    List<TornFactionOcDO> chain) {
        Set<Long> visited = new HashSet<>();
        visited.add(candidate.getId());
        Long cursorId = candidate.getId();
        while (cursorId != null) {
            TornFactionOcDO cursor = nodeMap.get(cursorId);
            chain.add(cursor);
            Long nextId = cursor.getPreviousOcId();
            if (nextId == null) {
                return null;
            }
            if (!visited.add(nextId)) {
                return nextId;
            }
            TornFactionOcDO parent = nodeMap.get(nextId);
            if (parent == null) {
                return nextId;
            }
            if (!Objects.equals(parent.getFactionId(), candidate.getFactionId())) {
                return parent.getId();
            }
            cursorId = nextId;
        }
        return null;
    }

    /**
     * 解析帮派级大锅饭收益扫描起点。
     *
     * <p>委托{@link TornSettingOcReassignManager}按该帮派范围行的最小非NULL生效时间解析；
     * 全部为NULL时使用执行时间所在月份第一天。该方法是纯函数透传，不读取数据库、系统时间或外部配置。</p>
     *
     * @param factionId 帮派ID
     * @param execTime  执行时间
     * @return 大锅饭收益扫描起点（左闭区间）
     */
    LocalDateTime resolveIncomeStartTime(long factionId, LocalDateTime execTime) {
        return reassignManager.resolveIncomeStartTime(factionId, execTime);
    }

    /**
     * 单链快照，供批量门面预分类与事务Worker复用。
     *
     * @param chain               完整链（从最早祖先到叶子）或已加载的部分链
     * @param complete            链是否完整
     * @param missingAncestorOcId 缺失祖先OC ID；链完整时为{@code null}
     * @param chainOcIds          链节点OC ID列表
     */
    private record ChainSnapshot(List<TornFactionOcDO> chain, boolean complete,
                                 Long missingAncestorOcId, List<Long> chainOcIds) {
    }

    /**
     * 批量链上下文，包含每个叶子快照与全部链节点ID。
     *
     * @param snapshotByLeaf  叶子OC ID到链快照映射
     * @param allChainNodeIds 本批全部链节点OC ID
     */
    private record BatchChainContext(Map<Long, ChainSnapshot> snapshotByLeaf, List<Long> allChainNodeIds) {
    }

    /**
     * 批次统计累计器，聚合各叶子处理结果并生成批次统计。
     */
    private static class BatchCounters {
        private int successCount;
        private int failureCount;
        private int waitingCount;
        private int alreadyCalculatedCount;
        private int abnormalPartialIncomeCount;
        private int abnormalIncompleteChainCount;
        private int skippedCount;
        private final List<SingleChainResult> abnormalChains = new ArrayList<>();

        /**
         * 记录链回溯不完整的批次预分类结果。
         *
         * @param factionId 帮派ID
         * @param leaf      叶子候选
         * @param snapshot  链快照；为空时使用叶子自身信息
         */
        void recordIncompleteChain(long factionId, TornFactionOcDO leaf, ChainSnapshot snapshot) {
            abnormalIncompleteChainCount++;
            SingleChainResult incomplete = new SingleChainResult(
                    SingleChainOutcomeEnum.ABNORMAL_INCOMPLETE_CHAIN, leaf.getId(), leaf.getName(),
                    snapshot == null ? List.of() : snapshot.chainOcIds(), Set.of(),
                    snapshot == null ? leaf.getPreviousOcId() : snapshot.missingAncestorOcId());
            abnormalChains.add(incomplete);
            log.warn("批次预分类链回溯不完整: factionId={}, leafOcId={}, leafOcName={}, " +
                            "chainOcIds={}, missingAncestorOcId={}",
                    factionId, leaf.getId(), leaf.getName(),
                    snapshot == null ? List.of() : snapshot.chainOcIds(),
                    snapshot == null ? leaf.getPreviousOcId() : snapshot.missingAncestorOcId());
        }

        /**
         * 记录已结算跳过。
         *
         * @param factionId 帮派ID
         * @param leaf      叶子候选
         */
        void recordAlreadyCalculated(long factionId, TornFactionOcDO leaf) {
            alreadyCalculatedCount++;
            log.info("OC已计算过，跳过: factionId={}, id={}, name={}",
                    factionId, leaf.getId(), leaf.getName());
        }

        /**
         * 记录异常部分income链，不新增任何收益。
         *
         * @param factionId    帮派ID
         * @param leaf         叶子候选
         * @param chainOcIds   链节点OC ID
         * @param actualIncome 链上实际income
         */
        void recordAbnormalPartial(long factionId, TornFactionOcDO leaf, List<Long> chainOcIds,
                                   List<TornFactionOcIncomeDO> actualIncome) {
            abnormalPartialIncomeCount++;
            Set<Long> existingIncomeOcIds = actualIncome.stream()
                    .map(TornFactionOcIncomeDO::getOcId)
                    .collect(Collectors.toSet());
            SingleChainResult abnormal = new SingleChainResult(
                    SingleChainOutcomeEnum.ABNORMAL_PARTIAL_INCOME, leaf.getId(), leaf.getName(),
                    chainOcIds, existingIncomeOcIds);
            abnormalChains.add(abnormal);
            log.warn("异常部分income链，不新增任何收益: factionId={}, leafOcId={}, leafOcName={}, " +
                            "chainOcIds={}, existingIncomeOcIds={}",
                    factionId, leaf.getId(), leaf.getName(), chainOcIds, existingIncomeOcIds);
        }

        /**
         * 累计Worker单链处理结果。
         *
         * @param factionId 帮派ID
         * @param leaf      叶子候选
         * @param result    单链处理结果
         */
        void recordWorkerResult(long factionId, TornFactionOcDO leaf, SingleChainResult result) {
            switch (result.outcome()) {
                case SUCCESS -> {
                    successCount++;
                    log.info("成功计算OC收益: factionId={}, id={}, name={}, status={}",
                            factionId, leaf.getId(), leaf.getName(), leaf.getStatus());
                }
                case ALREADY_CALCULATED -> recordAlreadyCalculated(factionId, leaf);
                case ABNORMAL_PARTIAL_INCOME -> {
                    abnormalPartialIncomeCount++;
                    abnormalChains.add(result);
                    log.warn("事务内判定异常部分income链: factionId={}, leafOcId={}, leafOcName={}, " +
                                    "chainOcIds={}, existingIncomeOcIds={}",
                            factionId, result.leafOcId(), result.leafOcName(),
                            result.chainOcIds(), result.existingIncomeOcIds());
                }
                case ABNORMAL_INCOMPLETE_CHAIN -> {
                    abnormalIncompleteChainCount++;
                    abnormalChains.add(result);
                    log.warn("事务内判定链回溯不完整: factionId={}, leafOcId={}, leafOcName={}, " +
                                    "chainOcIds={}, missingAncestorOcId={}",
                            factionId, result.leafOcId(), result.leafOcName(),
                            result.chainOcIds(), result.missingAncestorOcId());
                }
                case WAITING_PARENT -> {
                    waitingCount++;
                    log.info("事务内判定为等待链式后继节点: factionId={}, leafOcId={}, leafOcName={}",
                            factionId, leaf.getId(), leaf.getName());
                }
                case NOT_CANDIDATE -> {
                    skippedCount++;
                    log.warn("单链叶子不再适用，跳过: factionId={}, leafOcId={}, leafOcName={}",
                            factionId, leaf.getId(), leaf.getName());
                }
            }
        }

        /**
         * 记录Worker调用失败。
         *
         * @param factionId 帮派ID
         * @param leaf      叶子候选
         * @param e         异常
         */
        void recordFailure(long factionId, TornFactionOcDO leaf, Exception e) {
            failureCount++;
            log.error("计算OC收益失败: factionId={}, leafOcId={}, leafOcName={}",
                    factionId, leaf.getId(), leaf.getName(), e);
        }
    }

    /**
     * 候选拆分结果，包含等待后继父节点数量与可处理候选。
     *
     * @param candidates   可处理候选
     * @param waitingCount 等待链式后继节点的父节点数量
     */
    private record CandidatePartition(
            List<TornFactionOcDO> candidates,
            int waitingCount) {
    }

    /**
     * 批次输入数据，包含本批链节点income与岗位映射。
     *
     * @param incomeByOcId 按OC分组的income映射
     * @param slotsByOcId  按OC分组的岗位映射
     */
    private record BatchInputs(
            Map<Long, List<TornFactionOcIncomeDO>> incomeByOcId,
            Map<Long, List<TornFactionOcSlotDO>> slotsByOcId) {
    }

    /**
     * 单个叶子候选处理所需的批次共享上下文。
     *
     * @param factionId          帮派ID
     * @param startTime          扫描起点（左闭区间）
     * @param chainParentKeys    有效链配置父节点集合
     * @param batchContext       批量链上下文
     * @param inputs             批次输入数据
     * @param counters           批次统计累计器
     * @param affectedYearMonths 已完整结算或成功生成income的受影响年月
     */
    private record CandidateProcessingContext(
            long factionId,
            LocalDateTime startTime,
            Set<OcKey> chainParentKeys,
            BatchChainContext batchContext,
            BatchInputs inputs,
            BatchCounters counters,
            Set<String> affectedYearMonths) {
    }

    /**
     * 帮派批量收益计算运行状态。
     */
    private static class FactionRunState {
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean rerunRequested = new AtomicBoolean(false);
    }
}
