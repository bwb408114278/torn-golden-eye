package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.math.BigDecimal;

/**
 * 规划使用的岗位模板。
 *
 * @param code             岗位唯一编码
 * @param position         岗位名称
 * @param requiredPassRate 岗位最低成功率要求
 * @param priority         岗位匹配优先级
 * @param bestSuccess      该岗位的大成功贡献占比（岗位设置快照）
 * @author Bai
 * @version 1.9.0
 * @since 2026.07.15
 */
public record OcPlanSlot(
        String code,
        String position,
        int requiredPassRate,
        int priority,
        BigDecimal bestSuccess) {

    public OcPlanSlot {
        bestSuccess = bestSuccess == null ? BigDecimal.ZERO : bestSuccess;
    }
}
