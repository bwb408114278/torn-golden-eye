package pn.torn.goldeneye.repository.model.faction.oc;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.repository.model.BaseDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.IncomeCalculationDTO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * OC收益分配记录表
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_faction_oc_income", autoResultMap = true)
@NoArgsConstructor
public class TornFactionOcIncomeDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * OC ID
     */
    private Long ocId;
    /**
     * 用户ID
     */
    private Long userId;
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
     * 工时占比
     */
    private BigDecimal workingHoursRatio;
    /**
     * 道具成本
     */
    private Long itemCost;
    /**
     * 分配收益（不含道具报销）
     */
    private Long allocatedIncome;
    /**
     * 最终收益（含道具报销）
     */
    private Long finalIncome;

    public TornFactionOcIncomeDO(long ocId, IncomeCalculationDTO income, LocalDateTime calculatedTime) {
        this.ocId = ocId;
        this.userId = income.getUserId();
        this.baseWorkingHours = income.getWorkingHours().getBaseWorkingHours();
        this.coefficient = income.getWorkingHours().getCoefficient();
        this.effectiveWorkingHours = income.getWorkingHours().getEffectiveWorkingHours();
        this.workingHoursRatio = income.getWorkingHoursRatio();
        this.itemCost = income.getItemCost();
        this.allocatedIncome = income.getAllocatedIncome();
        this.finalIncome = income.getFinalIncome();
        this.calculatedTime = calculatedTime;
    }
}