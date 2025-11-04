package pn.torn.goldeneye.torn.service.faction.oc.income;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.constants.torn.TornConstants;
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
import pn.torn.goldeneye.torn.model.faction.crime.income.IncomeDetailVO;
import pn.torn.goldeneye.torn.model.faction.crime.income.UserIncomeVO;
import pn.torn.goldeneye.torn.model.faction.crime.income.WorkingHoursDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * OC收益计算服务（增强版）
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TornOcIncomeService {
    private final TornOcWorkingHourService workingHourService;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO ocSlotDao;
    private final TornFactionOcIncomeDAO incomeDao;
    private final TornFactionOcIncomeSummaryDAO incomeSummaryDao;

    /**
     * 计算并保存OC收益
     */
    @Transactional(rollbackFor = Exception.class)
    public void calculateAndSaveIncome(TornFactionOcDO oc) {
        // 1. 检查OC是否已完成
        if (!TornOcStatusEnum.getCompleteStatusList().contains(oc.getStatus())) {
            throw new BizException("OC未完成，无法计算收益");
        }

        // 2. 检查是否已计算过
        long existCount = incomeDao.lambdaQuery().eq(TornFactionOcIncomeDO::getOcId, oc.getId()).count();
        if (existCount > 0) {
            log.warn("OC收益已计算过，跳过: ocId={}", oc.getId());
            return;
        }

        // 3. 计算工时
        List<WorkingHoursDTO> workingHoursList = workingHourService.calculateWorkingHours(oc);
        if (CollectionUtils.isEmpty(workingHoursList)) {
            log.warn("OC没有参与者，跳过收益计算: ocId={}", oc.getId());
            return;
        }

        // 4. 查询所有slot获取道具成本
        List<TornFactionOcSlotDO> slots = ocSlotDao.lambdaQuery()
                .eq(TornFactionOcSlotDO::getOcId, oc.getId())
                .isNotNull(TornFactionOcSlotDO::getUserId)
                .list();
        Map<Long, Long> userItemCostMap = slots.stream()
                .collect(Collectors.toMap(TornFactionOcSlotDO::getUserId,
                        slot -> slot.getOutcomeItemValue() != null ? slot.getOutcomeItemValue() : 0L,
                        Long::sum));

        // 5. 计算总道具成本
        long totalItemCost = userItemCostMap.values().stream()
                .mapToLong(Long::longValue)
                .sum();

        // 6. 计算总有效工时
        BigDecimal totalEffectiveHours = workingHoursList.stream()
                .map(WorkingHoursDTO::getEffectiveWorkingHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 7. 判断成功还是失败
        boolean isSuccess = TornOcStatusEnum.SUCCESSFUL.getCode().equals(oc.getStatus());
        List<IncomeCalculationDTO> incomeList = new ArrayList<>();

        if (isSuccess) {
            // 成功：总收益 - 总道具成本后按工时分配 + 各自道具报销
            long netReward = oc.getRewardMoney() - totalItemCost;
            for (WorkingHoursDTO workingHours : workingHoursList) {
                // 计算工时占比
                BigDecimal ratio = workingHours.getEffectiveWorkingHours()
                        .divide(totalEffectiveHours, 6, RoundingMode.HALF_UP);
                // 计算分配收益
                long allocatedIncome = BigDecimal.valueOf(netReward)
                        .multiply(ratio)
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValue();
                // 获取道具成本
                long itemCost = userItemCostMap.getOrDefault(workingHours.getUserId(), 0L);
                // 最终收益 = 分配收益 + 道具报销
                long finalIncome = allocatedIncome + itemCost;
                incomeList.add(new IncomeCalculationDTO(workingHours, ratio, itemCost, allocatedIncome, finalIncome));
            }
        } else {
            // 失败：收益为0，道具成本平摊（负收益）
            int participantCount = workingHoursList.size();
            long costPerPerson = totalItemCost / participantCount;
            long remainder = totalItemCost % participantCount;
            for (int i = 0; i < workingHoursList.size(); i++) {
                WorkingHoursDTO workingHours = workingHoursList.get(i);
                long itemCost = userItemCostMap.getOrDefault(workingHours.getUserId(), 0L);
                // 平摊成本，余数分配给前几个人
                long sharedCost = i < remainder ? costPerPerson + 1 : costPerPerson;
                // 最终收益 = 自己的道具成本 - 平摊成本
                long finalIncome = itemCost - sharedCost;
                BigDecimal ratio = workingHours.getEffectiveWorkingHours()
                        .divide(totalEffectiveHours, 6, RoundingMode.HALF_UP);

                incomeList.add(new IncomeCalculationDTO(workingHours, ratio, itemCost, 0L, finalIncome));
            }
        }

        // 9. 保存详细记录
        saveIncomeRecords(oc, incomeList, isSuccess, oc.getRewardMoney(), totalItemCost);
        // 10. 更新汇总表
        updateIncomeSummary(oc.getFactionId(), incomeList, isSuccess,
                oc.getRewardMoney(), totalItemCost);

        log.info("OC收益计算完成: ocId={}, 参与人数={}, 总收益={}, 状态={}",
                oc.getId(), incomeList.size(),
                incomeList.stream().mapToLong(IncomeCalculationDTO::getFinalIncome).sum(),
                oc.getStatus());
    }

    /**
     * 月度结算（重新计算收益分配）
     */
    @Transactional(rollbackFor = Exception.class)
    public void monthlySettlement(String yearMonth) {
        log.info("开始月度结算: yearMonth={}", yearMonth);

        // 1. 查询该月所有详细记录
        LocalDateTime startTime = LocalDateTime.parse(yearMonth + "-01 00:00:00",
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        LocalDateTime endTime = startTime.plusMonths(1);

        List<TornFactionOcIncomeDO> monthlyRecords = incomeDao.lambdaQuery()
                .ge(TornFactionOcIncomeDO::getOcExecutedTime, startTime)
                .lt(TornFactionOcIncomeDO::getOcExecutedTime, endTime)
                .list();

        if (CollectionUtils.isEmpty(monthlyRecords)) {
            log.warn("该月没有OC记录: yearMonth={}", yearMonth);
            return;
        }

        // 2. 计算该月总收益和总成本
        long monthlyTotalReward = monthlyRecords.stream()
                .filter(TornFactionOcIncomeDO::getIsSuccess)
                .mapToLong(TornFactionOcIncomeDO::getTotalReward)
                .sum();

        long monthlyTotalItemCost = monthlyRecords.stream()
                .mapToLong(TornFactionOcIncomeDO::getItemCost)
                .sum();

        long monthlyNetReward = monthlyTotalReward - monthlyTotalItemCost;

        // 3. 按用户分组，计算每个人的总工时
        Map<Long, BigDecimal> userTotalHours = monthlyRecords.stream()
                .collect(Collectors.groupingBy(
                        TornFactionOcIncomeDO::getUserId,
                        Collectors.reducing(BigDecimal.ZERO,
                                TornFactionOcIncomeDO::getEffectiveWorkingHours,
                                BigDecimal::add)));

        BigDecimal totalHours = userTotalHours.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 4. 重新计算每个人的收益
        for (Map.Entry<Long, BigDecimal> entry : userTotalHours.entrySet()) {
            Long userId = entry.getKey();
            BigDecimal userHours = entry.getValue();

            // 计算用户工时占比
            BigDecimal ratio = userHours.divide(totalHours, 6, RoundingMode.HALF_UP);

            // 计算用户净收益
            long userNetIncome = BigDecimal.valueOf(monthlyNetReward)
                    .multiply(ratio)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();

            // 计算用户道具报销
            long userItemCost = monthlyRecords.stream()
                    .filter(r -> r.getUserId().equals(userId))
                    .mapToLong(TornFactionOcIncomeDO::getItemCost)
                    .sum();

            // 最终收益 = 净收益分配 + 道具报销
            long finalIncome = userNetIncome + userItemCost;

            // 更新汇总表
            TornFactionOcIncomeSummaryDO summary = incomeSummaryDao.lambdaQuery()
                    .eq(TornFactionOcIncomeSummaryDO::getFactionId, TornConstants.FACTION_PN_ID)
                    .eq(TornFactionOcIncomeSummaryDO::getUserId, userId)
                    .eq(TornFactionOcIncomeSummaryDO::getYearMonth, yearMonth)
                    .one();

            if (summary != null) {
                summary.setTotalReward(monthlyTotalReward);
                summary.setNetReward(monthlyNetReward);
                summary.setFinalIncome(finalIncome);
                summary.setIsSettled(true);
                summary.setSettledTime(LocalDateTime.now());
                incomeSummaryDao.updateById(summary);
            }
        }

        log.info("月度结算完成: yearMonth={}, 参与人数={}, 总收益={}, 总成本={}, 净收益={}",
                yearMonth, userTotalHours.size(), monthlyTotalReward,
                monthlyTotalItemCost, monthlyNetReward);
    }

    /**
     * 获取已计算收益的OC ID列表
     */
    public List<Long> getCalculatedOcIds(List<Long> ocIds) {
        if (CollectionUtils.isEmpty(ocIds)) {
            return List.of();
        }

        return incomeDao.lambdaQuery()
                .in(TornFactionOcIncomeDO::getOcId, ocIds)
                .select(TornFactionOcIncomeDO::getOcId)
                .groupBy(TornFactionOcIncomeDO::getOcId)
                .list()
                .stream()
                .map(TornFactionOcIncomeDO::getOcId)
                .distinct()
                .toList();
    }

    /**
     * 查询用户在时间范围内的收益
     */
    public UserIncomeVO queryUserIncome(long userId, LocalDateTime startTime, LocalDateTime endTime) {
        // 1. 查询收益记录
        List<TornFactionOcIncomeDO> incomeRecords = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getUserId, userId)
                .ge(TornFactionOcIncomeDO::getOcExecutedTime, startTime)
                .le(TornFactionOcIncomeDO::getOcExecutedTime, endTime)
                .list();
        if (CollectionUtils.isEmpty(incomeRecords)) {
            return new UserIncomeVO(userId, startTime, endTime, 0L, 0, List.of());
        }

        // 2. 计算总收益
        long totalIncome = incomeRecords.stream()
                .mapToLong(TornFactionOcIncomeDO::getFinalIncome)
                .sum();

        // 3. 查询OC详情
        Set<Long> ocIds = incomeRecords.stream()
                .map(TornFactionOcIncomeDO::getOcId)
                .collect(Collectors.toSet());

        List<TornFactionOcDO> ocList = ocDao.queryListByIdList(TornConstants.FACTION_PN_ID, ocIds);
        Map<Long, TornFactionOcDO> ocMap = ocList.stream()
                .collect(Collectors.toMap(TornFactionOcDO::getId, Function.identity()));

        // 4. 查询slot信息获取岗位
        List<TornFactionOcSlotDO> slots = ocSlotDao.lambdaQuery()
                .in(TornFactionOcSlotDO::getOcId, ocIds)
                .eq(TornFactionOcSlotDO::getUserId, userId)
                .list();
        Map<Long, String> ocPositionMap = slots.stream()
                .collect(Collectors.toMap(
                        TornFactionOcSlotDO::getOcId,
                        TornFactionOcSlotDO::getPosition,
                        (a, b) -> a));

        // 5. 构建明细
        List<IncomeDetailVO> details = incomeRecords.stream()
                .map(data -> new IncomeDetailVO(data, ocMap.get(data.getOcId()), ocPositionMap))
                .sorted(Comparator.comparing(IncomeDetailVO::getExecutedTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        return new UserIncomeVO(userId, startTime, endTime, totalIncome, incomeRecords.size(), details);
    }

    /**
     * 保存收益记录
     */
    private void saveIncomeRecords(TornFactionOcDO oc, List<IncomeCalculationDTO> incomeList,
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
     * 更新汇总表（每次OC完成时更新）
     */
    private void updateIncomeSummary(Long factionId, List<IncomeCalculationDTO> incomeList,
                                     boolean isSuccess, long totalReward, long totalItemCost) {
        String yearMonth = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        for (IncomeCalculationDTO income : incomeList) {
            // 查询或创建汇总记录
            TornFactionOcIncomeSummaryDO summary = incomeSummaryDao.lambdaQuery()
                    .eq(TornFactionOcIncomeSummaryDO::getFactionId, factionId)
                    .eq(TornFactionOcIncomeSummaryDO::getUserId, income.getUserId())
                    .eq(TornFactionOcIncomeSummaryDO::getYearMonth, yearMonth)
                    .one();

            if (summary == null) {
                summary = new TornFactionOcIncomeSummaryDO();
                summary.setFactionId(factionId);
                summary.setUserId(income.getUserId());
                summary.setYearMonth(yearMonth);
                summary.setTotalEffectiveHours(BigDecimal.ZERO);
                summary.setTotalItemCost(0L);
                summary.setTotalReward(0L);
                summary.setNetReward(0L);
                summary.setFinalIncome(0L);
                summary.setOcCount(0);
                summary.setSuccessOcCount(0);
                summary.setIsSettled(false);
            }

            // 累加数据
            summary.setTotalEffectiveHours(
                    summary.getTotalEffectiveHours().add(income.getWorkingHours().getEffectiveWorkingHours()));
            summary.setTotalItemCost(summary.getTotalItemCost() + income.getItemCost());
            summary.setOcCount(summary.getOcCount() + 1);

            if (isSuccess) {
                summary.setSuccessOcCount(summary.getSuccessOcCount() + 1);
            }

            // 暂时累加收益（最终收益需要月底重新结算）
            summary.setFinalIncome(summary.getFinalIncome() + income.getFinalIncome());

            if (summary.getId() == null) {
                incomeSummaryDao.save(summary);
            } else {
                incomeSummaryDao.updateById(summary);
            }
        }
    }
}