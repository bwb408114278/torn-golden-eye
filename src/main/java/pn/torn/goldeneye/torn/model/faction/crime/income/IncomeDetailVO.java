package pn.torn.goldeneye.torn.model.faction.crime.income;

import lombok.Data;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 收益明细VO
 *
 * @version 0.3.0
 * @since 2025.11.03
 */
@Data
public class IncomeDetailVO {
    /**
     * OC ID
     */
    private Long ocId;
    /**
     * OC名称
     */
    private String ocName;
    /**
     * OC级别
     */
    private Integer rank;
    /**
     * 岗位
     */
    private String position;
    /**
     * 执行时间
     */
    private LocalDateTime executedTime;
    /**
     * 最终收益
     */
    private Long finalIncome;
    /**
     * 有效工时
     */
    private BigDecimal effectiveWorkingHours;

    public IncomeDetailVO(TornFactionOcIncomeDO income, TornFactionOcDO oc, Map<Long, String> ocPositionMap) {
        this.ocId = income.getOcId();
        this.ocName = oc != null ? oc.getName() : "";
        this.rank = oc != null ? oc.getRank() : 0;
        this.position = ocPositionMap.get(income.getOcId());
        this.executedTime = oc != null ? oc.getExecutedTime() : null;
        this.finalIncome = income.getFinalIncome();
        this.effectiveWorkingHours = income.getEffectiveWorkingHours();
    }
}