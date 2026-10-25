package pn.torn.goldeneye.torn.service.faction.oc.recommend.rank;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 推荐评分到百分制的换算。
 *
 * <p>排序内核是货币量纲的「单位人天产出 × 开动紧迫度」，但货币不适合直接展示：它是估计值，
 * 展示成精确金额会被当成账本，任何估算误差都会变成对公式的质疑。因此对外只展示0至100的评分，
 * 对内仍用货币量纲排序，两者是严格单调的换算关系，不会出现「评分高但排后面」。</p>
 *
 * <p>换算用对数而不是线性：OC产出跨等级差异极大（7级约2至3M/人天，9级约14M/人天），
 * 线性换算会让绝大多数真实推荐挤在个位数分，或者让9级队一律顶到100分饱和。
 * 对数把「高一倍」映射成固定的分数增量，10分以内的小差距仍可见，极端值也不饱和。</p>
 *
 * <p>锚点固定，不随候选池浮动：{@link OcRecommendTunings#RATING_BASE_VALUE}折0分，
 * {@link OcRecommendTunings#RATING_FULL_VALUE}折100分。固定锚点保证同一支队在任何一次推荐里
 * 拿到同一个分数，不会因为「这一批候选里谁最强」而变脸。</p>
 *
 * @author Bai
 * @version 1.9.4
 * @since 2026.10.25
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class OcRecommendRating {
    /**
     * 评分下限：保证评分恒为正，不会出现0分或负分展示。
     */
    private static final int MIN_RATING = 1;
    /**
     * 评分上限。
     */
    private static final int MAX_RATING = 100;
    /**
     * 百分制满分的分子。
     */
    private static final double FULL_PERCENT = 100.0;

    /**
     * 把货币量纲的推荐评分换算成百分制评分。
     *
     * @param score 推荐评分（单位人天产出 × 开动紧迫度），货币单位
     * @return 百分制评分，取值1至100
     */
    public static int ratingOf(BigDecimal score) {
        if (score == null || score.signum() <= 0) {
            return MIN_RATING;
        }
        double base = OcRecommendTunings.RATING_BASE_VALUE.doubleValue();
        double full = OcRecommendTunings.RATING_FULL_VALUE.doubleValue();
        double ratio = 1.0 + score.doubleValue() / base;
        double fullRatio = 1.0 + full / base;
        int rating = (int) Math.round(FULL_PERCENT * Math.log(ratio) / Math.log(fullRatio));
        return Math.clamp(rating, MIN_RATING, MAX_RATING);
    }
}
