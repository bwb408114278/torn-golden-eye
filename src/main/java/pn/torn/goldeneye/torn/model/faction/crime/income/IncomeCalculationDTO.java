package pn.torn.goldeneye.torn.model.faction.crime.income;

import lombok.Data;

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
     * 岗位
     */
    private String position;
    /**
     * 成功率
     */
    private Integer passRate;
    /**
     * 工时信息
     */
    private WorkingHoursDTO workingHours;
    /**
     * 道具成本
     */
    private Long itemCost;
    /**
     * 总道具成本
     */
    private Long totalItemCost;
    /**
     * 奖励金额
     */
    private Long rewardMoney;
    /**
     * 最终利润
     */
    private Long netReward;

    public IncomeCalculationDTO(WorkingHoursDTO workingHours, long itemCost, long totalItemCost,
                                long rewardMoney, long netReward) {
        this.userId = workingHours.getUserId();
        this.position = workingHours.getPosition();
        this.passRate = workingHours.getPassRate();
        this.workingHours = workingHours;
        this.itemCost = itemCost;
        this.totalItemCost = totalItemCost;
        this.rewardMoney = rewardMoney;
        this.netReward = netReward;
    }
}