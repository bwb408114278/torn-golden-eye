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
     * OC名称
     */
    private String ocName;
    /**
     * OC等级
     */
    private Integer rank;
    /**
     * OC完成时间
     */
    private LocalDateTime ocExecutedTime;
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
     * 道具成本
     */
    private Long itemCost;
    /**
     * 最终收益（含道具报销）
     */
    private Long finalIncome;
    /**
     * 是否成功
     */
    private Boolean isSuccess;
    /**
     * 总奖金
     */
    private Long totalReward;
    /**
     * 总道具支出
     */
    private Long totalItemCost;

    public TornFactionOcIncomeDO(TornFactionOcDO oc, IncomeCalculationDTO income) {
        this.ocId = oc.getId();
        this.ocName = oc.getName();
        this.rank = oc.getRank();
        this.ocExecutedTime = oc.getExecutedTime();
        this.userId = income.getUserId();
        this.position = income.getPosition();
        this.passRate = income.getPassRate();
        this.baseWorkingHours = income.getWorkingHours().getBaseWorkingHours();
        this.coefficient = income.getWorkingHours().getCoefficient();
        this.effectiveWorkingHours = income.getWorkingHours().getEffectiveWorkingHours();
        this.itemCost = income.getItemCost();
        this.totalItemCost = income.getTotalItemCost();
        this.totalReward = income.getRewardMoney();
        this.finalIncome = income.getNetReward();
    }
}