package pn.torn.goldeneye.torn.service.faction.oc.income;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcChainDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.BatchIncomeResult;
import pn.torn.goldeneye.torn.model.faction.crime.income.IncomeCompletenessEnum;
import pn.torn.goldeneye.torn.model.faction.crime.income.OcIncomeKey;
import pn.torn.goldeneye.torn.model.faction.crime.income.OcKey;
import pn.torn.goldeneye.torn.model.faction.crime.income.SingleChainOutcomeEnum;
import pn.torn.goldeneye.torn.model.faction.crime.income.SingleChainResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * OC批量收益计算服务（非事务门面）
 *
 * <p>负责按帮派防重入、批量查询叶子候选、批量加载链与income键进行预分类、逐条调用单链
 * 事务Worker并统计结果。本身不持有覆盖整批的事务，每个叶子在独立的REQUIRES_NEW事务中
 * 原子生成明细与汇总，避免循环提交残缺链。锁在Worker返回（事务提交/回滚完成）后于finally释放。</p>
 *
 * @author Bai
 * @version 1.2.12
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

    /**
     * 批量计算已完成OC的收益（单次执行，不含重跑合并）。
     *
     * <p>供定时任务与既有测试直接调用。同一帮派同时只允许一个流程，抢占失败直接返回{@code null}；
     * 每个叶子经独立事务Worker处理，失败链不影响其他链。</p>
     *
     * @param factionId 帮派ID
     * @param execTime  执行时间
     * @return 批次统计结果；因同帮派并发抢占失败时返回{@code null}
     */
    public BatchIncomeResult batchCalculateIncome(long factionId, LocalDateTime execTime) {
        FactionRunState state = factionRunStates.computeIfAbsent(factionId, key -> new FactionRunState());
        if (!state.running.compareAndSet(false, true)) {
            log.info("帮派{}的批量收益计算正在进行中，本次跳过", factionId);
            return null;
        }
        try {
            return doBatchCalculateIncome(factionId, execTime);
        } finally {
            state.running.set(false);
        }
    }

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
     * 尝试抢占指定帮派的批量收益计算标记。
     *
     * <p>同一帮派同时只允许一个批量收益计算流程，不同帮派可以并行。</p>
     *
     * @param factionId 帮派ID
     * @return 抢占成功返回{@code true}，否则返回{@code false}
     */
    boolean tryAcquireFactionCalculateLock(long factionId) {
        FactionRunState state = factionRunStates.computeIfAbsent(factionId, key -> new FactionRunState());
        return state.running.compareAndSet(false, true);
    }

    /**
     * 释放指定帮派的批量收益计算标记。
     *
     * @param factionId 帮派ID
     */
    void releaseFactionCalculateLock(long factionId) {
        FactionRunState state = factionRunStates.get(factionId);
        if (state != null) {
            state.running.set(false);
        }
    }

    /**
     * 执行批量收益计算主体逻辑。
     *
     * @param factionId 帮派ID
     * @param execTime  执行时间
     * @return 批次统计结果
     */
    private BatchIncomeResult doBatchCalculateIncome(long factionId, LocalDateTime execTime) {
        LocalDateTime startTime = resolveIncomeStartTime(factionId, execTime);
        List<String> rotationList = TornConstants.ROTATION_OC_NAME.get(factionId);
        if (CollectionUtils.isEmpty(rotationList)) {
            log.info("该帮派没有配置大锅饭OC名单，跳过批量收益计算: factionId={}", factionId);
            return new BatchIncomeResult(0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }
        Set<OcKey> chainParentKeys = loadChainParentKeys();

        // 1. 查询目标终点（叶子）：R10不再用叶子任意income直接排除，R16对后继子查询显式过滤逻辑删除
        List<TornFactionOcDO> ocList = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getFactionId, factionId)
                .in(TornFactionOcDO::getStatus, TornOcStatusEnum.getCompleteStatusList())
                .in(TornFactionOcDO::getName, rotationList)
                .ge(TornFactionOcDO::getExecutedTime, startTime)
                .notExists("SELECT 1 FROM torn_faction_oc child WHERE child.previous_oc_id = torn_faction_oc.id AND child.deleted = 0")
                .list();
        if (CollectionUtils.isEmpty(ocList)) {
            log.info("没有待计算收益的OC, factionId={}", factionId);
            return new BatchIncomeResult(0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }

        // 2. 过滤配置链中仍要求成功后继节点的父节点（分页校准下避免提前结算整条链）
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
        if (CollectionUtils.isEmpty(candidates)) {
            log.info("过滤链式等待节点后没有待计算收益的OC, factionId={}", factionId);
            return new BatchIncomeResult(ocList.size(), 0, 0, waitingCount, 0, 0, 0, 0, List.of());
        }

        // 3. R15：批量加载候选的祖先关系，构建每个叶子完整链，避免候选数×链长查询放大
        BatchChainContext batchContext = buildBatchChainContext(factionId, candidates);

        // 4. R15：本批实际income业务键一次集合查询，并批量加载岗位计算预期业务键
        List<Long> allChainNodeIds = batchContext.allChainNodeIds();
        Map<Long, List<TornFactionOcIncomeDO>> incomeByOcId = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, factionId)
                .in(TornFactionOcIncomeDO::getOcId, allChainNodeIds)
                .list()
                .stream()
                .collect(Collectors.groupingBy(TornFactionOcIncomeDO::getOcId));
        Map<Long, List<TornFactionOcSlotDO>> slotsByOcId = incomeService.loadSlotsByOcIds(allChainNodeIds);

        // 5. 逐条预分类：已结算、异常部分income、链回溯不完整跳过；仅待计算叶子调用事务Worker
        int successCount = 0;
        int failureCount = 0;
        int alreadyCalculatedCount = 0;
        int abnormalPartialIncomeCount = 0;
        int abnormalIncompleteChainCount = 0;
        int skippedCount = 0;
        List<SingleChainResult> abnormalChains = new ArrayList<>();
        for (TornFactionOcDO leaf : candidates) {
            ChainSnapshot snapshot = batchContext.snapshotByLeaf().get(leaf.getId());
            if (snapshot == null || !snapshot.complete()) {
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
                continue;
            }

            List<TornFactionOcDO> chain = snapshot.chain();
            List<Long> chainOcIds = snapshot.chainOcIds();
            Set<OcIncomeKey> expectedKeys = incomeService.buildExpectedIncomeKeys(chain, slotsByOcId);
            List<TornFactionOcIncomeDO> actualIncome = chainOcIds.stream()
                    .map(incomeByOcId::get)
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream)
                    .toList();
            IncomeCompletenessEnum completeness = incomeService.classifyIncomeCompleteness(expectedKeys, actualIncome);
            Set<Long> existingIncomeOcIds = actualIncome.stream()
                    .map(TornFactionOcIncomeDO::getOcId)
                    .collect(Collectors.toSet());

            switch (completeness) {
                case ALREADY_CALCULATED -> {
                    alreadyCalculatedCount++;
                    log.info("OC已计算过，跳过: factionId={}, id={}, name={}",
                            factionId, leaf.getId(), leaf.getName());
                }
                case ABNORMAL_PARTIAL_INCOME -> {
                    abnormalPartialIncomeCount++;
                    SingleChainResult abnormal = new SingleChainResult(
                            SingleChainOutcomeEnum.ABNORMAL_PARTIAL_INCOME, leaf.getId(), leaf.getName(),
                            chainOcIds, existingIncomeOcIds);
                    abnormalChains.add(abnormal);
                    log.warn("异常部分income链，不新增任何收益: factionId={}, leafOcId={}, leafOcName={}, " +
                                    "chainOcIds={}, existingIncomeOcIds={}",
                            factionId, leaf.getId(), leaf.getName(), chainOcIds, existingIncomeOcIds);
                }
                case PENDING -> {
                    try {
                        SingleChainResult result = transactionWorker.processSingleChain(
                                factionId, leaf.getId(), startTime, chainParentKeys, chain);
                        switch (result.outcome()) {
                            case SUCCESS -> {
                                successCount++;
                                log.info("成功计算OC收益: factionId={}, id={}, name={}, status={}",
                                        factionId, leaf.getId(), leaf.getName(), leaf.getStatus());
                            }
                            case ALREADY_CALCULATED -> {
                                alreadyCalculatedCount++;
                                log.info("事务内判定已计算过，跳过: factionId={}, id={}, name={}",
                                        factionId, leaf.getId(), leaf.getName());
                            }
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
                    } catch (Exception e) {
                        failureCount++;
                        log.error("计算OC收益失败: factionId={}, leafOcId={}, leafOcName={}",
                                factionId, leaf.getId(), leaf.getName(), e);
                    }
                }
            }
        }

        log.info("批量计算收益完成: factionId={}, startTime={}, candidateCount={}, successCount={}, " +
                        "failureCount={}, waitingCount={}, alreadyCalculatedCount={}, abnormalCount={}, " +
                        "abnormalIncompleteCount={}, skippedCount={}",
                factionId, startTime, ocList.size(), successCount, failureCount,
                waitingCount, alreadyCalculatedCount, abnormalPartialIncomeCount,
                abnormalIncompleteChainCount, skippedCount);
        return new BatchIncomeResult(ocList.size(), successCount, failureCount, waitingCount,
                alreadyCalculatedCount, abnormalPartialIncomeCount, abnormalIncompleteChainCount,
                skippedCount, List.copyOf(abnormalChains));
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
     * <p>先加载全部候选，再按{@code previous_oc_id}批量加载祖先，直到无新节点；随后为每个
     * 叶子组装有序链并检测缺失祖先、帮派不一致与环形引用。</p>
     *
     * @param factionId  帮派ID
     * @param candidates 叶子候选
     * @return 批量链上下文
     */
    private BatchChainContext buildBatchChainContext(long factionId, List<TornFactionOcDO> candidates) {
        Map<Long, TornFactionOcDO> nodeMap = new HashMap<>();
        for (TornFactionOcDO candidate : candidates) {
            nodeMap.put(candidate.getId(), candidate);
        }
        Set<Long> loadedIds = new HashSet<>(nodeMap.keySet());
        List<Long> pendingIds = candidates.stream()
                .map(TornFactionOcDO::getPreviousOcId)
                .filter(Objects::nonNull)
                .filter(id -> !loadedIds.contains(id))
                .distinct()
                .toList();
        while (!pendingIds.isEmpty()) {
            List<TornFactionOcDO> loaded = ocDao.lambdaQuery()
                    .eq(TornFactionOcDO::getFactionId, factionId)
                    .in(TornFactionOcDO::getId, pendingIds)
                    .list();
            List<Long> nextPending = new ArrayList<>();
            for (TornFactionOcDO node : loaded) {
                nodeMap.put(node.getId(), node);
                loadedIds.add(node.getId());
                if (node.getPreviousOcId() != null && !loadedIds.contains(node.getPreviousOcId())) {
                    nextPending.add(node.getPreviousOcId());
                }
            }
            pendingIds = nextPending.stream().distinct().toList();
        }

        Map<Long, ChainSnapshot> snapshotByLeaf = new HashMap<>();
        for (TornFactionOcDO candidate : candidates) {
            Long missingAncestor = null;
            boolean complete = true;
            List<TornFactionOcDO> chain = new ArrayList<>();
            Set<Long> visited = new HashSet<>();
            visited.add(candidate.getId());
            TornFactionOcDO cursor = candidate;
            while (true) {
                chain.add(cursor);
                if (cursor.getPreviousOcId() == null) {
                    break;
                }
                if (!visited.add(cursor.getPreviousOcId())) {
                    complete = false;
                    missingAncestor = cursor.getPreviousOcId();
                    break;
                }
                TornFactionOcDO parent = nodeMap.get(cursor.getPreviousOcId());
                if (parent == null) {
                    complete = false;
                    missingAncestor = cursor.getPreviousOcId();
                    break;
                }
                if (!Objects.equals(parent.getFactionId(), candidate.getFactionId())) {
                    complete = false;
                    missingAncestor = parent.getId();
                    break;
                }
                cursor = parent;
            }
            List<Long> chainOcIds = chain.stream().map(TornFactionOcDO::getId).toList();
            snapshotByLeaf.put(candidate.getId(), new ChainSnapshot(chain, complete, missingAncestor, chainOcIds));
        }
        return new BatchChainContext(snapshotByLeaf,
                nodeMap.keySet().stream().toList());
    }

    /**
     * 解析帮派级大锅饭收益扫描起点。
     *
     * <p>PN从2026-08-01起、NOV从2026-07-01起扫描历史补算；其他帮派保持现有行为，
     * 使用执行时间所在月份第一天。该方法是纯函数，不读取数据库、系统时间或外部配置。</p>
     *
     * @param factionId 帮派ID
     * @param execTime  执行时间
     * @return 大锅饭收益扫描起点（左闭区间）
     */
    LocalDateTime resolveIncomeStartTime(long factionId, LocalDateTime execTime) {
        if (factionId == TornConstants.FACTION_PN_ID) {
            return TornConstants.PN_OC_REASSIGN_EFFECTIVE_FROM;
        }
        if (factionId == TornConstants.FACTION_NOV_ID) {
            return TornConstants.NOV_OC_REASSIGN_EFFECTIVE_FROM;
        }
        return LocalDateTime.of(execTime.getYear(), execTime.getMonth(), 1, 0, 0, 0);
    }

    /**
     * 单链快照，供批量门面预分类与事务Worker复用。
     *
     * @param chain              完整链（从最早祖先到叶子）或已加载的部分链
     * @param complete           链是否完整
     * @param missingAncestorOcId 缺失祖先OC ID；链完整时为{@code null}
     * @param chainOcIds         链节点OC ID列表
     */
    private record ChainSnapshot(List<TornFactionOcDO> chain, boolean complete,
                                 Long missingAncestorOcId, List<Long> chainOcIds) {
    }

    /**
     * 批量链上下文，包含每个叶子快照与全部链节点ID。
     *
     * @param snapshotByLeaf    叶子OC ID到链快照映射
     * @param allChainNodeIds   本批全部链节点OC ID
     */
    private record BatchChainContext(Map<Long, ChainSnapshot> snapshotByLeaf, List<Long> allChainNodeIds) {
    }

    /**
     * 帮派批量收益计算运行状态。
     */
    private static class FactionRunState {
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean rerunRequested = new AtomicBoolean(false);
    }
}
