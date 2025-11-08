package pn.torn.goldeneye.torn.model.faction.crime.income;

import lombok.Data;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;

import java.math.BigDecimal;

/**
 * 工时计算结果DTO
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Data
public class WorkingHoursDTO {
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
     * 基础工时
     */
    private Integer baseWorkingHours;
    /**
     * 工时系数
     */
    private BigDecimal coefficient;
    /**
     * 有效工时
     */
    private BigDecimal effectiveWorkingHours;
    /**
     * 加入顺序
     */
    private Integer joinOrder;

    public WorkingHoursDTO(TornFactionOcSlotDO slot, int baseWorkingHours, BigDecimal coefficient,
                           BigDecimal effectiveWorkingHours, int joinOrder) {
        this.userId = slot.getUserId();
        this.position = slot.getPosition();
        this.passRate = slot.getPassRate();
        this.baseWorkingHours = baseWorkingHours;
        this.coefficient = coefficient;
        this.effectiveWorkingHours = effectiveWorkingHours;
        this.joinOrder = joinOrder;
    }
}