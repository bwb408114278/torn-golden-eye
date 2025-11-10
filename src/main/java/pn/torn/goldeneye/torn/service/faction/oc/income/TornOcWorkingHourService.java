package pn.torn.goldeneye.torn.service.faction.oc.income;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcCoefficientManager;
import pn.torn.goldeneye.torn.model.faction.crime.income.WorkingHoursDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * OC工时计算服务
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TornOcWorkingHourService {
    private final TornSettingOcCoefficientManager coefficientManager;
    private final TornFactionOcSlotDAO ocSlotDao;

    /**
     * 计算OC所有参与者的工时
     */
    public List<WorkingHoursDTO> calculateWorkingHours(TornFactionOcDO oc) {
        // 1. 查询所有参与的slot，按加入时间排序
        List<TornFactionOcSlotDO> slots = ocSlotDao.lambdaQuery()
                .eq(TornFactionOcSlotDO::getOcId, oc.getId())
                .orderByAsc(TornFactionOcSlotDO::getJoinTime)
                .list();

        if (CollectionUtils.isEmpty(slots)) {
            return List.of();
        }

        int totalSlots = slots.size();
        List<WorkingHoursDTO> workingHoursList = new ArrayList<>();

        // 2. 计算每个人的工时
        for (int i = 0; i < slots.size(); i++) {
            TornFactionOcSlotDO slot = slots.get(i);

            // 2.1 计算基础工时（第一个人工时最多）
            int baseWorkingHours = totalSlots - i;

            // 2.2 获取系数
            BigDecimal coefficient = coefficientManager.getCoefficient(oc.getName(), oc.getRank(),
                    slot.getPosition(), slot.getPassRate());

            // 2.3 计算有效工时
            BigDecimal effectiveWorkingHours = BigDecimal.valueOf(baseWorkingHours)
                    .multiply(coefficient)
                    .setScale(2, RoundingMode.HALF_UP);
            WorkingHoursDTO workingHours = new WorkingHoursDTO(slot, baseWorkingHours, coefficient,
                    effectiveWorkingHours, i + 1);
            workingHoursList.add(workingHours);
        }

        return workingHoursList;
    }
}