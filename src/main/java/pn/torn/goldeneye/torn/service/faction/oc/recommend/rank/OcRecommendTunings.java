package pn.torn.goldeneye.torn.service.faction.oc.recommend.rank;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * OC推荐调参常量。
 *
 * <p>集中持有机会成本公式与度量层使用的全部可调数字，避免常数散落在调用方。
 * 当前没有按帮派差异化调整的需求，因此不引入配置管道；每个常量行末注明数据来源，
 * 后续维护不得在无实测依据的情况下修改。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class OcRecommendTunings {
    /**
     * 单岗位期望占用天数（人天）。来源：业务基线4.3实测2026-10已完成岗位占用时长
     * 中位数4.00天、P90为6.00天，取4.5天作为期望值。
     */
    public static final BigDecimal MEMBER_DAYS_PER_SLOT = new BigDecimal("4.5");

    /**
     * 每日等待成本，即一成员天折算的货币量，等价5.0M/人天。
     * 来源：业务基线4.9主力OC的期望单位人天产出中位量级（M/人天）。
     */
    public static final BigDecimal DEFAULT_DAILY_WAIT_COST = new BigDecimal("5000000");

    /**
     * 占用成本系数，表达「我占用这段时间本可产出的机会」。
     * 置0即退化为业务基线5.9的原式（只算期望收益与被锁死人天）。
     */
    public static final BigDecimal OCCUPANCY_COST_FACTOR = BigDecimal.ONE;

    /**
     * 判定实测来源所需的最少成功样本数。来源：业务基线4.6，成功案例内部奖励离散
     * 在样本少于3个时单位人天产出波动可达一倍。
     */
    public static final int MIN_MEASURED_SAMPLES = 3;

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
