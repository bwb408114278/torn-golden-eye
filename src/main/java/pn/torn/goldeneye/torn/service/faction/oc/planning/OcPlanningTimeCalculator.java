package pn.torn.goldeneye.torn.service.faction.oc.planning;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * OC逐人准备时间计算器。
 */
public final class OcPlanningTimeCalculator {
    private static final BigDecimal HOURS_PER_STAGE = BigDecimal.valueOf(24);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private OcPlanningTimeCalculator() {
    }

    /**
     * 计算岗位按24小时顺序完成准备阶段时消耗的成员天数。
     *
     * @param memberCount 需要依次准备的成员数量
     * @return 顺序准备过程中累计占用的成员天数；成员数非正时返回0
     */
    public static int calculateSequentialMemberDays(int memberCount) {
        if (memberCount < 0) {
            throw new IllegalArgumentException("OC人数不能小于0");
        }
        return memberCount * (memberCount + 1) / 2;
    }

    /**
     * 根据当前准备进度计算该岗位阶段的剩余小时数。
     *
     * @param progress 0到100之间的准备进度百分比
     * @return 保留两位小数的剩余小时数
     * @throws IllegalArgumentException 进度为空或超出0到100范围时抛出
     */
    public static BigDecimal calculateRemainingHours(BigDecimal progress) {
        if (progress == null || progress.compareTo(BigDecimal.ZERO) < 0
                || progress.compareTo(ONE_HUNDRED) > 0) {
            throw new IllegalArgumentException("准备进度必须在0到100之间");
        }
        return HOURS_PER_STAGE.multiply(BigDecimal.ONE.subtract(progress.divide(ONE_HUNDRED, 6,
                        RoundingMode.HALF_UP)))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
