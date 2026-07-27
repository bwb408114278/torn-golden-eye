package pn.torn.goldeneye.torn.service.stocks.alert.buy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBuyStrategyEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * 区间下沿买入策略。
 * <p>
 * 适用风格：NARROW（窄幅震荡）、RANGING（区间震荡）。
 * <p>
 * 核心逻辑：当价格处于30日通道的下沿区域（通道窄、位置低、Z1为负、短期回落），
 * 且中期趋势未破位时触发买入。该策略已通过position30约束位置，因此NARROW风格
 * 不再对Z1打折。
 * <p>
 * 触发条件（全部满足）：
 * <ul>
 *   <li>30日通道宽度 {@code <=} 8%（width30d {@code <=} 0.08）</li>
 *   <li>当前仓位位置 {@code <=} 10%（position30 {@code <=} 0.10）</li>
 *   <li>effectiveZ1 {@code <=} -0.5（NARROW不打折，effectiveZ1 = zscore1d）</li>
 *   <li>近6小时收益率 {@code <=} 0（return6h {@code <=} 0）</li>
 *   <li>趋势保护：MA7 / MA30 - 1 {@code >=} -2%</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Slf4j
@Component
public class RangeLowerBuyStrategy implements StockBuyStrategy {
    /**
     * 30日通道宽度上限阈值：8%
     */
    private static final BigDecimal WIDTH_30D_THRESHOLD = new BigDecimal("0.08");
    /**
     * 仓位位置上限阈值：10%
     */
    private static final BigDecimal POSITION_30_THRESHOLD = new BigDecimal("0.10");
    /**
     * effectiveZ1上限阈值
     */
    private static final BigDecimal EFFECTIVE_Z1_THRESHOLD = new BigDecimal("-0.5");
    /**
     * 趋势保护阈值：MA7/MA30 - 1 >= -2%
     */
    private static final BigDecimal TREND_PROTECT_THRESHOLD = new BigDecimal("-0.02");
    /**
     * 质量分基础分
     */
    private static final BigDecimal SCORE_BASE = new BigDecimal("80");
    /**
     * 质量分位置系数
     */
    private static final BigDecimal SCORE_POSITION_COEFFICIENT = new BigDecimal("100");
    /**
     * 质量分位置基准
     */
    private static final BigDecimal SCORE_POSITION_BASE = new BigDecimal("0.10");
    /**
     * 质量分Z1系数
     */
    private static final BigDecimal SCORE_Z1_COEFFICIENT = new BigDecimal("5");
    /**
     * BigDecimal运算精度
     */
    private static final int SCALE = 18;
    /**
     * 适用的策略适配风格集合
     */
    private static final Set<StockStrategyFitEnum> APPLICABLE_STYLES = Set.of(
            StockStrategyFitEnum.NARROW,
            StockStrategyFitEnum.RANGING
    );

    @Override
    public StockBuyStrategyEnum getStrategyType() {
        return StockBuyStrategyEnum.RANGE_LOWER_BUY;
    }

    @Override
    public boolean matches(BuyContext context) {
        StockStrategyFitEnum style = context.stylePrior();
        if (!isApplicableStyle(style)) {
            log.debug("区间下沿-风格不适配: stocksId={}, style={}", context.stocksId(), style);
            return false;
        }

        BigDecimal effectiveZ1 = calculateEffectiveZ1(context);
        boolean widthOk = isWidthNarrow(context.width30d());
        boolean positionOk = isPositionLow(context.position30());
        boolean z1Ok = effectiveZ1.compareTo(EFFECTIVE_Z1_THRESHOLD) <= 0;
        boolean return6hOk = isReturn6hNonPositive(context.return6h());
        boolean trendOk = StockStrategyUtils.isTrendProtected(context.ma7d(), context.ma30d(),
                TREND_PROTECT_THRESHOLD, SCALE);

        boolean matched = widthOk && positionOk && z1Ok && return6hOk && trendOk;
        if (matched) {
            log.info("区间下沿-条件命中: stocksId={}, effectiveZ1={}, width30d={}, position30={}",
                    context.stocksId(), effectiveZ1, context.width30d(), context.position30());
        }
        return matched;
    }

    @Override
    public BigDecimal calculateQualityScore(BuyContext context) {
        BigDecimal effectiveZ1 = calculateEffectiveZ1(context);
        BigDecimal position30 = context.position30();

        BigDecimal positionContribution = SCORE_POSITION_BASE
                .subtract(position30)
                .max(BigDecimal.ZERO)
                .multiply(SCORE_POSITION_COEFFICIENT);

        BigDecimal z1Contribution = StockStrategyUtils.maxZero(effectiveZ1.negate())
                .multiply(SCORE_Z1_COEFFICIENT);

        BigDecimal score = SCORE_BASE
                .add(positionContribution)
                .add(z1Contribution)
                .setScale(SCALE, RoundingMode.HALF_UP);

        log.debug("区间下沿-质量分: stocksId={}, effectiveZ1={}, position30={}, score={}",
                context.stocksId(), effectiveZ1, position30, score);
        return score;
    }

    @Override
    public boolean isApplicableStyle(StockStrategyFitEnum style) {
        return style != null && APPLICABLE_STYLES.contains(style);
    }

    /**
     * 计算有效Z1，该策略NARROW不打折。
     *
     * @param context 买入评估上下文
     * @return zscore1d原值
     */
    private BigDecimal calculateEffectiveZ1(BuyContext context) {
        return context.zscore1d();
    }

    /**
     * 判断30日通道宽度是否足够窄。
     *
     * @param width30d 30日通道宽度
     * @return 宽度小于等于阈值时返回true
     */
    private boolean isWidthNarrow(BigDecimal width30d) {
        return width30d != null && width30d.compareTo(WIDTH_30D_THRESHOLD) <= 0;
    }

    /**
     * 判断仓位位置是否足够低。
     *
     * @param position30 当前仓位位置
     * @return 位置小于等于阈值时返回true
     */
    private boolean isPositionLow(BigDecimal position30) {
        return position30 != null && position30.compareTo(POSITION_30_THRESHOLD) <= 0;
    }

    /**
     * 判断近6小时收益率是否非正。
     *
     * @param return6h 近6小时收益率
     * @return 收益率小于等于0时返回true
     */
    private boolean isReturn6hNonPositive(BigDecimal return6h) {
        return return6h != null && return6h.compareTo(BigDecimal.ZERO) <= 0;
    }
}
