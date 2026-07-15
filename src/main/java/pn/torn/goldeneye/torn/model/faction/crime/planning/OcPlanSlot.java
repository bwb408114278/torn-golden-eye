package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.math.BigDecimal;

/**
 * 规划使用的岗位模板。
 */
public record OcPlanSlot(String code, String position, int requiredPassRate,
                         int priority, BigDecimal bestSuccess) {

    public OcPlanSlot {
        bestSuccess = bestSuccess == null ? BigDecimal.ZERO : bestSuccess;
    }
}
