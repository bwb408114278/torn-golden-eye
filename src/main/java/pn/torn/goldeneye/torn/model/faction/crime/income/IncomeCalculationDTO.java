package pn.torn.goldeneye.torn.model.faction.crime.income;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 收益计算结果DTO
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Data
public class IncomeCalculationDTO {
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 工时信息
     */
    private WorkingHoursDTO workingHours;
    /**
     * 工时占比
     */
    private BigDecimal workingHoursRatio;
    /**
     * 道具成本
     */
    private Long itemCost;
    /**
     * 分配收益
     */
    private Long allocatedIncome;
    /**
     * 最终收益
     */
    private Long finalIncome;

    public IncomeCalculationDTO(WorkingHoursDTO workingHours, BigDecimal workingHoursRatio, long itemCost,
                                long allocatedIncome, long finalIncome) {
        this.userId = workingHours.getUserId();
        this.workingHours = workingHours;
        this.workingHoursRatio = workingHoursRatio;
        this.itemCost = itemCost;
        this.allocatedIncome = allocatedIncome;
        this.finalIncome = finalIncome;
    }
}