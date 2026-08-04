package pn.torn.goldeneye.torn.service.faction.oc.income;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeSummaryDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcChainDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeSummaryDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.ChainIncompleteReasonEnum;
import pn.torn.goldeneye.torn.model.faction.crime.income.ChainLoadResult;
import pn.torn.goldeneye.torn.model.faction.crime.income.IncomeCalculationDTO;
import pn.torn.goldeneye.torn.model.faction.crime.income.IncomeCompletenessEnum;
import pn.torn.goldeneye.torn.model.faction.crime.income.OcIncomeKey;
import pn.torn.goldeneye.torn.model.faction.crime.income.OcKey;
import pn.torn.goldeneye.torn.model.faction.crime.income.WorkingHoursDTO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OC收益计算服务
 *
 * @author Bai
 * @version 1.0.0
 * @since 2025.11.03
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TornOcIncomeService {
    private final TornOcWorkingHourService workingHourService;
    private final TornFactionOcSlotDAO ocSlotDao;
    private final TornFactionOcIncomeDAO incomeDao;
    private final TornFactionOcIncomeSummaryDAO incomeSummaryDao;
    private final TornFactionOcDAO ocDao;
    private final TornSettingOcChainDAO ocChainDao;

    /**
     * 计算并保存OC收益（含受影响月份汇总重算）。
     *
     * <p>供直接调用与既有测试使用，生成整链明细后重算整链涉及的所有月份汇总，
     * 确保跨月链的工时、成本与奖励统一归入叶子完成月份。</p>
     *
     * @param oc 触发计算的叶子OC
     * @throws BizException 链回溯不完整或总有效工时为0时抛出，整链回滚
     */
    @Transactional(rollbackFor = Exception.class)
    public void calculateAndSaveIncome(TornFactionOcDO oc) {
        ChainLoadResult chainResult = loadOcChain(oc);
        if (!chainResult.complete()) {
            throw new BizException(String.format(
                    "OC链回溯不完整，拒绝计算: leafOcId=%d, missingAncestorOcId=%s, reason=%s",
                    oc.getId(), chainResult.missingAncestorOcId(), chainResult.reason()));
        }
        generateAndSaveIncome(oc, chainResult.chain());
        recalcAffectedMonths(oc.getFactionId(), chainResult.chain());
    }

    /**
     * 使用调用方已加载的完整链生成并保存OC收益明细。
     *
     * <p>供单链事务Worker在自身事务内复用已回溯的链，避免重复逐节点查询。本方法只写入
     * income明细，月度汇总由调用方在Worker事务内另行调用，便于整链原子提交。
     * 总有效工时为0时抛出业务异常，避免把无有效工时的链误报为成功。</p>
     *
     * @param leaf    触发计算的叶子OC
     * @param ocChain 从最早步骤到最终步骤的完整OC链（含叶子自身）
     * @throws BizException 总有效工时为0时抛出，调用方应回滚且不得计数成功
     */
    void generateAndSaveIncome(TornFactionOcDO leaf, List<TornFactionOcDO> ocChain) {
        // 1. 计算工时
        Map<Long, List<WorkingHoursDTO>> stepWorkingHoursMap = new LinkedHashMap<>();
        for (TornFactionOcDO stepOc : ocChain) {
            List<WorkingHoursDTO> whs = workingHourService.calculateWorkingHours(stepOc);
            if (!CollectionUtils.isEmpty(whs)) {
                stepWorkingHoursMap.put(stepOc.getId(), whs);
            }
        }
        // 2. 计算全叶子节点总有效工时
        BigDecimal totalEffectiveHours = stepWorkingHoursMap.values().stream()
                .flatMap(List::stream)
                .map(WorkingHoursDTO::getEffectiveWorkingHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalEffectiveHours.compareTo(BigDecimal.ZERO) == 0) {
            throw new BizException(String.format(
                    "OC链总有效工时为0，拒绝生成收益: leafOcId=%d, leafOcName=%s, chainOcIds=%s",
                    leaf.getId(), leaf.getName(),
                    ocChain.stream().map(TornFactionOcDO::getId).toList()));
        }
        // 3. 计算全叶子节点总道具成本
        List<Long> allOcIds = ocChain.stream().map(TornFactionOcDO::getId).toList();
        List<TornFactionOcSlotDO> slots = ocSlotDao.lambdaQuery()
                .in(TornFactionOcSlotDO::getOcId, allOcIds)
                .list();
        Map<Long, Long> userItemCostMap = slots.stream()
                .collect(Collectors.toMap(TornFactionOcSlotDO::getUserId,
                        TornFactionOcSlotDO::getOutcomeItemValue, Long::sum));
        long totalItemCost = userItemCostMap.values().stream()
                .mapToLong(Long::longValue)
                .sum();
        // 4. 按步骤生成income记录
        boolean isSuccess = TornOcStatusEnum.SUCCESSFUL.getCode().equals(leaf.getStatus());
        List<IncomeCalculationDTO> incomeList;
        long itemReward = !StringUtils.hasText(leaf.getRewardItemsValue()) ? 0 :
                Arrays.stream(leaf.getRewardItemsValue().split("#")).map(Long::parseLong).reduce(0L, Long::sum);
        long totalReward = leaf.getRewardMoney() + itemReward;
        long netReward = totalReward - totalItemCost;
        for (TornFactionOcDO stepOc : ocChain) {
            List<WorkingHoursDTO> workingHoursList = stepWorkingHoursMap.get(stepOc.getId());
            incomeList = new ArrayList<>();
            for (WorkingHoursDTO workingHours : workingHoursList) {
                long itemCost = userItemCostMap.getOrDefault(workingHours.getUserId(), 0L);
                incomeList.add(new IncomeCalculationDTO(workingHours, itemCost, totalItemCost,
                        totalReward, netReward));
            }

            // 5. 保存详细记录
            saveIncomeRecords(stepOc, incomeList, isSuccess, totalReward, totalItemCost);
        }
    }

    /**
     * 保存收益记录
     */
    public void saveIncomeRecords(TornFactionOcDO oc, List<IncomeCalculationDTO> incomeList,
                                  boolean isSuccess, long totalReward, long totalItemCost) {
        List<TornFactionOcIncomeDO> dataList = incomeList.stream()
                .map(income -> {
                    TornFactionOcIncomeDO data = new TornFactionOcIncomeDO(oc, income);
                    data.setIsSuccess(isSuccess);
                    data.setTotalReward(totalReward);
                    data.setTotalItemCost(totalItemCost);
                    return data;
                })
                .toList();
        incomeDao.saveBatch(dataList);
    }

    /**
     * 按指定月份重新计算收益汇总（用于月度结算）。
     *
     * <p>月度汇总的数据集按“结算叶子完成月份”选择，而不是按每条明细自身完成月份选择：
     * 先找出目标月份完成的结算叶子，批量回溯完整链节点，再查询这些链节点的全部income，
     * 把整条链的有效工时、道具成本、OC次数和成功次数统一归入叶子月份。父节点月份不得单独承载
     * 该链的部分数据，单步OC继续按自身完成月份归属。重算会同步清除该月不再适用用户的旧汇总行，
     * 保证跨月链补偿与幂等语义。</p>
     *
     * @param factionId 帮派ID
     * @param yearMonth 目标年月，格式yyyy-MM
     * @throws BizException 同一结算叶子分组内出现不同奖励金额时抛出，要求调用方回滚
     */
    public void calcMonthlyIncomeSummary(long factionId, String yearMonth) {
        // 1. 找出目标月份完成的结算叶子
        LocalDateTime monthStart = LocalDateTime.parse(yearMonth + "-01 00:00:00",
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        LocalDateTime monthEnd = monthStart.plusMonths(1);
        List<String> rotationList = TornConstants.ROTATION_OC_NAME.get(factionId);
        Set<OcKey> chainParentKeys = loadChainParentKeys();
        List<TornFactionOcDO> leaves;
        if (CollectionUtils.isEmpty(rotationList)) {
            leaves = ocDao.lambdaQuery()
                    .eq(TornFactionOcDO::getFactionId, factionId)
                    .in(TornFactionOcDO::getStatus, TornOcStatusEnum.getCompleteStatusList())
                    .ge(TornFactionOcDO::getExecutedTime, monthStart)
                    .lt(TornFactionOcDO::getExecutedTime, monthEnd)
                    .notExists("SELECT 1 FROM torn_faction_oc child WHERE child.previous_oc_id = torn_faction_oc.id AND child.deleted = 0")
                    .list();
        } else {
            leaves = ocDao.lambdaQuery()
                    .eq(TornFactionOcDO::getFactionId, factionId)
                    .in(TornFactionOcDO::getStatus, TornOcStatusEnum.getCompleteStatusList())
                    .in(TornFactionOcDO::getName, rotationList)
                    .ge(TornFactionOcDO::getExecutedTime, monthStart)
                    .lt(TornFactionOcDO::getExecutedTime, monthEnd)
                    .notExists("SELECT 1 FROM torn_faction_oc child WHERE child.previous_oc_id = torn_faction_oc.id AND child.deleted = 0")
                    .list();
        }
        // 排除仍在等待后继节点的成功配置链父节点，避免被误判为结算叶子
        leaves = leaves.stream()
                .filter(oc -> !(TornOcStatusEnum.SUCCESSFUL.getCode().equals(oc.getStatus())
                        && chainParentKeys.contains(new OcKey(oc.getName(), oc.getRank()))))
                .toList();
        if (CollectionUtils.isEmpty(leaves)) {
            // 该月没有结算叶子，清除可能残留的旧汇总
            purgeStaleSummaryRows(factionId, yearMonth, Set.of());
            log.warn("该月没有结算叶子，清除旧汇总: factionId={}, yearMonth={}", factionId, yearMonth);
            return;
        }

        // 2. 批量回溯这些叶子的完整链节点，并查询其全部income
        ChainContext chainContext = buildChainContextForLeaves(factionId, leaves);
        List<Long> chainNodeIds = new ArrayList<>(chainContext.nodeMap().keySet());
        List<TornFactionOcIncomeDO> monthlyRecords = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, factionId)
                .in(TornFactionOcIncomeDO::getOcId, chainNodeIds)
                .list();
        if (CollectionUtils.isEmpty(monthlyRecords)) {
            purgeStaleSummaryRows(factionId, yearMonth, Set.of());
            log.warn("该月没有结算income，清除旧汇总: yearMonth={}", yearMonth);
            return;
        }

        // 3. 计算该月总收益（按结算叶子去重）和总成本
        long monthlyTotalReward = calculateMonthlyTotalReward(factionId, yearMonth, monthlyRecords, chainContext);
        long monthlyTotalItemCost = monthlyRecords.stream()
                .mapToLong(TornFactionOcIncomeDO::getItemCost)
                .sum();
        long monthlyNetReward = monthlyTotalReward - monthlyTotalItemCost;

        // 4. 按用户分组，计算每个人的统计数据
        Map<Long, List<TornFactionOcIncomeDO>> userRecordsMap = monthlyRecords.stream()
                .collect(Collectors.groupingBy(TornFactionOcIncomeDO::getUserId));
        // 5. 计算所有用户的总有效工时
        BigDecimal totalEffectiveHours = monthlyRecords.stream()
                .map(TornFactionOcIncomeDO::getEffectiveWorkingHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 6. 为每个用户计算或更新汇总记录
        for (Map.Entry<Long, List<TornFactionOcIncomeDO>> entry : userRecordsMap.entrySet()) {
            Long userId = entry.getKey();
            List<TornFactionOcIncomeDO> userRecords = entry.getValue();

            // 计算用户的总有效工时
            BigDecimal userTotalHours = userRecords.stream()
                    .map(TornFactionOcIncomeDO::getEffectiveWorkingHours)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 计算用户的工时占比
            BigDecimal ratio = totalEffectiveHours.compareTo(BigDecimal.ZERO) > 0
                    ? userTotalHours.divide(totalEffectiveHours, 6, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            // 计算用户的净收益分配
            long userNetIncome = BigDecimal.valueOf(monthlyNetReward)
                    .multiply(ratio)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();

            // 计算用户的道具成本
            long userItemCost = userRecords.stream()
                    .mapToLong(TornFactionOcIncomeDO::getItemCost)
                    .sum();

            // 计算用户的最终收益 = 净收益分配 + 道具报销
            long finalIncome = userNetIncome + userItemCost;

            // 计算用户的OC数量和成功OC数量
            int ocCount = userRecords.size();
            int successOcCount = (int) userRecords.stream()
                    .filter(TornFactionOcIncomeDO::getIsSuccess)
                    .count();

            // 查询或创建汇总记录
            TornFactionOcIncomeSummaryDO summary = incomeSummaryDao.lambdaQuery()
                    .eq(TornFactionOcIncomeSummaryDO::getUserId, userId)
                    .eq(TornFactionOcIncomeSummaryDO::getFactionId, factionId)
                    .eq(TornFactionOcIncomeSummaryDO::getYearMonth, yearMonth)
                    .one();
            if (summary == null) {
                summary = new TornFactionOcIncomeSummaryDO();
                summary.setUserId(userId);
                summary.setFactionId(factionId);
                summary.setYearMonth(yearMonth);
                summary.setIsSettled(false);
            }

            // 更新汇总数据
            summary.setTotalEffectiveHours(userTotalHours);
            summary.setTotalItemCost(userItemCost);
            summary.setTotalReward(monthlyTotalReward);
            summary.setNetReward(monthlyNetReward);
            summary.setFinalIncome(finalIncome);
            summary.setOcCount(ocCount);
            summary.setSuccessOcCount(successOcCount);

            if (summary.getId() == null) {
                incomeSummaryDao.save(summary);
            } else {
                incomeSummaryDao.updateById(summary);
            }
        }

        // 7. 清除该月不再适用用户的旧汇总行，保证补偿与幂等
        purgeStaleSummaryRows(factionId, yearMonth, userRecordsMap.keySet());

        log.info("月度汇总重新计算完成: factionId={}, yearMonth={}, 参与人数={}, 总收益={}, 总成本={}, 净收益={}",
                factionId, yearMonth, userRecordsMap.size(), monthlyTotalReward,
                monthlyTotalItemCost, monthlyNetReward);
    }

    /**
     * 清除指定月份中不再参与的用户旧汇总行。
     *
     * @param factionId       帮派ID
     * @param yearMonth       年月
     * @param presentUserIds  当前仍应保留汇总的用户ID集合；为空表示清除该月全部汇总
     */
    private void purgeStaleSummaryRows(long factionId, String yearMonth, Set<Long> presentUserIds) {
        if (CollectionUtils.isEmpty(presentUserIds)) {
            incomeSummaryDao.lambdaUpdate()
                    .eq(TornFactionOcIncomeSummaryDO::getFactionId, factionId)
                    .eq(TornFactionOcIncomeSummaryDO::getYearMonth, yearMonth)
                    .remove();
            return;
        }
        incomeSummaryDao.lambdaUpdate()
                .eq(TornFactionOcIncomeSummaryDO::getFactionId, factionId)
                .eq(TornFactionOcIncomeSummaryDO::getYearMonth, yearMonth)
                .notIn(TornFactionOcIncomeSummaryDO::getUserId, presentUserIds)
                .remove();
    }

    /**
     * 重算整条链涉及的所有受影响月份汇总。
     *
     * <p>跨月链可能同时影响父节点完成月份与叶子完成月份；统一按结算叶子完成月份归属后，
     * 父节点月份会自然去除该链的工时、成本与奖励，避免旧汇总保留历史残量。重算具有幂等语义。</p>
     *
     * @param factionId 帮派ID
     * @param chain     从最早步骤到最终步骤的完整OC链（含叶子自身）
     */
    public void recalcAffectedMonths(long factionId, List<TornFactionOcDO> chain) {
        Set<String> affectedMonths = chain.stream()
                .map(TornFactionOcDO::getExecutedTime)
                .filter(Objects::nonNull)
                .map(time -> time.format(DateTimeUtils.YEAR_MONTH_FORMATTER))
                .collect(Collectors.toSet());
        for (String yearMonth : affectedMonths) {
            calcMonthlyIncomeSummary(factionId, yearMonth);
        }
    }

    /**
     * 计算该月总奖励，按结算叶子分组后每个叶子只计一次奖励。
     *
     * @param factionId      帮派ID
     * @param yearMonth      目标年月
     * @param monthlyRecords 该月归属的全部income（已按结算叶子完成月份筛选）
     * @param chainContext   链上下文
     * @return 该月总奖励
     */
    private long calculateMonthlyTotalReward(long factionId, String yearMonth,
                                             List<TornFactionOcIncomeDO> monthlyRecords,
                                             ChainContext chainContext) {
        Map<Long, List<TornFactionOcIncomeDO>> successByLeaf = monthlyRecords.stream()
                .filter(TornFactionOcIncomeDO::getIsSuccess)
                .collect(Collectors.groupingBy(
                        income -> chainContext.settlementLeafByOcId.getOrDefault(income.getOcId(), income.getOcId())));

        long total = 0L;
        for (Map.Entry<Long, List<TornFactionOcIncomeDO>> entry : successByLeaf.entrySet()) {
            Long leafOcId = entry.getKey();
            List<TornFactionOcIncomeDO> leafRecords = entry.getValue();
            List<Long> distinctRewards = leafRecords.stream()
                    .map(TornFactionOcIncomeDO::getTotalReward)
                    .distinct()
                    .toList();
            if (distinctRewards.size() > 1) {
                throw new BizException(String.format(
                        "月度汇总发现同一结算叶子奖励不一致: factionId=%d, yearMonth=%s, leafOcId=%d, rewards=%s",
                        factionId, yearMonth, leafOcId, distinctRewards));
            }
            total += distinctRewards.getFirst();
        }
        return total;
    }

    /**
     * 构建本批结算叶子涉及的完整链上下文。
     *
     * <p>以目标月份结算叶子为起点，批量加载其所有祖先节点，在内存中构建
     * {@code ocId -> 结算叶子OcId}映射，避免逐节点查询的N+1。</p>
     *
     * @param factionId 帮派ID
     * @param leaves    目标月份完成的结算叶子
     * @return 链上下文
     */
    private ChainContext buildChainContextForLeaves(long factionId, List<TornFactionOcDO> leaves) {
        Map<Long, TornFactionOcDO> nodeMap = loadAncestorNodes(factionId, leaves);
        Map<Long, Long> settlementLeafByOcId = buildSettlementMapping(leaves, nodeMap);
        return new ChainContext(nodeMap, settlementLeafByOcId);
    }

    /**
     * 批量加载种子节点及其全部祖先，避免逐节点查询N+1。
     *
     * <p>以种子节点为起点，按{@code previous_oc_id}分批IN查询，直到无新祖先为止；
     * 供批量门面与月度汇总上下文复用，保证不随候选数逐链重复查询同一祖先。</p>
     *
     * @param factionId 帮派ID
     * @param seedOcs   种子OC集合（候选叶子或结算叶子）
     * @return OC ID到节点的映射（含种子节点与全部祖先）
     */
    Map<Long, TornFactionOcDO> loadAncestorNodes(long factionId, Collection<TornFactionOcDO> seedOcs) {
        Map<Long, TornFactionOcDO> nodeMap = new HashMap<>();
        for (TornFactionOcDO seed : seedOcs) {
            nodeMap.put(seed.getId(), seed);
        }
        Set<Long> loadedIds = new HashSet<>(nodeMap.keySet());
        List<Long> pendingIds = seedOcs.stream()
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
        return nodeMap;
    }

    /**
     * 构建叶子到其整条链全部节点的结算叶子映射。
     *
     * @param leaves  结算叶子
     * @param nodeMap 已加载的链节点映射
     * @return OC ID到结算叶子OC ID的映射
     */
    private Map<Long, Long> buildSettlementMapping(List<TornFactionOcDO> leaves, Map<Long, TornFactionOcDO> nodeMap) {
        Map<Long, Long> settlementLeafByOcId = new HashMap<>();
        for (TornFactionOcDO leaf : leaves) {
            settlementLeafByOcId.put(leaf.getId(), leaf.getId());
            Long cursor = leaf.getPreviousOcId();
            Set<Long> visited = new HashSet<>();
            visited.add(leaf.getId());
            while (cursor != null && visited.add(cursor)) {
                TornFactionOcDO node = nodeMap.get(cursor);
                if (node == null) {
                    break;
                }
                settlementLeafByOcId.put(node.getId(), leaf.getId());
                cursor = node.getPreviousOcId();
            }
        }
        return settlementLeafByOcId;
    }

    /**
     * 沿 previousOcId 链批量回溯，获取完整OC链（从最早步骤到最终步骤）。
     *
     * <p>使用分批IN查询替代逐节点getById，消除链回溯N+1。遇到祖先缺失、被逻辑删除、
     * 帮派不一致或环形引用时返回不完整结果，由调用方fail-closed处理，禁止截断后按单步OC结算。</p>
     *
     * @param finalOc 最终步骤（叶子）OC
     * @return 链回溯结果；{@code complete=false}时{@code chain}为已加载的部分节点（含叶子）
     */
    public ChainLoadResult loadOcChain(TornFactionOcDO finalOc) {
        Map<Long, TornFactionOcDO> nodeMap = new HashMap<>();
        nodeMap.put(finalOc.getId(), finalOc);
        Set<Long> loadedIds = new HashSet<>();
        loadedIds.add(finalOc.getId());

        ChainLoadResult loadFailure = loadChainAncestors(finalOc, nodeMap, loadedIds);
        if (loadFailure != null) {
            return loadFailure;
        }

        // 构建从最早祖先到叶子的有序链，并检测环形引用
        ChainWalkResult walkResult = walkChain(finalOc, nodeMap);
        if (walkResult.cycleNodeId() != null) {
            return new ChainLoadResult(walkResult.chain(), false, walkResult.cycleNodeId(),
                    ChainIncompleteReasonEnum.CYCLE);
        }
        return new ChainLoadResult(walkResult.chain(), true, null, null);
    }

    /**
     * 按批回溯加载叶子的全部祖先节点。
     *
     * <p>祖先缺失（含被逻辑删除）或帮派不一致时返回不完整结果，由调用方fail-closed处理；
     * 全部加载成功返回{@code null}。</p>
     *
     * @param finalOc   叶子OC
     * @param nodeMap   已含叶子的节点映射，成功时追加全部祖先
     * @param loadedIds 已加载节点ID集合
     * @return 加载失败的不完整结果；全部成功返回{@code null}
     */
    private ChainLoadResult loadChainAncestors(TornFactionOcDO finalOc, Map<Long, TornFactionOcDO> nodeMap,
                                               Set<Long> loadedIds) {
        List<Long> pendingIds = new ArrayList<>();
        if (finalOc.getPreviousOcId() != null) {
            pendingIds.add(finalOc.getPreviousOcId());
        }
        while (!pendingIds.isEmpty()) {
            List<TornFactionOcDO> loaded = ocDao.lambdaQuery()
                    .in(TornFactionOcDO::getId, pendingIds)
                    .list();
            Set<Long> foundIds = loaded.stream().map(TornFactionOcDO::getId).collect(Collectors.toSet());
            for (Long expectedId : pendingIds) {
                if (!foundIds.contains(expectedId)) {
                    return new ChainLoadResult(partialChain(finalOc, nodeMap), false, expectedId,
                            ChainIncompleteReasonEnum.MISSING_ANCESTOR);
                }
            }
            List<Long> nextPending = new ArrayList<>();
            for (TornFactionOcDO node : loaded) {
                if (!Objects.equals(node.getFactionId(), finalOc.getFactionId())) {
                    return new ChainLoadResult(partialChain(finalOc, nodeMap), false, node.getId(),
                            ChainIncompleteReasonEnum.FACTION_MISMATCH);
                }
                nodeMap.put(node.getId(), node);
                loadedIds.add(node.getId());
                if (node.getPreviousOcId() != null && !loadedIds.contains(node.getPreviousOcId())) {
                    nextPending.add(node.getPreviousOcId());
                }
            }
            pendingIds = nextPending;
        }
        return null;
    }

    /**
     * 从叶子向根遍历链，构建从最早祖先到叶子的有序链并检测环形引用。
     *
     * <p>不完整链场景下也返回已加载节点的有序链，供fail-closed结果携带部分链信息。</p>
     *
     * @param finalOc  叶子OC
     * @param nodeMap  已加载节点映射
     * @return 链遍历结果
     */
    private ChainWalkResult walkChain(TornFactionOcDO finalOc, Map<Long, TornFactionOcDO> nodeMap) {
        List<TornFactionOcDO> chain = new ArrayList<>();
        Deque<TornFactionOcDO> stack = new ArrayDeque<>();
        stack.push(finalOc);
        Long cursor = finalOc.getPreviousOcId();
        Set<Long> visited = new HashSet<>();
        visited.add(finalOc.getId());
        Long cycleNodeId = null;
        while (cursor != null && cycleNodeId == null) {
            if (!visited.add(cursor)) {
                cycleNodeId = cursor;
            } else {
                TornFactionOcDO node = nodeMap.get(cursor);
                if (node != null) {
                    stack.push(node);
                    cursor = node.getPreviousOcId();
                } else {
                    cursor = null;
                }
            }
        }
        while (!stack.isEmpty()) {
            chain.add(stack.pop());
        }
        return new ChainWalkResult(chain, cycleNodeId);
    }

    /**
     * 使用已加载的节点构建从最早祖先到叶子的部分有序链（用于不完整结果返回）。
     *
     * @param finalOc  叶子OC
     * @param nodeMap  已加载节点映射
     * @return 已加载节点的有序链（从叶子向根回溯）
     */
    private List<TornFactionOcDO> partialChain(TornFactionOcDO finalOc, Map<Long, TornFactionOcDO> nodeMap) {
        return walkChain(finalOc, nodeMap).chain();
    }

    /**
     * 批量查询指定OC节点的岗位。
     *
     * @param ocIds OC ID集合
     * @return OC ID到岗位列表的映射
     */
    Map<Long, List<TornFactionOcSlotDO>> loadSlotsByOcIds(Collection<Long> ocIds) {
        if (CollectionUtils.isEmpty(ocIds)) {
            return Map.of();
        }
        return ocSlotDao.lambdaQuery()
                .in(TornFactionOcSlotDO::getOcId, ocIds)
                .list()
                .stream()
                .collect(Collectors.groupingBy(TornFactionOcSlotDO::getOcId));
    }

    /**
     * 根据链节点有效完成岗位计算预期收益业务键集合。
     *
     * <p>预期业务键为(ocId, userId, position)：每个持有用户的岗位对应一条预期income记录，
     * 与活动income的实际业务键精确比较以判断整链是否完整结算。</p>
     *
     * @param chain       完整OC链（含叶子自身）
     * @param slotsByOcId 链节点岗位映射
     * @return 预期收益业务键集合
     */
    Set<OcIncomeKey> buildExpectedIncomeKeys(List<TornFactionOcDO> chain,
                                             Map<Long, List<TornFactionOcSlotDO>> slotsByOcId) {
        Set<OcIncomeKey> expectedKeys = new HashSet<>();
        for (TornFactionOcDO node : chain) {
            List<TornFactionOcSlotDO> slots = slotsByOcId.getOrDefault(node.getId(), List.of());
            for (TornFactionOcSlotDO slot : slots) {
                if (slot.getUserId() == null) {
                    continue;
                }
                expectedKeys.add(new OcIncomeKey(node.getId(), slot.getUserId(), slot.getPosition()));
            }
        }
        return expectedKeys;
    }

    /**
     * 判断链income完整性。
     *
     * <p>实际业务键集合为空表示待计算；实际集合精确等于预期集合且整链完整表示已结算；
     * 其余情况（真子集、超集、重复业务键或链节点缺失）均为异常部分income。</p>
     *
     * @param expectedKeys 预期业务键集合
     * @param actualIncome 活动income记录
     * @return 完整性结论
     */
    IncomeCompletenessEnum classifyIncomeCompleteness(Set<OcIncomeKey> expectedKeys,
                                                      List<TornFactionOcIncomeDO> actualIncome) {
        if (CollectionUtils.isEmpty(actualIncome)) {
            return IncomeCompletenessEnum.PENDING;
        }
        Set<OcIncomeKey> actualKeys = actualIncome.stream()
                .map(income -> new OcIncomeKey(income.getOcId(), income.getUserId(), income.getPosition()))
                .collect(Collectors.toSet());
        boolean hasDuplicate = actualIncome.size() != actualKeys.size();
        if (hasDuplicate || !actualKeys.equals(expectedKeys)) {
            return IncomeCompletenessEnum.ABNORMAL_PARTIAL_INCOME;
        }
        return IncomeCompletenessEnum.ALREADY_CALCULATED;
    }

    /**
     * 一次性加载有效链配置中所有父节点，避免逐条查询。
     *
     * <p>仅加载{@code enabled=true}的有效配置；逻辑删除由MyBatis-Plus全局逻辑删除自动追加
     * {@code deleted = 0}过滤。</p>
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
     * OC链上下文，包含节点映射与结算叶子映射。
     *
     * @param nodeMap               OC ID到节点映射（含本批叶子及全部祖先节点）
     * @param settlementLeafByOcId  OC ID到结算叶子OC ID映射
     */
    private record ChainContext(Map<Long, TornFactionOcDO> nodeMap,
                                Map<Long, Long> settlementLeafByOcId) {
    }

    /**
     * 链遍历结果，包含从最早祖先到叶子的有序链与环形引用检测结果。
     *
     * @param chain       有序链（从最早祖先到叶子，含叶子自身）
     * @param cycleNodeId 检测到环形引用时指向环中重复节点OC ID；无环时为{@code null}
     */
    private record ChainWalkResult(List<TornFactionOcDO> chain, Long cycleNodeId) {
    }
}
