package pn.torn.goldeneye.torn.service.faction.oc;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * OC岗位权重（priority）五档共享计算器。
 * <p>
 * 岗位权重是一个"OC内部占比、合计约100"的连续值，消费侧统一压缩为1~5档。
 * 推荐评分与图片展示共用本计算器，防止两处各自维护分档造成口径漂移；阈值与权重分布的五等分位对齐。
 *
 * @author Bai
 * @version 1.9.0
 * @since 2026.10.05
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class OcSlotTierCalculator {
    private static final int TIER_FIVE_MIN_PRIORITY = 25;
    private static final int TIER_FOUR_MIN_PRIORITY = 20;
    private static final int TIER_THREE_MIN_PRIORITY = 15;
    private static final int TIER_TWO_MIN_PRIORITY = 10;

    /**
     * 将岗位权重压缩为1~5档。
     *
     * @param priority 岗位权重原始值
     * @return 权重档位：25及以上为5档，依次20为4档、15为3档、10为2档，其余为1档
     */
    public static int priorityTier(int priority) {
        if (priority >= TIER_FIVE_MIN_PRIORITY) {
            return 5;
        }
        if (priority >= TIER_FOUR_MIN_PRIORITY) {
            return 4;
        }
        if (priority >= TIER_THREE_MIN_PRIORITY) {
            return 3;
        }
        if (priority >= TIER_TWO_MIN_PRIORITY) {
            return 2;
        }
        return 1;
    }
}
