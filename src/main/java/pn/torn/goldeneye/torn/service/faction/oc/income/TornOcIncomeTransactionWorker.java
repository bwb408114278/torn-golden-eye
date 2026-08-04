package pn.torn.goldeneye.torn.service.faction.oc.income;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.IncomeCompletenessEnum;
import pn.torn.goldeneye.torn.model.faction.crime.income.OcIncomeKey;
import pn.torn.goldeneye.torn.model.faction.crime.income.OcKey;
import pn.torn.goldeneye.torn.model.faction.crime.income.SingleChainOutcomeEnum;
import pn.torn.goldeneye.torn.model.faction.crime.income.SingleChainResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 单链收益事务Worker。
 *
 * <p>每个叶子在一个独立事务（REQUIRES_NEW）中完成链完整性校验、income完整性审计、
 * 整链明细生成与受影响月份汇总重算，任一环节失败整链回滚，避免批量门面在大事务中循环
 * 提交残缺链。必须通过Spring代理由批量门面调用，禁止在同一Service内自调用。</p>
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
     * 大锅饭名单、扫描时间范围；无真实后继；链回溯完整；整链income按预期业务键完整或待计算。
     * 传入批量门面已预加载的链，事务内仅以一次受控批量查询重新确认链节点仍存在且帮派一致，
     * 不再逐节点回溯，避免查询放大。真实失败异常会穿过事务边界由批量门面捕获统计。</p>
     *
     * @param factionId       帮派ID
     * @param leafOcId        叶子OC ID
     * @param startTime       扫描时间下限（左闭区间）
     * @param chainParentKeys 有效链配置父节点集合，用于等待后继判断
     * @param preloadedChain  批量门面已预加载的完整链（从最早祖先到叶子，含叶子自身）
     * @return 单链处理结果
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public SingleChainResult processSingleChain(long factionId, long leafOcId, LocalDateTime startTime,
                                                Set<OcKey> chainParentKeys, List<TornFactionOcDO> preloadedChain) {
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

        // R9/R15：以一次受控批量查询在事务内重新确认链节点仍存在且帮派一致，缺失则fail-closed
        List<Long> expectedChainOcIds = preloadedChain.stream().map(TornFactionOcDO::getId).toList();
        if (expectedChainOcIds.isEmpty()) {
            return new SingleChainResult(SingleChainOutcomeEnum.ABNORMAL_INCOMPLETE_CHAIN, leaf.getId(),
                    leaf.getName(), List.of(), Set.of(), leaf.getPreviousOcId());
        }
        Map<Long, TornFactionOcDO> freshNodes = ocDao.lambdaQuery()
                .in(TornFactionOcDO::getId, expectedChainOcIds)
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(TornFactionOcDO::getId, node -> node));
        Long missingAncestor = null;
        for (TornFactionOcDO node : preloadedChain) {
            TornFactionOcDO fresh = freshNodes.get(node.getId());
            if (fresh == null) {
                missingAncestor = node.getId();
                break;
            }
            if (!fresh.getFactionId().equals(factionId)) {
                missingAncestor = node.getId();
                break;
            }
        }
        if (missingAncestor != null) {
            log.warn("单链事务内链节点缺失或帮派不一致，整链不结算: factionId={}, leafOcId={}, leafOcName={}, " +
                            "chainOcIds={}, missingAncestorOcId={}",
                    factionId, leaf.getId(), leaf.getName(), expectedChainOcIds, missingAncestor);
            return new SingleChainResult(SingleChainOutcomeEnum.ABNORMAL_INCOMPLETE_CHAIN, leaf.getId(),
                    leaf.getName(), expectedChainOcIds, Set.of(), missingAncestor);
        }
        List<TornFactionOcDO> chain = preloadedChain;
        List<Long> chainOcIds = expectedChainOcIds;

        // R10：income完整性必须按预期业务键判断，禁止把任意一行income等同于完整结算
        Map<Long, List<TornFactionOcSlotDO>> slotsByOcId = incomeService.loadSlotsByOcIds(chainOcIds);
        Set<OcIncomeKey> expectedKeys = incomeService.buildExpectedIncomeKeys(chain, slotsByOcId);
        List<TornFactionOcIncomeDO> existingIncome = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, factionId)
                .in(TornFactionOcIncomeDO::getOcId, chainOcIds)
                .list();
        IncomeCompletenessEnum completeness = incomeService.classifyIncomeCompleteness(expectedKeys, existingIncome);
        Set<Long> existingIncomeOcIds = existingIncome.stream()
                .map(TornFactionOcIncomeDO::getOcId)
                .collect(Collectors.toSet());
        if (completeness == IncomeCompletenessEnum.ALREADY_CALCULATED) {
            return new SingleChainResult(SingleChainOutcomeEnum.ALREADY_CALCULATED, leaf.getId(), leaf.getName(),
                    chainOcIds, existingIncomeOcIds);
        }
        if (completeness == IncomeCompletenessEnum.ABNORMAL_PARTIAL_INCOME) {
            log.warn("链内income不完整，整链不新增任何收益: factionId={}, leafOcId={}, leafOcName={}, " +
                            "chainOcIds={}, existingIncomeOcIds={}, expectedKeyCount={}, actualKeyCount={}",
                    factionId, leaf.getId(), leaf.getName(), chainOcIds, existingIncomeOcIds,
                    expectedKeys.size(), existingIncome.size());
            return new SingleChainResult(SingleChainOutcomeEnum.ABNORMAL_PARTIAL_INCOME, leaf.getId(),
                    leaf.getName(), chainOcIds, existingIncomeOcIds);
        }

        try {
            incomeService.generateAndSaveIncome(leaf, chain);
            // R8：跨月链统一归叶子月份，同时补偿重算受影响父节点月份
            incomeService.recalcAffectedMonths(leaf.getFactionId(), chain);
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
        if (CollectionUtils.isEmpty(rotationList) || !rotationList.contains(leaf.getName())) {
            return false;
        }
        return leaf.getExecutedTime() != null && !leaf.getExecutedTime().isBefore(startTime);
    }
}
