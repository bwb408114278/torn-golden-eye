package pn.torn.goldeneye.torn.service.faction.oc.income;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.OcKey;
import pn.torn.goldeneye.torn.model.faction.crime.income.SingleChainOutcomeEnum;
import pn.torn.goldeneye.torn.model.faction.crime.income.SingleChainResult;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 单链收益事务Worker。
 *
 * <p>每个叶子在一个独立事务（REQUIRES_NEW）中完成链完整性校验、整链明细生成与月度汇总
 * 重算，任一环节失败整链回滚，避免批量门面在大事务中循环提交残缺链。必须通过Spring代理
 * 由批量门面调用，禁止在同一Service内自调用。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.03
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TornOcIncomeTransactionWorker {
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcIncomeDAO incomeDao;
    private final TornOcIncomeService incomeService;

    /**
     * 在独立事务中处理一条链，事务提交或回滚完成后才返回。
     *
     * <p>批次查询只是候选快照，事务内会基于最新数据重新校验：叶子仍属于目标帮派、完成状态、
     * 大锅饭名单、扫描时间范围；无真实后继；整链当前没有部分income异常。真实失败异常会穿过
     * 事务边界由批量门面捕获统计。</p>
     *
     * @param factionId       帮派ID
     * @param leafOcId        叶子OC ID
     * @param startTime       扫描时间下限（左闭区间）
     * @param chainParentKeys 有效链配置父节点集合，用于等待后继判断
     * @return 单链处理结果
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public SingleChainResult processSingleChain(long factionId, long leafOcId, LocalDateTime startTime,
                                                Set<OcKey> chainParentKeys) {
        TornFactionOcDO leaf = ocDao.getById(leafOcId);
        if (leaf == null) {
            log.warn("单链叶子OC不存在，跳过: factionId={}, leafOcId={}", factionId, leafOcId);
            return new SingleChainResult(SingleChainOutcomeEnum.NOT_CANDIDATE, leafOcId, null, List.of(), Set.of());
        }
        if (!isStillCandidate(factionId, leaf, startTime)) {
            log.warn("单链叶子不再满足候选条件，跳过: factionId={}, leafOcId={}, leafOcName={}",
                    factionId, leaf.getId(), leaf.getName());
            return new SingleChainResult(SingleChainOutcomeEnum.NOT_CANDIDATE, leaf.getId(), leaf.getName(),
                    List.of(), Set.of());
        }

        boolean configParent = TornOcStatusEnum.SUCCESSFUL.getCode().equals(leaf.getStatus())
                && chainParentKeys.contains(new OcKey(leaf.getName(), leaf.getRank()));
        boolean hasSuccessor = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getPreviousOcId, leaf.getId())
                .eq(TornFactionOcDO::getFactionId, factionId)
                .exists();
        if (configParent) {
            return hasSuccessor
                    ? new SingleChainResult(SingleChainOutcomeEnum.NOT_CANDIDATE, leaf.getId(), leaf.getName(),
                    List.of(), Set.of())
                    : new SingleChainResult(SingleChainOutcomeEnum.WAITING_PARENT, leaf.getId(), leaf.getName(),
                    List.of(), Set.of());
        }
        if (hasSuccessor) {
            log.info("单链叶子已出现真实后继，由后继节点触发整链: factionId={}, leafOcId={}, leafOcName={}",
                    factionId, leaf.getId(), leaf.getName());
            return new SingleChainResult(SingleChainOutcomeEnum.NOT_CANDIDATE, leaf.getId(), leaf.getName(),
                    List.of(), Set.of());
        }

        List<TornFactionOcDO> chain = incomeService.loadOcChain(leaf);
        List<Long> chainOcIds = chain.stream().map(TornFactionOcDO::getId).toList();
        Set<Long> existingIncomeOcIds = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, factionId)
                .in(TornFactionOcIncomeDO::getOcId, chainOcIds)
                .select(TornFactionOcIncomeDO::getOcId)
                .list()
                .stream()
                .map(TornFactionOcIncomeDO::getOcId)
                .collect(Collectors.toSet());
        if (existingIncomeOcIds.contains(leaf.getId())) {
            return new SingleChainResult(SingleChainOutcomeEnum.ALREADY_CALCULATED, leaf.getId(), leaf.getName(),
                    chainOcIds, existingIncomeOcIds);
        }
        if (!existingIncomeOcIds.isEmpty()) {
            log.warn("链内存在部分income异常，整链不新增任何收益: factionId={}, leafOcId={}, leafOcName={}, " +
                            "chainOcIds={}, existingIncomeOcIds={}",
                    factionId, leaf.getId(), leaf.getName(), chainOcIds, existingIncomeOcIds);
            return new SingleChainResult(SingleChainOutcomeEnum.ABNORMAL_PARTIAL_INCOME, leaf.getId(), leaf.getName(),
                    chainOcIds, existingIncomeOcIds);
        }

        try {
            incomeService.generateAndSaveIncome(leaf, chain);
            incomeService.calcMonthlyIncomeSummary(leaf.getFactionId(),
                    leaf.getExecutedTime().format(DateTimeUtils.YEAR_MONTH_FORMATTER));
        } catch (Exception e) {
            log.error("单链收益生成失败，整链回滚: factionId={}, leafOcId={}, leafOcName={}, chainOcIds={}",
                    factionId, leaf.getId(), leaf.getName(), chainOcIds, e);
            throw e;
        }
        return new SingleChainResult(SingleChainOutcomeEnum.SUCCESS, leaf.getId(), leaf.getName(),
                chainOcIds, existingIncomeOcIds);
    }

    /**
     * 校验叶子在当前事务内是否仍属于目标帮派、完成状态、大锅饭名单与扫描时间范围。
     *
     * @param factionId 帮派ID
     * @param leaf      叶子OC
     * @param startTime 扫描时间下限（左闭区间）
     * @return 满足候选条件返回{@code true}
     */
    private boolean isStillCandidate(long factionId, TornFactionOcDO leaf, LocalDateTime startTime) {
        if (leaf.getFactionId() == null || leaf.getFactionId() != factionId) {
            return false;
        }
        if (!TornOcStatusEnum.getCompleteStatusList().contains(leaf.getStatus())) {
            return false;
        }
        List<String> rotationList = TornConstants.ROTATION_OC_NAME.get(factionId);
        if (rotationList == null || !rotationList.contains(leaf.getName())) {
            return false;
        }
        return leaf.getExecutedTime() != null && !leaf.getExecutedTime().isBefore(startTime);
    }
}
