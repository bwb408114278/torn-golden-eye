package pn.torn.goldeneye.torn.model.faction.crime.income;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 工时计算结果DTO
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Data
@Builder
public class WorkingHoursDTO {
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 基础工时
     */
    private BigDecimal baseWorkingHours;
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
}