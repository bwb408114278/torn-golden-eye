package pn.torn.goldeneye.torn.service.faction.oc.income;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OC收益计算服务
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
    private final TornFactionOcSlotDAO ocSlotDao;
    private final TornFactionOcIncomeDAO incomeDao;
    private final TornFactionOcIncomeSummaryDAO incomeSummaryDao;

    /**
     * 计算并保存OC收益
     */
    @Transactional(rollbackFor = Exception.class)
    public void calculateAndSaveIncome(TornFactionOcDO oc) {
        // 1. 计算工时
        List<WorkingHoursDTO> workingHoursList = workingHourService.calculateWorkingHours(oc);
        if (CollectionUtils.isEmpty(workingHoursList)) {
            log.warn("OC没有参与者，跳过收益计算: ocId={}", oc.getId());
            return;
        }

        // 2. 查询所有slot获取道具成本
        List<TornFactionOcSlotDO> slots = ocSlotDao.lambdaQuery().eq(TornFactionOcSlotDO::getOcId, oc.getId()).list();
        Map<Long, Long> userItemCostMap = slots.stream()
                .collect(Collectors.toMap(TornFactionOcSlotDO::getUserId,
                        TornFactionOcSlotDO::getOutcomeItemValue, Long::sum));

        // 3. 计算总道具成本
        long totalItemCost = userItemCostMap.values().stream()
                .mapToLong(Long::longValue)
                .sum();

        // 4. 判断成功还是失败
        boolean isSuccess = TornOcStatusEnum.SUCCESSFUL.getCode().equals(oc.getStatus());
        List<IncomeCalculationDTO> incomeList = new ArrayList<>();

        long netReward = oc.getRewardMoney() - totalItemCost;
        for (WorkingHoursDTO workingHours : workingHoursList) {
            long itemCost = userItemCostMap.get(workingHours.getUserId());
            incomeList.add(new IncomeCalculationDTO(workingHours, itemCost, totalItemCost,
                    oc.getRewardMoney(), netReward));
        }

        // 5. 保存详细记录
        saveIncomeRecords(oc, incomeList, isSuccess, oc.getRewardMoney(), totalItemCost);
        // 6. 更新汇总表
        calcMonthlyIncomeSummary(oc.getFactionId(), oc.getExecutedTime().format(DateTimeUtils.YEAR_MONTH_FORMATTER));
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

        // 2. 计算该月总收益和总成本
        long monthlyTotalReward = monthlyRecords.stream()
                .filter(TornFactionOcIncomeDO::getIsSuccess)
                .mapToLong(TornFactionOcIncomeDO::getTotalReward)
                .distinct()  // 去重，因为同一个OC的多个用户记录的totalReward是相同的
                .sum();
        long monthlyTotalItemCost = monthlyRecords.stream()
                .mapToLong(TornFactionOcIncomeDO::getItemCost)
                .sum();
        long monthlyNetReward = monthlyTotalReward - monthlyTotalItemCost;

        // 3. 按用户分组，计算每个人的统计数据
        Map<Long, List<TornFactionOcIncomeDO>> userRecordsMap = monthlyRecords.stream()
                .collect(Collectors.groupingBy(TornFactionOcIncomeDO::getUserId));
        // 4. 计算所有用户的总有效工时
        BigDecimal totalEffectiveHours = monthlyRecords.stream()
                .map(TornFactionOcIncomeDO::getEffectiveWorkingHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 5. 为每个用户计算或更新汇总记录
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
}