package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.flow;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;

/**
 * DAG空位的固定替补标尺。
 *
 * @author Bai
 * @version 1.9.5
 * @since 2026.10.27
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class OcFlowSubstitutePolicy {
    private static final int CORE_THRESHOLD = 65;
    private static final int CORE_DELTA = 5;
    private static final int PUBLIC_DELTA = 10;
    private static final int MAX_RATE = 85;
    private static final double PERCENT = 100.0;

    /**
     * 计算具体岗位的替补成功率。
     *
     * @param requirement 岗位配置，已应用帮派门槛覆盖
     * @return 0~1的替补成功率；门槛或等级缺失时返回null
     */
    public static Double substituteRate(TornSettingOcSlotDO requirement) {
        if (requirement == null || requirement.getPassRate() == null || requirement.getRank() == null) {
            return null;
        }
        int threshold = requirement.getPassRate();
        int rank = requirement.getRank();
        final int delta;
        switch (rank) {
            case 7, 8 -> delta = threshold >= CORE_THRESHOLD ? CORE_DELTA : PUBLIC_DELTA;
            case 9 -> delta = 0;
            default -> {
                return null;
            }
        }
        return Math.min(MAX_RATE, threshold + delta) / PERCENT;
    }
}
