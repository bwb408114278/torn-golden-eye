package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.math.BigDecimal;

/**
 * 规划使用的岗位模板。
 *
 * @param code 岗位唯一编码
 * @param position 岗位名称
 * @param requiredPassRate 岗位最低成功率要求
 * @param priority 岗位匹配优先级
 * @param bestSuccess 该岗位当前可达到的最高成功率
 */public record OcPlanSlot(String code, String position, int requiredPassRate,
                         int priority, BigDecimal bestSuccess) {

    public OcPlanSlot {
        bestSuccess = bestSuccess == null ? BigDecimal.ZERO : bestSuccess;
    }
}
