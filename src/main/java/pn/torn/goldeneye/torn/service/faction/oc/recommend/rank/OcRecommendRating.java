package pn.torn.goldeneye.torn.service.faction.oc.recommend.rank;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 推荐评分到百分制的换算。
 *
 * <p>R6货币分只用于诊断。最终展示分由完整业务排序生成，业务并列候选同分，
 * 不再把长期单位人天收益、等级或核心标签换算成会反超真实停转时间的固定带宽。</p>
 *
 * <p>只有R6诊断分使用0.5M/12M固定锚点和对数刻度。最终展示分不承诺跨请求固定，
 * 因为补位优先级取决于当时候选池、真实readyTime和成员DAG边际。</p>
 *
 * @author Bai
 * @version 1.9.6
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

    /**
     * 把业务优先级序号换算成展示分；同一序号的业务并列候选同分。
     *
     * @param priority 从0开始的业务优先级序号，越小越优先
     * @return 1~100的展示分
     */
    public static int ratingOfOrder(int priority) {
        return Math.clamp(MAX_RATING - Math.max(0, priority), MIN_RATING, MAX_RATING);
    }
}
