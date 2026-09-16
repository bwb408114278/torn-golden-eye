package pn.torn.goldeneye.torn.service.faction.attack.contribution;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * RW真赛贡献分计算器
 *
 * <p>无状态纯函数：基础分=101−战神榜名次，系数只由对手最终战争分决定，
 * 单场得分=基础分×系数并保留一位小数。门槛与档位属于冻结的业务口径，
 * 只在本类以私有常量表达，不作为入参外泄。</p>
 *
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
@Component
public class RwContributionCalculator {
    /**
     * 有效对手得分下限（不含），不大于该值的场次不进入贡献榜
     */
    private static final int MIN_OPPONENT_SCORE = 3000;
    /**
     * 低系数与阶梯系数的分界点（含）
     */
    private static final int STEP_BASE_SCORE = 6000;
    /**
     * 低分对手统一系数
     */
    private static final BigDecimal LOW_COEFFICIENT = new BigDecimal("0.5");
    /**
     * 阶梯系数起点
     */
    private static final BigDecimal BASE_COEFFICIENT = new BigDecimal("1.0");
    /**
     * 每档系数增量
     */
    private static final BigDecimal COEFFICIENT_STEP = new BigDecimal("0.1");
    /**
     * 每个系数档覆盖的对手得分跨度
     */
    private static final int STEP_WIDTH = 6000;
    /**
     * 基础分基数，基础分=该基数−战神榜名次
     */
    private static final int BASE_SCORE_RADIX = 101;
    /**
     * 单场得分保留的小数位
     */
    private static final int SCORE_SCALE = 1;

    /**
     * 计算对手得分对应的得分系数。
     *
     * <p>对手得分不大于3000属无效场次；3000与6000之间统一按0.5计算；
     * 6000及以上按 1.0 + 0.1 × floor((对手得分 − 6000) / 6000) 阶梯递增。</p>
     *
     * @param opponentScore 对手最终战争分
     * @return 得分系数
     * @throws IllegalArgumentException 对手得分不大于3000时抛出
     */
    public BigDecimal coefficient(int opponentScore) {
        if (opponentScore <= MIN_OPPONENT_SCORE) {
            throw new IllegalArgumentException("对手得分必须大于" + MIN_OPPONENT_SCORE + ": " + opponentScore);
        }
        if (opponentScore < STEP_BASE_SCORE) {
            return LOW_COEFFICIENT;
        }

        int stepCount = (opponentScore - STEP_BASE_SCORE) / STEP_WIDTH;
        return BASE_COEFFICIENT.add(COEFFICIENT_STEP.multiply(BigDecimal.valueOf(stepCount)));
    }

    /**
     * 计算战神榜名次对应的基础分。
     *
     * @param rank 战神榜名次，从1开始
     * @return 基础分，名次越靠前分数越高
     */
    public int baseScore(int rank) {
        return BASE_SCORE_RADIX - rank;
    }

    /**
     * 计算单场贡献得分。
     *
     * @param rank          战神榜名次，从1开始
     * @param opponentScore 对手最终战争分
     * @return 单场得分，保留一位小数且四舍五入
     * @throws IllegalArgumentException 对手得分不大于3000时抛出
     */
    public BigDecimal warScore(int rank, int opponentScore) {
        return BigDecimal.valueOf(baseScore(rank))
                .multiply(coefficient(opponentScore))
                .setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }
}
