package pn.torn.goldeneye.torn.service.faction.oc.recommend.rank;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * OC推荐调参常量。
 *
 * <p>集中持有度量层使用的可调数字，避免常数散落在调用方。排序口径（单位人天产出 × 开动紧迫度）
 * 不含货币折算系数，因此2026.10.25起不再保留「每日等待成本」与「占用成本系数」两个常量；
 * 开动紧迫度的分段阈值由{@link OcRecommendStartUrgency}独占，百分制评分的换算锚点由
 * {@link OcRecommendRating}独占，也不放在本类，避免口径漂移。</p>
 *
 * <p>当前没有按帮派差异化调整的需求，因此不引入配置管道；每个常量行末注明数据来源，
 * 后续维护不得在无实测依据的情况下修改。</p>
 *
 * @author Bai
 * @version 1.9.4
 * @since 2026.10.23
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class OcRecommendTunings {
    /**
     * 单岗位期望占用天数（人天），用于把「每队人数」折算成期望总人天，是排序键分母的唯一来源。
     *
     * <p>取值来源：本帮实测占用天数（create_time到ready_time）的中位数——3级6.56、4级5.57、
     * 5级5.00、6级5.78、7级6.02、8级5.69、9级6.02、10级6.53，合计取6.0。2026.10.25之前用的是4.5，
     * 那是按「中位数与P90之间」估的，系统性把每支队的人天低估约25%，进而把单位人天产出整体高估。
     * 统一常量只做整体缩放，不改变同一紧迫度下的排序；跨等级差异（3级比8级慢约21%）留作后续精度项，
     * 不在此处按等级分叉，避免用少数等级的样本过拟合。</p>
     */
    public static final BigDecimal MEMBER_DAYS_PER_SLOT = new BigDecimal("6.0");

    /**
     * 实测样本的可信度权重（伪尝试次数）：实测值按「尝试次数÷(尝试次数+本值)」的权重与等级映射值混合。
     *
     * <p>把实测与映射做可信度加权而不是设硬门槛，是因为硬门槛会让样本刚好不足的OC
     * 整段退回等级阶梯——而等级阶梯对个别OC的偏差可达±40%，反而比带噪声的实测更离谱。
     * 取10次尝试作为先验强度：509次尝试的OC约98%取实测，11次尝试的OC约52%取实测。</p>
     */
    public static final int MEASURED_PRIOR_ATTEMPTS = 10;

    /**
     * 百分制评分的0分锚点（货币单位/人天）：低于该值一律按1分展示，避免出现0分与负分。
     *
     * <p>取值低于全部7级以上OC的实测单位人天产出（最低的Window of Opportunity约2.1M），
     * 只用来兜住1至3级的小额OC，不让它们被压成0分。</p>
     */
    public static final BigDecimal RATING_BASE_VALUE = new BigDecimal("500000");

    /**
     * 百分制评分的100分锚点（货币单位/人天）：达到该值即为满分。
     *
     * <p>取9级队的实测量级（Ace in the Hole约14M/人天），使7级主力队在常规紧迫度下落在
     * 50至85分的区间，既能区分强弱，也不至于一律满分。</p>
     */
    public static final BigDecimal RATING_FULL_VALUE = new BigDecimal("12000000");

    /**
     * 等级阶梯：成功时平均奖励（现金加道具价值），货币单位。
     * 来源：业务基线4.1，窗口executed_time∈[2026-09-01,2026-11-01)，仅成功样本，现金与道具合计。
     */
    private static final Map<Integer, BigDecimal> RANK_SUCCESS_REWARD = Map.ofEntries(
            Map.entry(1, new BigDecimal("800000")),
            Map.entry(2, new BigDecimal("1400000")),
            Map.entry(3, new BigDecimal("4500000")),
            Map.entry(4, new BigDecimal("10200000")),
            Map.entry(5, new BigDecimal("10500000")),
            Map.entry(6, new BigDecimal("41400000")),
            Map.entry(7, new BigDecimal("134500000")),
            Map.entry(8, new BigDecimal("191300000")),
            Map.entry(9, new BigDecimal("641400000")),
            Map.entry(10, new BigDecimal("1000000000")));

    /**
     * 等级阶梯：OC成功率。来源：业务基线4.1同一窗口的成功样本数除以全部样本数。
     */
    private static final Map<Integer, BigDecimal> RANK_SUCCESS_RATE = Map.ofEntries(
            Map.entry(1, new BigDecimal("0.821")),
            Map.entry(2, new BigDecimal("0.755")),
            Map.entry(3, new BigDecimal("0.890")),
            Map.entry(4, new BigDecimal("0.875")),
            Map.entry(5, new BigDecimal("0.840")),
            Map.entry(6, new BigDecimal("0.752")),
            Map.entry(7, new BigDecimal("0.811")),
            Map.entry(8, new BigDecimal("0.754")),
            Map.entry(9, new BigDecimal("0.912")),
            Map.entry(10, BigDecimal.ONE));

    /**
     * 查询等级阶梯的「成功时平均奖励」。
     *
     * @param rank OC等级
     * @return 成功时平均奖励；等级不在阶梯内时返回0，避免把未知等级当成高产出
     */
    public static BigDecimal ladderSuccessReward(Integer rank) {
        return rank == null ? BigDecimal.ZERO : RANK_SUCCESS_REWARD.getOrDefault(rank, BigDecimal.ZERO);
    }

    /**
     * 查询等级阶梯的OC成功率。
     *
     * @param rank OC等级
     * @return OC成功率；等级不在阶梯内时返回1，即不做成功率折算（中性保守一侧）
     */
    public static BigDecimal ladderSuccessRate(Integer rank) {
        return rank == null ? BigDecimal.ONE : RANK_SUCCESS_RATE.getOrDefault(rank, BigDecimal.ONE);
    }
}
