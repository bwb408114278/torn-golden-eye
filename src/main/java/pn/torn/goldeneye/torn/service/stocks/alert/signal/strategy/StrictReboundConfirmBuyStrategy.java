package pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBuyStrategyEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.BuyContext;

/**
 * 严格反弹确认买入策略。
 * <p>
 * 仅适用风格：WEAK（弱势）、DECLINER（持续下行）。
 * <p>
 * 核心逻辑：在弱势/下行股票中，要求价格接近30日低点、出现明确的日级反弹（return1d > 0）、
 * Z1转正且价格未脱离MA30，以此确认反弹动能后再入场，避免抄底在半山腰。
 * <p>
 * 触发条件（全部满足）：
 * <ul>
 *   <li>距30日最低价 {@code <=} 0.5%（pctAbove30dLow {@code <=} 0.005）</li>
 *   <li>近1日收益率 {@code >} 0（return1d {@code >} 0）</li>
 *   <li>Z1 {@code >=} 0.8（zscore1d {@code >=} 0.8）</li>
 *   <li>当前价格 {@code <=} MA30 × 1.002（referencePrice {@code <=} ma30d × 1.002）</li>
 * </ul>
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.07.24
 */
@Slf4j
@Component
public class StrictReboundConfirmBuyStrategy implements StockBuyStrategy {
    /**
     * 距30日低点的最大涨幅阈值：0.5%
     */
    private static final BigDecimal PCT_ABOVE_30D_LOW_THRESHOLD = new BigDecimal("0.005");
    /**
     * Z1下限阈值
     */
    private static final BigDecimal Z1_THRESHOLD = new BigDecimal("0.8");
    /**
     * 价格相对MA30的上限乘数：1.002
     */
    private static final BigDecimal MA30_PRICE_MULTIPLIER = new BigDecimal("1.002");
    /**
     * 质量分基础分
     */
    private static final BigDecimal SCORE_BASE = new BigDecimal("60");
    /**
     * 质量分Z1系数
     */
    private static final BigDecimal SCORE_Z1_COEFFICIENT = new BigDecimal("5");
    /**
     * 质量分低点距离系数
     */
    private static final BigDecimal SCORE_LOW_DISTANCE_COEFFICIENT = new BigDecimal("1000");
    /**
     * 质量分低点距离基准
     */
    private static final BigDecimal SCORE_LOW_DISTANCE_BASE = new BigDecimal("0.005");
    /**
     * BigDecimal运算精度
     */
    private static final int SCALE = 18;
    /**
     * 适用的策略适配风格集合
     */
    private static final Set<StockStrategyFitEnum> APPLICABLE_STYLES = Set.of(
            StockStrategyFitEnum.WEAK,
            StockStrategyFitEnum.DECLINER
    );

    @Override
    public StockBuyStrategyEnum getStrategyType() {
        return StockBuyStrategyEnum.STRICT_REBOUND_CONFIRM_BUY;
    }

    @Override
    public boolean matches(BuyContext context) {
        StockStrategyFitEnum style = context.stylePrior();
        if (!isApplicableStyle(style)) {
            log.debug("严格反弹确认-风格不适配: stocksId={}, style={}", context.stocksId(), style);
            return false;
        }

        boolean lowDistanceOk = isNear30dLow(context.pctAbove30dLow());
        boolean return1dOk = isReturn1dPositive(context.return1d());
        boolean z1Ok = isZ1Sufficient(context.zscore1d());
        boolean priceOk = isPriceNearMa30(context.referencePrice(), context.ma30d());

        boolean matched = lowDistanceOk && return1dOk && z1Ok && priceOk;
        if (matched) {
            log.debug("严格反弹确认-条件命中: stocksId={}, pctAbove30dLow={}, return1d={}, zscore1d={}",
                    context.stocksId(), context.pctAbove30dLow(), context.return1d(), context.zscore1d());
        }
        return matched;
    }

    @Override
    public BigDecimal calculateQualityScore(BuyContext context) {
        BigDecimal z1 = context.zscore1d();
        BigDecimal low30Distance = context.pctAbove30dLow();

        BigDecimal z1Contribution = z1.multiply(SCORE_Z1_COEFFICIENT);

        BigDecimal lowDistanceContribution = SCORE_LOW_DISTANCE_BASE
                .subtract(low30Distance)
                .max(BigDecimal.ZERO)
                .multiply(SCORE_LOW_DISTANCE_COEFFICIENT);

        BigDecimal score = SCORE_BASE
                .add(z1Contribution)
                .add(lowDistanceContribution)
                .setScale(SCALE, RoundingMode.HALF_UP);

        log.debug("严格反弹确认-质量分: stocksId={}, z1={}, low30Distance={}, score={}",
                context.stocksId(), z1, low30Distance, score);
        return score;
    }

    @Override
    public boolean isApplicableStyle(StockStrategyFitEnum style) {
        return style != null && APPLICABLE_STYLES.contains(style);
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
     * 判断近1日收益率是否为正。
     *
     * @param return1d 近1日收益率
     * @return 收益率大于0时返回true
     */
    private boolean isReturn1dPositive(BigDecimal return1d) {
        return return1d != null && return1d.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 判断Z1是否达到反弹确认门槛（>= 0.8）。
     *
     * @param zscore1d 参考价相对近1日均值的Z-score
     * @return Z1大于等于阈值时返回true
     */
    private boolean isZ1Sufficient(BigDecimal zscore1d) {
        return zscore1d != null && zscore1d.compareTo(Z1_THRESHOLD) >= 0;
    }

    /**
     * 判断当前价格是否未脱离MA30（referencePrice <= ma30d × 1.002）。
     *
     * @param referencePrice 当前参考价格
     * @param ma30d          近30日移动均价
     * @return 价格未超过MA30上限时返回true
     */
    private boolean isPriceNearMa30(BigDecimal referencePrice, BigDecimal ma30d) {
        if (referencePrice == null || ma30d == null) {
            return false;
        }
        BigDecimal priceUpperBound = ma30d.multiply(MA30_PRICE_MULTIPLIER);
        return referencePrice.compareTo(priceUpperBound) <= 0;
    }
}
