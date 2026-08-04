package pn.torn.goldeneye.torn.service.faction.oc.income;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcChainDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.BatchIncomeResult;
import pn.torn.goldeneye.torn.model.faction.crime.income.OcKey;
import pn.torn.goldeneye.torn.model.faction.crime.income.SingleChainResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * OC批量收益计算服务（非事务门面）
 *
 * <p>负责按帮派防重入、批量查询叶子候选、逐条调用单链事务Worker并统计结果。本身不持有
 * 覆盖整批的事务，每个叶子在独立的REQUIRES_NEW事务中原子生成明细与汇总，避免循环提交
 * 残缺链。锁在Worker返回（事务提交/回滚完成）后于finally释放。</p>
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
     * 帮派批量收益计算防重入标记，Key为帮派ID。
     */
    private final ConcurrentHashMap<Long, AtomicBoolean> factionCalculateLocks = new ConcurrentHashMap<>();
    private final TornOcIncomeTransactionWorker transactionWorker;
    private final TornFactionOcDAO ocDao;
    private final TornSettingOcChainDAO ocChainDao;

    /**
     * 批量计算已完成OC的收益。
     *
     * <p>供定时任务与OC校准调用。同一帮派同时只允许一个流程，抢占失败直接返回{@code null}；
     * 每个叶子经独立事务Worker处理，失败链不影响其他链。</p>
     *
     * @param factionId 帮派ID
     * @param execTime  执行时间
     * @return 批次统计结果；因同帮派并发抢占失败时返回{@code null}
     */
    public BatchIncomeResult batchCalculateIncome(long factionId, LocalDateTime execTime) {
        if (!tryAcquireFactionCalculateLock(factionId)) {
            log.info("帮派{}的批量收益计算正在进行中，本次跳过", factionId);
            return null;
        }
        try {
            return doBatchCalculateIncome(factionId, execTime);
        } finally {
            releaseFactionCalculateLock(factionId);
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
        AtomicBoolean lock = factionCalculateLocks.computeIfAbsent(factionId, key -> new AtomicBoolean(false));
        return lock.compareAndSet(false, true);
    }

    /**
     * 释放指定帮派的批量收益计算标记。
     *
     * @param factionId 帮派ID
     */
    void releaseFactionCalculateLock(long factionId) {
        AtomicBoolean lock = factionCalculateLocks.get(factionId);
        if (lock != null) {
            lock.set(false);
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
            return new BatchIncomeResult(0, 0, 0, 0, 0, 0, 0, List.of());
        }
        Set<OcKey> chainParentKeys = loadChainParentKeys();

        // 1. 查询所有已完成、未计算收益且无真实后继的OC（叶子候选快照）
        List<TornFactionOcDO> ocList = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getFactionId, factionId)
                .in(TornFactionOcDO::getStatus, TornOcStatusEnum.getCompleteStatusList())
                .in(TornFactionOcDO::getName, rotationList)
                .ge(TornFactionOcDO::getExecutedTime, startTime)
                .notExists("SELECT 1 FROM torn_faction_oc_income WHERE oc_id = torn_faction_oc.id")
                .notExists("SELECT 1 FROM torn_faction_oc child WHERE child.previous_oc_id = torn_faction_oc.id")
                .list();
        if (CollectionUtils.isEmpty(ocList)) {
            log.info("没有待计算收益的OC, factionId={}", factionId);
            return new BatchIncomeResult(0, 0, 0, 0, 0, 0, 0, List.of());
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
            return new BatchIncomeResult(ocList.size(), 0, 0, waitingCount, 0, 0, 0, List.of());
        }

        // 3. 逐条调用单链事务Worker，异常穿过事务边界后由这里统计
        int successCount = 0;
        int failureCount = 0;
        int alreadyCalculatedCount = 0;
        int abnormalCount = 0;
        int skippedCount = 0;
        List<SingleChainResult> abnormalChains = new ArrayList<>();
        for (TornFactionOcDO leaf : candidates) {
            try {
                SingleChainResult result = transactionWorker.processSingleChain(
                        factionId, leaf.getId(), startTime, chainParentKeys);
                switch (result.outcome()) {
                    case SUCCESS -> {
                        successCount++;
                        log.info("成功计算OC收益: factionId={}, id={}, name={}, status={}",
                                factionId, leaf.getId(), leaf.getName(), leaf.getStatus());
                    }
                    case ALREADY_CALCULATED -> {
                        alreadyCalculatedCount++;
                        log.info("OC已计算过，跳过: factionId={}, id={}, name={}",
                                factionId, leaf.getId(), leaf.getName());
                    }
                    case ABNORMAL_PARTIAL_INCOME -> {
                        abnormalCount++;
                        abnormalChains.add(result);
                        log.warn("异常部分income链，不新增任何收益: factionId={}, leafOcId={}, leafOcName={}, " +
                                        "chainOcIds={}, existingIncomeOcIds={}",
                                factionId, result.leafOcId(), result.leafOcName(),
                                result.chainOcIds(), result.existingIncomeOcIds());
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

        log.info("批量计算收益完成: factionId={}, startTime={}, candidateCount={}, successCount={}, " +
                        "failureCount={}, waitingCount={}, alreadyCalculatedCount={}, abnormalCount={}, skippedCount={}",
                factionId, startTime, ocList.size(), successCount, failureCount,
                waitingCount, alreadyCalculatedCount, abnormalCount, skippedCount);
        return new BatchIncomeResult(ocList.size(), successCount, failureCount, waitingCount,
                alreadyCalculatedCount, abnormalCount, skippedCount, List.copyOf(abnormalChains));
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
}
