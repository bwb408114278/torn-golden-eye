package pn.torn.goldeneye.torn.service.faction.oc.income;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeSummaryDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeSummaryDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.IncomeCalculationDTO;
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
import java.util.Set;
import java.util.function.Function;
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

    /**
     * 计算并保存OC收益（含月度汇总重算）。
     *
     * <p>供直接调用与既有测试使用，生成整链明细后重算叶子完成月份的汇总。</p>
     *
     * @param oc 触发计算的叶子OC
     */
    @Transactional(rollbackFor = Exception.class)
    public void calculateAndSaveIncome(TornFactionOcDO oc) {
        generateAndSaveIncome(oc, loadOcChain(oc));
        calcMonthlyIncomeSummary(oc.getFactionId(), oc.getExecutedTime().format(DateTimeUtils.YEAR_MONTH_FORMATTER));
    }

    /**
     * 使用调用方已加载的完整链生成并保存OC收益明细。
     *
     * <p>供单链事务Worker在自身事务内复用已回溯的链，避免重复逐节点查询。本方法只写入
     * income明细，月度汇总由调用方在Worker事务内另行调用，便于整链原子提交。</p>
     *
     * @param leaf    触发计算的叶子OC
     * @param ocChain 从最早步骤到最终步骤的完整OC链（含叶子自身）
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
            log.warn("OC链总有效工时为0，跳过收益计算: ocId={}", leaf.getId());
            return;
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
                long itemCost = userItemCostMap.get(workingHours.getUserId());
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
     * 按指定月份重新计算收益汇总（用于月度结算）
     *
     * <p>月度总奖励按“实际奖励结算叶子”计一次：单步OC自身是结算叶子；成功链的最终叶子是结算单元；
     * 同一链所有步骤与成员共享该叶子奖励但月度只计一次；不同叶子即使奖励金额相同也分别计入；
     * 跨月链父节点归到最终叶子完成月份。同一结算叶子分组内出现不同奖励金额视为数据异常，fail-closed报错。</p>
     *
     * @param factionId 帮派ID
     * @param yearMonth 目标年月，格式yyyy-MM
     * @throws BizException 同一结算叶子分组内出现不同奖励金额时抛出，要求调用方回滚
     */
    public void calcMonthlyIncomeSummary(long factionId, String yearMonth) {
        // 1. 查询该月所有已完成的OC记录
        LocalDateTime startTime = LocalDateTime.parse(yearMonth + "-01 00:00:00",
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        LocalDateTime endTime = startTime.plusMonths(1);
        List<TornFactionOcIncomeDO> monthlyRecords = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, factionId)
                .ge(TornFactionOcIncomeDO::getOcExecutedTime, startTime)
                .lt(TornFactionOcIncomeDO::getOcExecutedTime, endTime)
                .list();
        if (CollectionUtils.isEmpty(monthlyRecords)) {
            log.warn("该月没有OC记录: yearMonth={}", yearMonth);
            return;
        }

        // 2. 构建本批income涉及OC的链上下文（内存回溯，按结算叶子归并）
        ChainContext chainContext = buildChainContext(factionId, monthlyRecords);

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

        log.info("月度汇总重新计算完成: yearMonth={}, 参与人数={}, 总收益={}, 总成本={}, 净收益={}",
                yearMonth, userRecordsMap.size(), monthlyTotalReward,
                monthlyTotalItemCost, monthlyNetReward);
    }

    /**
     * 计算该月总奖励，按结算叶子分组后每个叶子只计一次奖励。
     *
     * @param factionId     帮派ID
     * @param yearMonth     目标年月
     * @param monthlyRecords 该月所有income记录
     * @param chainContext  链上下文
     * @return 该月总奖励
     */
    private long calculateMonthlyTotalReward(long factionId, String yearMonth,
                                             List<TornFactionOcIncomeDO> monthlyRecords,
                                             ChainContext chainContext) {
        Map<Long, List<TornFactionOcIncomeDO>> successByLeaf = monthlyRecords.stream()
                .filter(TornFactionOcIncomeDO::getIsSuccess)
                .collect(Collectors.groupingBy(
                        record -> chainContext.settlementLeafByOcId.get(record.getOcId()) != null
                                ? chainContext.settlementLeafByOcId.get(record.getOcId())
                                : record.getOcId()));

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

            // 跨月链父节点归到最终叶子完成月份：仅当叶子完成月份等于当前结算月份才计入奖励
            LocalDateTime leafTime = chainContext.nodeMap.get(leafOcId) != null
                    ? chainContext.nodeMap.get(leafOcId).getExecutedTime()
                    : leafRecords.getFirst().getOcExecutedTime();
            String leafMonth = leafTime == null ? null : leafTime.format(DateTimeUtils.YEAR_MONTH_FORMATTER);
            if (yearMonth.equals(leafMonth)) {
                total += distinctRewards.getFirst();
            }
        }
        return total;
    }

    /**
     * 构建本批income记录涉及OC的链上下文。
     *
     * <p>以该月income的ocId为起点，批量加载涉及的OC节点及其后继节点，在内存中构建
     * {@code ocId -> 结算叶子OcId}映射，避免逐节点查询的N+1。</p>
     *
     * @param factionId      帮派ID
     * @param monthlyRecords 该月所有income记录
     * @return 链上下文
     */
    private ChainContext buildChainContext(long factionId, List<TornFactionOcIncomeDO> monthlyRecords) {
        Set<Long> ocIds = monthlyRecords.stream()
                .map(TornFactionOcIncomeDO::getOcId)
                .collect(Collectors.toSet());
        if (CollectionUtils.isEmpty(ocIds)) {
            return new ChainContext(Map.of(), Map.of());
        }

        Map<Long, TornFactionOcDO> nodeMap = loadOcNodes(factionId, ocIds);
        Map<Long, Long> childOf = new HashMap<>();
        Set<Long> frontier = new HashSet<>(ocIds);
        while (!frontier.isEmpty()) {
            List<TornFactionOcDO> children = ocDao.lambdaQuery()
                    .eq(TornFactionOcDO::getFactionId, factionId)
                    .in(TornFactionOcDO::getPreviousOcId, frontier)
                    .list();
            Set<Long> nextFrontier = new HashSet<>();
            for (TornFactionOcDO child : children) {
                nodeMap.put(child.getId(), child);
                childOf.putIfAbsent(child.getPreviousOcId(), child.getId());
                nextFrontier.add(child.getId());
            }
            frontier = nextFrontier;
        }

        Map<Long, Long> settlementLeafByOcId = new HashMap<>();
        for (Long ocId : ocIds) {
            Long leafId = ocId;
            Set<Long> visited = new HashSet<>();
            while (childOf.containsKey(leafId) && visited.add(leafId)) {
                leafId = childOf.get(leafId);
            }
            settlementLeafByOcId.put(ocId, leafId);
        }
        return new ChainContext(nodeMap, settlementLeafByOcId);
    }

    /**
     * 批量加载指定OC节点。
     *
     * @param factionId 帮派ID
     * @param ocIds     OC ID集合
     * @return OC ID到节点的映射
     */
    private Map<Long, TornFactionOcDO> loadOcNodes(long factionId, Collection<Long> ocIds) {
        if (CollectionUtils.isEmpty(ocIds)) {
            return Map.of();
        }
        return ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getFactionId, factionId)
                .in(TornFactionOcDO::getId, ocIds)
                .list()
                .stream()
                .collect(Collectors.toMap(TornFactionOcDO::getId, Function.identity()));
    }

    /**
     * 沿 previousOcId 链批量回溯，获取完整OC链（从最早步骤到最终步骤）。
     *
     * <p>使用分批IN查询替代逐节点getById，消除链回溯N+1。</p>
     *
     * @param finalOc 最终步骤（叶子）OC
     * @return 从最早步骤到最终步骤的完整OC链（含叶子自身）
     */
    public List<TornFactionOcDO> loadOcChain(TornFactionOcDO finalOc) {
        Map<Long, TornFactionOcDO> nodeMap = new HashMap<>();
        List<Long> pendingIds = new ArrayList<>();
        if (finalOc.getPreviousOcId() != null) {
            pendingIds.add(finalOc.getPreviousOcId());
        }
        while (!pendingIds.isEmpty()) {
            List<TornFactionOcDO> loaded = ocDao.lambdaQuery()
                    .in(TornFactionOcDO::getId, pendingIds)
                    .list();
            List<Long> nextPending = new ArrayList<>();
            for (TornFactionOcDO node : loaded) {
                nodeMap.put(node.getId(), node);
                if (node.getPreviousOcId() != null && !nodeMap.containsKey(node.getPreviousOcId())) {
                    nextPending.add(node.getPreviousOcId());
                }
            }
            pendingIds = nextPending;
        }

        List<TornFactionOcDO> chain = new ArrayList<>();
        Deque<TornFactionOcDO> stack = new ArrayDeque<>();
        stack.push(finalOc);
        Long cursor = finalOc.getPreviousOcId();
        Set<Long> visited = new HashSet<>();
        while (cursor != null && visited.add(cursor)) {
            TornFactionOcDO node = nodeMap.get(cursor);
            if (node == null) {
                break;
            }
            stack.push(node);
            cursor = node.getPreviousOcId();
        }
        while (!stack.isEmpty()) {
            chain.add(stack.pop());
        }
        return chain;
    }

    /**
     * OC链上下文，包含节点映射与结算叶子映射。
     *
     * @param nodeMap               OC ID到节点映射（含本批及后继节点）
     * @param settlementLeafByOcId  OC ID到结算叶子OC ID映射
     */
    private record ChainContext(Map<Long, TornFactionOcDO> nodeMap,
                                Map<Long, Long> settlementLeafByOcId) {
    }
}
