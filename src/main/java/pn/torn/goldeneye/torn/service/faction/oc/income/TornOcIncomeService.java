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
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.IncomeCalculationDTO;
import pn.torn.goldeneye.torn.model.faction.crime.income.IncomeDetailVO;
import pn.torn.goldeneye.torn.model.faction.crime.income.UserIncomeVO;
import pn.torn.goldeneye.torn.model.faction.crime.income.WorkingHoursDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
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
     * 计算并保存OC收益
     */
    @Transactional(rollbackFor = Exception.class)
    public void calculateAndSaveIncome(long ocId) {
        // 1. 查询OC信息
        TornFactionOcDO oc = ocDao.getById(ocId);
        if (oc == null) {
            throw new BizException("OC不存在");
        }

        // 2. 检查OC是否已完成
        if (!TornOcStatusEnum.getCompleteStatusList().contains(oc.getStatus())) {
            throw new BizException("OC未完成，无法计算收益");
        }

        // 3. 检查是否已计算过
        long existCount = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getOcId, ocId)
                .count();
        if (existCount > 0) {
            log.warn("OC收益已计算过，跳过: ocId={}", ocId);
            return;
        }

        // 4. 计算工时
        List<WorkingHoursDTO> workingHoursList = workingHourService.calculateWorkingHours(ocId,
                oc.getName(), oc.getRank());
        if (CollectionUtils.isEmpty(workingHoursList)) {
            log.warn("OC没有参与者，跳过收益计算: ocId={}", ocId);
            return;
        }

        // 5. 查询所有slot获取道具成本
        List<TornFactionOcSlotDO> slots = ocSlotDao.lambdaQuery()
                .eq(TornFactionOcSlotDO::getOcId, ocId)
                .isNotNull(TornFactionOcSlotDO::getUserId)
                .list();
        Map<Long, Long> userItemCostMap = slots.stream()
                .collect(Collectors.toMap(TornFactionOcSlotDO::getUserId,
                        slot -> slot.getOutcomeItemValue() != null ? slot.getOutcomeItemValue() : 0L,
                        Long::sum));

        // 6. 计算总道具成本
        long totalItemCost = userItemCostMap.values().stream()
                .mapToLong(Long::longValue)
                .sum();

        // 7. 计算总有效工时
        BigDecimal totalEffectiveHours = workingHoursList.stream()
                .map(WorkingHoursDTO::getEffectiveWorkingHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 8. 判断成功还是失败
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

        // 9. 保存收益记录
        saveIncomeRecords(ocId, incomeList);
        log.info("OC收益计算完成: ocId={}, 参与人数={}, 总收益={}, 状态={}",
                ocId, incomeList.size(),
                incomeList.stream().mapToLong(IncomeCalculationDTO::getFinalIncome).sum(),
                oc.getStatus());
    }

    /**
     * 查询用户在时间范围内的收益
     */
    public UserIncomeVO queryUserIncome(long userId, LocalDateTime startTime, LocalDateTime endTime) {
        // 1. 查询收益记录
        List<TornFactionOcIncomeDO> incomeRecords = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getUserId, userId)
                .ge(TornFactionOcIncomeDO::getCalculatedTime, startTime)
                .le(TornFactionOcIncomeDO::getCalculatedTime, endTime)
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

        List<TornFactionOcDO> ocs = ocDao.queryListByIdList(TornConstants.FACTION_PN_ID, ocIds);
        Map<Long, TornFactionOcDO> ocMap = ocs.stream()
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
    private void saveIncomeRecords(long ocId, List<IncomeCalculationDTO> incomeList) {
        LocalDateTime dateTime = LocalDateTime.now();
        List<TornFactionOcIncomeDO> dataList = incomeList.stream()
                .map(income -> new TornFactionOcIncomeDO(ocId, income, dateTime))
                .toList();
        incomeDao.saveBatch(dataList);
    }
}