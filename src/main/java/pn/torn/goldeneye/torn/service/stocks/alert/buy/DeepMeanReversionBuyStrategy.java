package pn.torn.goldeneye.torn.service.stocks.alert.buy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBuyStrategyEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Set;

/**
 * 深度均值回归买入策略。
 * <p>
 * 适用风格：NARROW（窄幅震荡）、RANGING（区间震荡）、STEADY（稳健）。
 * <p>
 * 核心逻辑：当价格显著偏离近期均值且接近30日低点，同时中期趋势未破位时触发买入。
 * NARROW风格下对Z1打0.6折，降低窄幅震荡中Z-score的敏感度，避免频繁误触发。
 * <p>
 * 触发条件（全部满足）：
 * <ul>
 *   <li>距30日最低价 {@code <= 0.3%}（pctAbove30dLow {@code <=} 0.003）</li>
 *   <li>effectiveZ1 {@code <=} -2.0</li>
 *   <li>近7日收益率 {@code >=} -1%（return7d {@code >=} -0.01）</li>
 *   <li>MA7 / MA30 - 1 {@code >=} -2%（即中期趋势保护）</li>
 * </ul>
 * NARROW风格：effectiveZ1 = zscore1d × 0.6；其他风格：effectiveZ1 = zscore1d。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Slf4j
@Component
public class DeepMeanReversionBuyStrategy implements StockBuyStrategy {

    /** 买入规则版本 */
    private static final String BUY_RULE_VERSION = "1.0.0";

    /** NARROW风格下Z1的折扣系数 */
    private static final BigDecimal NARROW_Z1_DISCOUNT = new BigDecimal("0.6");

    /** 距30日低点的最大涨幅阈值：0.3% */
    private static final BigDecimal PCT_ABOVE_30D_LOW_THRESHOLD = new BigDecimal("0.003");

    /** effectiveZ1下限阈值 */
    private static final BigDecimal EFFECTIVE_Z1_THRESHOLD = new BigDecimal("-2.0");

    /** 近7日收益率下限阈值：-1% */
    private static final BigDecimal RETURN_7D_THRESHOLD = new BigDecimal("-0.01");

    /** 中期趋势保护阈值：MA7/MA30 - 1 >= -2% */
    private static final BigDecimal TREND_PROTECT_THRESHOLD = new BigDecimal("-0.02");

    /** 质量分基础分 */
    private static final BigDecimal SCORE_BASE = new BigDecimal("100");

    /** 质量分Z1系数 */
    private static final BigDecimal SCORE_Z1_COEFFICIENT = BigDecimal.TEN;

    /** 质量分低点距离系数 */
    private static final BigDecimal SCORE_LOW_DISTANCE_COEFFICIENT = new BigDecimal("1000");

    /** 质量分低点距离基准 */
    private static final BigDecimal SCORE_LOW_DISTANCE_BASE = new BigDecimal("0.003");

    /** BigDecimal运算精度 */
    private static final int SCALE = 18;

    /** 适用的策略适配风格集合 */
    private static final Set<StockStrategyFitEnum> APPLICABLE_STYLES = Set.of(
            StockStrategyFitEnum.NARROW,
            StockStrategyFitEnum.RANGING,
            StockStrategyFitEnum.STEADY
    );

    @Override
    public StockBuyStrategyEnum getStrategyType() {
        return StockBuyStrategyEnum.DEEP_MEAN_REVERSION_BUY;
    }

    @Override
    public boolean matches(BuyContext context) {
        StockStrategyFitEnum style = context.stylePrior();
        if (!isApplicableStyle(style)) {
            log.debug("深度均值回归-风格不适配: stocksId={}, style={}", context.stocksId(), style);
            return false;
        }

        BigDecimal effectiveZ1 = calculateEffectiveZ1(context);
        boolean lowDistanceOk = isNear30dLow(context.pctAbove30dLow());
        boolean z1Ok = effectiveZ1.compareTo(EFFECTIVE_Z1_THRESHOLD) <= 0;
        boolean return7dOk = isReturn7dAcceptable(context.return7d());
        boolean trendOk = isTrendProtected(context.ma7d(), context.ma30d());

        boolean matched = lowDistanceOk && z1Ok && return7dOk && trendOk;
        if (matched) {
            log.info("深度均值回归-条件命中: stocksId={}, effectiveZ1={}, pctAbove30dLow={}, return7d={}",
                    context.stocksId(), effectiveZ1, context.pctAbove30dLow(), context.return7d());
        }
        return matched;
    }

    @Override
    public BigDecimal calculateQualityScore(BuyContext context) {
        BigDecimal effectiveZ1 = calculateEffectiveZ1(context);
        BigDecimal low30Distance = context.pctAbove30dLow();

        // deepScore = 100 + max(0, -effectiveZ1) × 10 + max(0, 0.003 - low30Distance) × 1000
        BigDecimal z1Contribution = maxZero(effectiveZ1.negate()).multiply(SCORE_Z1_COEFFICIENT);

        BigDecimal lowDistanceContribution = SCORE_LOW_DISTANCE_BASE
                .subtract(low30Distance)
                .max(BigDecimal.ZERO)
                .multiply(SCORE_LOW_DISTANCE_COEFFICIENT);

        BigDecimal score = SCORE_BASE
                .add(z1Contribution)
                .add(lowDistanceContribution)
                .setScale(SCALE, RoundingMode.HALF_UP);

        log.debug("深度均值回归-质量分: stocksId={}, effectiveZ1={}, low30Distance={}, score={}",
                context.stocksId(), effectiveZ1, low30Distance, score);
        return score;
    }

    @Override
    public boolean isApplicableStyle(StockStrategyFitEnum style) {
        return style != null && APPLICABLE_STYLES.contains(style);
    }

    /**
     * 计算有效Z1，NARROW风格打0.6折。
     *
     * @param context 买入评估上下文
     * @return 有效Z1值
     */
    private BigDecimal calculateEffectiveZ1(BuyContext context) {
        BigDecimal rawZ1 = context.zscore1d();
        if (StockStrategyFitEnum.NARROW == context.stylePrior()) {
            return rawZ1.multiply(NARROW_Z1_DISCOUNT, MathContext.DECIMAL128)
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }
        return rawZ1;
    }

    /**
     * 判断距30日低点是否在阈值内。
     *
     * @param pctAbove30dLow 参考价相对30日低点的涨幅
     * @return 距离小于等于阈值时返回true
     */
    private boolean isNear30dLow(BigDecimal pctAbove30dLow) {
        return pctAbove30dLow != null && pctAbove30dLow.compareTo(PCT_ABOVE_30D_LOW_THRESHOLD) <= 0;
    }

    /**
     * 判断近7日收益率是否可接受（>= -1%）。
     *
     * @param return7d 近7日收益率
     * @return 收益率大于等于阈值时返回true
     */
    private boolean isReturn7dAcceptable(BigDecimal return7d) {
        return return7d != null && return7d.compareTo(RETURN_7D_THRESHOLD) >= 0;
    }

    /**
     * 中期趋势保护：MA7 / MA30 - 1 >= -2%。
     *
     * @param ma7d  近7日移动均价
     * @param ma30d 近30日移动均价
     * @return 趋势未破位时返回true
     */
    private boolean isTrendProtected(BigDecimal ma7d, BigDecimal ma30d) {
        if (ma7d == null || ma30d == null || ma30d.compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }
        BigDecimal trendDeviation = ma7d.divide(ma30d, SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
        return trendDeviation.compareTo(TREND_PROTECT_THRESHOLD) >= 0;
    }

    /**
     * 取BigDecimal与0的较大值。
     *
     * @param value 输入值
     * @return value > 0 时返回value，否则返回0
     */
    private BigDecimal maxZero(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) > 0 ? value : BigDecimal.ZERO;
    }
}
