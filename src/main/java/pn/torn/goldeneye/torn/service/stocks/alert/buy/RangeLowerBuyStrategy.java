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
 * RANGE专属绝对趋势保护（策略专属资格守卫，与{@link #matches}分离）：
 * <ul>
 *   <li>{@code return7d >= -2%}（{@link #RANGE_RETURN_7D_FLOOR}）</li>
 *   <li>{@code MA7 / MA30 - 1 >= -2%}（{@link #TREND_PROTECT_THRESHOLD}）</li>
 *   <li>等于-2%通过；低于-2%以{@value #ABSOLUTE_TREND_GUARD_FAILED}拒绝</li>
 *   <li>{@code return7d}、{@code MA7}或{@code MA30}任一缺失时以{@value #TREND_GUARD_DATA_INSUFFICIENT}
 *       记录数据不足,仍写原始信号与拒绝观察,不建立正式批次,不伪装为普通策略不命中</li>
 * </ul>
 *
 * @author Bai
 * @version 1.4.0
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
     * RANGE专属7日收益下界：return7d >= -2%(冻结,不得复用DEEP的-1%)
     */
    public static final BigDecimal RANGE_RETURN_7D_FLOOR = new BigDecimal("-0.02");
    /**
     * 绝对趋势保护守卫失败原因码(冻结)
     */
    public static final String ABSOLUTE_TREND_GUARD_FAILED = "ABSOLUTE_TREND_GUARD_FAILED";
    /**
     * 绝对趋势保护守卫数据不足原因码: return7d/MA7/MA30任一缺失时使用,与阈值失败区分
     */
    public static final String TREND_GUARD_DATA_INSUFFICIENT = "DATA_INSUFFICIENT";
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
        boolean trendOk = isMaTrendProtectedOrMissing(context.ma7d(), context.ma30d());

        boolean matched = widthOk && positionOk && z1Ok && return6hOk && trendOk;
        if (matched) {
            log.debug("区间下沿-条件命中: stocksId={}, effectiveZ1={}, width30d={}, position30={}",
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

    @Override
    public String absoluteTrendGuardFailureReason(BuyContext context) {
        BigDecimal return7d = context.return7d();
        BigDecimal ma7d = context.ma7d();
        BigDecimal ma30d = context.ma30d();
        if (return7d == null || ma7d == null || ma30d == null) {
            log.debug("区间下沿-绝对趋势守卫数据不足: stocksId={}, return7d={}, ma7d={}, ma30d={}, reason={}",
                    context.stocksId(), return7d, ma7d, ma30d, TREND_GUARD_DATA_INSUFFICIENT);
            return TREND_GUARD_DATA_INSUFFICIENT;
        }
        if (return7d.compareTo(RANGE_RETURN_7D_FLOOR) < 0) {
            log.debug("区间下沿-绝对趋势守卫失败: stocksId={}, return7d={}, reason={}",
                    context.stocksId(), return7d, ABSOLUTE_TREND_GUARD_FAILED);
            return ABSOLUTE_TREND_GUARD_FAILED;
        }
        return null;
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

    /**
     * 判断MA7/MA30中期趋势保护: 数据缺失时视为待守卫判定(不伪装为普通策略不命中),
     * 数据完整时按 {@code MA7/MA30 - 1 >= -2%} 阈值比较。
     *
     * @param ma7d  近7日移动均价(可为null)
     * @param ma30d 近30日移动均价(可为null)
     * @return 数据缺失或趋势未破位时返回true;数据完整且破位时返回false
     */
    private boolean isMaTrendProtectedOrMissing(BigDecimal ma7d, BigDecimal ma30d) {
        if (ma7d == null || ma30d == null) {
            return true;
        }
        return StockStrategyUtils.isTrendProtected(ma7d, ma30d, TREND_PROTECT_THRESHOLD, SCALE);
    }
}
