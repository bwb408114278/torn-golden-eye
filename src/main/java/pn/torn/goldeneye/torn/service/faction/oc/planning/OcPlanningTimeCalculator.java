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

    public static int calculateSequentialMemberDays(int memberCount) {
        if (memberCount < 0) {
            throw new IllegalArgumentException("OC人数不能小于0");
        }
        return memberCount * (memberCount + 1) / 2;
    }

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
