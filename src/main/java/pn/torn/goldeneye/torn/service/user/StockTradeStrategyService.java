package pn.torn.goldeneye.torn.service.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.stocks.StockPersonalityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.StockStrategyTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.StockTradeActionEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.StockStrategyFeaturePoint;
import pn.torn.goldeneye.torn.model.torn.stocks.trade.StockTradeAdvice;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mTradeFeatureProvider;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mTradeFeatureProvider.ProvidedTradeFeature;
import pn.torn.goldeneye.torn.service.user.StockMonthlyStyleResolver.ResolvedMonthlyStyle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 股票交易策略逻辑层(1.8.0起消费15m特征链与月度风格状态)
 * <p>
 * 特征源由分钟表 {@code torn_stock_strategy_feature} 切换为
 * {@code torn_stock_strategy_feature_15m}(版本过滤,RSI指令触发时现算,取数与就绪分流收敛在
 * {@link Stock15mTradeFeatureProvider});风格源由 {@code sys_setting.STOCK_PERSONALITY}
 * 切换为月度状态表 {@code torn_stock_monthly_state}(选月/沿用/停推收敛在
 * {@link StockMonthlyStyleResolver},缺失不得默认STEADY)。
 * 评分阈值数值全部维持不变;月度成熟度M1_EARLY与风险HIGH只提高{@code SWING_LOW_BUY}
 * 买入门槛(各+10,可叠加),不改变分数。
 *
 * @author Bai
 * @version 1.8.0
 * @since 2026.06.02
 */
@Service
@RequiredArgsConstructor
public class StockTradeStrategyService {
    private final Stock15mTradeFeatureProvider featureProvider;
    private final StockMonthlyStyleResolver monthlyStyleResolver;

    private static final int SCALE = 6;
    private static final double BUY_SCORE_THRESHOLD = 50D;
    private static final double TAKE_PROFIT_SELL_SCORE_THRESHOLD = 55D;
    private static final double REBOUND_SELL_SCORE_THRESHOLD = 45D;
    private static final double QUICK_PROFIT_SELL_SCORE_THRESHOLD = 40D;
    // 阴跌持续检测：两周跌幅阈值
    private static final double PERSISTENT_DECLINE_14D_THRESHOLD = -0.005D;
    // 窗口数据不足时额外提高的买入阈值
    private static final double WINDOW_INSUFFICIENT_PENALTY = 10D;
    // 月度成熟度早期(M1_EARLY)额外提高的买入阈值,与窗口不足惩罚同构
    private static final double MONTHLY_MATURITY_PENALTY = 10D;
    // 月度风险HIGH额外提高的买入阈值;MEDIUM仅展示不改门槛
    private static final double MONTHLY_RISK_PENALTY = 10D;
    // 窄幅股 Z-Score 折扣系数
    private static final double NARROW_BAND_Z_DISCOUNT = 0.6;

    /**
     * 分析结果 - 建议列表与顶部告警文案。
     *
     * @param advices 股票建议(非debug模式已过滤HOLD并按评分降序)
     * @param warnings 顶部告警文案(月度风格停推/缺失等需成员显式可见的异常)
     */
    public record StockTradeAnalysis(
            List<StockTradeAdvice> advices,
            List<String> warnings) {
    }

    /**
     * 分析股票
     */
    public StockTradeAnalysis analyze(LocalDateTime analysisTime, boolean debug) {
        List<ProvidedTradeFeature> providedFeatures = featureProvider.loadLatestFeatures(analysisTime);
        if (CollectionUtils.isEmpty(providedFeatures)) {
            return new StockTradeAnalysis(List.of(), List.of());
        }

        LocalDate targetMonth = analysisTime.toLocalDate().withDayOfMonth(1);
        Map<Integer, ResolvedMonthlyStyle> styles = monthlyStyleResolver.resolveAll(targetMonth);
        List<String> warnings = new ArrayList<>();
        List<StockTradeAdvice> advices = new ArrayList<>(providedFeatures.size());
        for (ProvidedTradeFeature provided : providedFeatures) {
            advices.add(analyzeSingleFeature(provided,
                    styles.get(provided.point().stocksId()), targetMonth, analysisTime, warnings));
        }

        if (debug) {
            return new StockTradeAnalysis(advices.stream()
                    .sorted(Comparator.comparing(StockTradeAdvice::stocksShortname))
                    .toList(), warnings);
        }

        return new StockTradeAnalysis(advices.stream()
                .filter(advice -> advice.action() != StockTradeActionEnum.HOLD)
                .sorted(Comparator.comparing(StockTradeAdvice::score).reversed())
                .toList(), warnings);
    }

    /**
     * 分析单个特征
     */
    private StockTradeAdvice analyzeSingleFeature(ProvidedTradeFeature provided,
                                                  ResolvedMonthlyStyle style,
                                                  LocalDate targetMonth,
                                                  LocalDateTime analysisTime,
                                                  List<String> warnings) {
        StockStrategyFeaturePoint point = provided.point();
        if (style == null || !style.styleAvailable()) {
            String reason = style == null ? "无任何已确认月度状态" : style.blockedReason();
            warnings.add(point.stocksShortname() + "：月度风格不可用（" + reason + "），已停止推荐");
            return toAdvice(newFeature(point, null, false, false, false),
                    holdAllSignal("月度风格不可用：" + reason + "，不推荐"), analysisTime, List.of());
        }
        if (provided.stale()) {
            return toAdvice(newFeature(point, style.personality(), false, false, false),
                    holdAllSignal("特征陈旧：最新特征桶距分析时点超过48小时，不推荐"), analysisTime, List.of());
        }
        if (style.maturity() == StockMaturityEnum.M0_UNMATURE) {
            return toAdvice(newFeature(point, style.personality(), false, false, false),
                    holdAllSignal("月度成熟度M0：历史不足60天，不推荐"), analysisTime, List.of());
        }

        boolean maturityEarly = style.maturity() == StockMaturityEnum.M1_EARLY;
        boolean riskHigh = style.riskLevel() == StockRiskLevelEnum.HIGH;
        StockPersonalityEnum personality = style.personality();
        Double adjustedZ30d = adjustZScoreForNarrowBand(point.zScore30d(), personality);
        boolean fallingKnife = isFallingKnife(point.pctAbove30dLow(),
                point.return1d(), point.zScore30d(), personality);
        boolean persistentDecline = isPersistentDecline(point, personality);

        StockFeature feature = newFeature(point, personality, fallingKnife, persistentDecline,
                provided.windowInsufficient());
        StrategySignal bestSignal = selectBestSignal(List.of(
                buildSwingLowBuySignal(feature, maturityEarly, riskHigh),
                buildSwingReversalBuySignal(feature),
                buildSwingQuickProfitSellSignal(feature),
                buildSwingTakeProfitSellSignal(feature),
                buildSwingReboundSellSignal(feature)));

        List<String> monthlyReasons = new ArrayList<>();
        if (provided.windowInsufficient()) {
            String qualityReason = provided.dataQualityReason() == null ? ""
                    : "(" + provided.dataQualityReason() + ")";
            monthlyReasons.add("特征未就绪" + qualityReason + "：买入门槛+10");
        }
        monthlyReasons.add("月度：风格=" + personality.name()
                + " 成熟度=" + style.maturity().name()
                + " 风险=" + style.riskLevel().name()
                + "（" + style.effectiveMonth() + " 生效）");
        if (style.carriedOver()) {
            monthlyReasons.add("使用 " + style.effectiveMonth() + " 风格（" + YearMonth.from(targetMonth) + " 未生成）");
        }
        if (riskHigh) {
            monthlyReasons.add("月度风险HIGH：买入门槛+10");
        }
        if (maturityEarly) {
            monthlyReasons.add("成熟度早期：买入门槛+10");
        }
        return toAdvice(feature, bestSignal, analysisTime, monthlyReasons);
    }

    /**
     * 构建摇摆低点购买信号
     */
    private StrategySignal buildSwingLowBuySignal(StockFeature feature, boolean maturityEarly, boolean riskHigh) {
        List<String> reasons = new ArrayList<>();
        double score = 0D;

        if (le(feature.pctAbove30dLow(), 0.005D)) {
            score += 30D;
            reasons.add("价格距离30日低点不足0.5%");
        } else if (le(feature.pctAbove30dLow(), 0.01D)) {
            score += 22D;
            reasons.add("价格距离30日低点不足1%");
        } else if (le(feature.pctAbove30dLow(), 0.02D)) {
            score += 12D;
            reasons.add("价格距离30日低点不足2%");
        }

        if (le(feature.zScore30d(), -1.5D)) {
            score += 20D;
            reasons.add("当前价格明显低于近30日常态价格");
        } else if (le(feature.zScore30d(), -0.8D)) {
            score += 12D;
            reasons.add("当前价格低于近30日常态价格");
        }

        if (le(feature.zScore7d(), -1.5D)) {
            score += 15D;
            reasons.add("当前价格明显低于近7日常态价格");
        } else if (le(feature.zScore7d(), -0.8D)) {
            score += 8D;
            reasons.add("当前价格低于近7日常态价格");
        }

        if (feature.rsi() <= 35D) {
            score += 8D;
            reasons.add("RSI偏低，短线卖压释放");
        }

        score = applyLowBuyRiskPenalty(feature, score, reasons);

        // 门槛叠加只作用于本通道(与SWING_REVERSAL_BUY固定50分的设计冻结一致):
        // 窗口不足+10(现状沿用)、月度成熟度早期+10、月度风险HIGH+10,可叠加,只改门槛不改分数
        int effectiveThreshold = feature.personality().getBuyThreshold()
                + (feature.windowInsufficient() ? (int) WINDOW_INSUFFICIENT_PENALTY : 0)
                + (maturityEarly ? (int) MONTHLY_MATURITY_PENALTY : 0)
                + (riskHigh ? (int) MONTHLY_RISK_PENALTY : 0);

        if (score < effectiveThreshold) {
            return holdSignal(StockStrategyTypeEnum.SWING_LOW_BUY, score, reasons);
        }

        return new StrategySignal(StockTradeActionEnum.BUY, StockStrategyTypeEnum.SWING_LOW_BUY, score, reasons);
    }

    /**
     * 构建摇摆反弹购买信号
     */
    private StrategySignal buildSwingReversalBuySignal(StockFeature feature) {
        List<String> reasons = new ArrayList<>();
        double score = 0D;

        boolean lowArea = le(feature.pctAbove30dLow(), 0.02D) && le(feature.zScore30d(), 0.2D);
        boolean reboundConfirmed = gt(feature.return1d(), 0D) && gt(feature.zScore1d(), 0.8D);

        if (lowArea) {
            score += 25D;
            reasons.add("价格仍处于30日低位区域");
        }

        if (reboundConfirmed) {
            score += 25D;
            reasons.add("低位出现短线反弹确认");
        }

        if (le(feature.zScore7d(), 0.5D)) {
            score += 8D;
            reasons.add("7日位置未明显过热");
        }

        if (feature.personality() == StockPersonalityEnum.DECLINER) {
            score += 10D;
            reasons.add("阴跌型Stock已出现确认信号，允许小仓位参与");
        } else if (feature.personality() != StockPersonalityEnum.STRONG) {
            score += 10D;
            reasons.add("非强势股出现低位反弹确认信号");
        }

        if (feature.fallingKnifeRisk()) {
            score -= 25D;
            reasons.add("仍有持续创新低风险，反弹确认不足");
        }

        if (score < BUY_SCORE_THRESHOLD) {
            return holdSignal(StockStrategyTypeEnum.SWING_REVERSAL_BUY, score, reasons);
        }

        return new StrategySignal(StockTradeActionEnum.BUY, StockStrategyTypeEnum.SWING_REVERSAL_BUY, score, reasons);
    }

    /**
     * 构建摇摆止盈卖出信号
     */
    private StrategySignal buildSwingTakeProfitSellSignal(StockFeature feature) {
        List<String> reasons = new ArrayList<>();
        double score = 0D;

        if (ge(feature.pctBelow30dHigh(), -0.002D)) {
            score += 30D;
            reasons.add("价格距离30日高点不足0.2%");
        } else if (ge(feature.pctBelow30dHigh(), -0.005D)) {
            score += 22D;
            reasons.add("价格距离30日高点不足0.5%");
        } else if (ge(feature.pctBelow30dHigh(), -0.01D)) {
            score += 12D;
            reasons.add("价格距离30日高点不足1%");
        }

        if (ge(feature.zScore30d(), 2D)) {
            score += 25D;
            reasons.add("当前价格明显高于近30日常态价格");
        }

        if (ge(feature.zScore7d(), 2D)) {
            score += 20D;
            reasons.add("当前价格明显高于近7日常态价格");
        }

        if (ge(feature.return14d(), 0.015D)) {
            score += 10D;
            reasons.add("近14日涨幅超过1.5%，具备波段止盈条件");
        }

        if (le(feature.pctAbove30dLow(), 0.005D) || le(feature.zScore30d(), -1D)) {
            score = Math.min(score, 20D);
            reasons.add("价格仍处于低位，禁止按高位止盈卖出");
        }

        if (score < TAKE_PROFIT_SELL_SCORE_THRESHOLD) {
            return holdSignal(StockStrategyTypeEnum.SWING_TAKE_PROFIT_SELL, score, reasons);
        }

        return new StrategySignal(StockTradeActionEnum.SELL, StockStrategyTypeEnum.SWING_TAKE_PROFIT_SELL, score, reasons);
    }

    /**
     * 构建摇摆反弹卖出信号
     */
    private StrategySignal buildSwingReboundSellSignal(StockFeature feature) {
        List<String> reasons = new ArrayList<>();
        double score = 0D;

        if (ge(feature.zScore1d(), 1.8D)) {
            score += 22D;
            reasons.add("当前价格高于近1日常态价格，短线反弹较强");
        }

        if (ge(feature.zScore7d(), 1.8D)) {
            score += 22D;
            reasons.add("当前价格高于近7日常态价格，存在回落风险");
        }

        if (ge(feature.return7d(), 0.01D)) {
            score += 10D;
            reasons.add("近7日涨幅超过1%，可考虑阶段性落袋");
        }

        if (le(feature.pctAbove30dLow(), 0.005D) || le(feature.zScore30d(), -1D)) {
            score = Math.min(score, 20D);
            reasons.add("价格仍处于低位，疑似换仓或止损，不作为普通卖出信号");
        }

        if (score < REBOUND_SELL_SCORE_THRESHOLD) {
            return holdSignal(StockStrategyTypeEnum.SWING_REBOUND_SELL, score, reasons);
        }

        return new StrategySignal(StockTradeActionEnum.SELL, StockStrategyTypeEnum.SWING_REBOUND_SELL, score, reasons);
    }

    /**
     * 构建短线快速获利卖出信号
     */
    private StrategySignal buildSwingQuickProfitSellSignal(StockFeature feature) {
        List<String> reasons = new ArrayList<>();
        double score = 0D;

        if (ge(feature.return7d(), 0.01D)) {
            score += 35D;
            reasons.add("近7日涨幅超1%, 短线获利了结");
        } else if (ge(feature.return7d(), 0.008D)) {
            score += 25D;
            reasons.add("近7日涨幅超0.8%, 可考虑止盈");
        }

        if (gt(feature.zScore7d(), 0D)) {
            score += 15D;
            reasons.add("价格站上7日均线");
        }

        if (gt(feature.return1d(), 0D)) {
            score += 10D;
            reasons.add("短线仍在上涨");
        }

        if (score < QUICK_PROFIT_SELL_SCORE_THRESHOLD) {
            return holdSignal(StockStrategyTypeEnum.SWING_QUICK_PROFIT_SELL, score, reasons);
        }

        return new StrategySignal(StockTradeActionEnum.SELL, StockStrategyTypeEnum.SWING_QUICK_PROFIT_SELL, score, reasons);
    }

    /**
     * 低点买入风险
     */
    private double applyLowBuyRiskPenalty(StockFeature feature, double sourceScore, List<String> reasons) {
        double score = sourceScore;

        if (feature.fallingKnifeRisk()) {
            score += feature.personality().getDeclinePenalty();
            reasons.add("接近30日低点但仍在走弱，存在接" + feature.personality().getDescription() + "风险");
        }

        if (feature.persistentDecline()) {
            score -= 30D;
            reasons.add("阴跌持续中：近14日跌幅超0.5%且接近历史低点，不建议裸买入");
        }

        if (feature.personality() == StockPersonalityEnum.DECLINER && le(feature.return1d(), 0D)) {
            score -= 22D;
            reasons.add("阴跌型Stock尚未出现1日反弹确认，容易长时间套牢");
        } else if (feature.personality() == StockPersonalityEnum.WEAK && le(feature.return1d(), 0D)) {
            score -= 14D;
            reasons.add("弱势Stock尚未出现反弹确认，建议等待");
        }

        if (le(feature.zScore30d(), -3D) && lt(feature.return1d(), 0D)) {
            score -= 10D;
            reasons.add("价格已显著偏离近30日常态且短线仍在下跌，暂不追低");
        }

        return score;
    }

    /**
     * 是否飞刀(窗口指标为null时一律视为无飞刀风险,不把"未知"误判为事实)
     */
    private boolean isFallingKnife(BigDecimal pctAbove30dLow, BigDecimal return1d, BigDecimal zScore30d,
                                   StockPersonalityEnum personality) {
        double zThreshold = personality != null ? personality.getFallingKnifeZThreshold() : -2.5D;
        return le(pctAbove30dLow, 0.001D) && lt(return1d, 0D) && le(zScore30d, zThreshold);
    }

    /**
     * 选择最佳信号
     */
    private StrategySignal selectBestSignal(List<StrategySignal> signals) {
        return signals.stream()
                .max(Comparator.comparingDouble(StrategySignal::score))
                .orElse(new StrategySignal(StockTradeActionEnum.HOLD, StockStrategyTypeEnum.NONE, 0D, List.of("无有效信号")));
    }

    /**
     * 窄幅震荡股 Z-Score 打折 — 价格带<4%的股票 Z-Score 虚高，需要缩小;原始值为null时保持null
     */
    private Double adjustZScoreForNarrowBand(BigDecimal rawZScore, StockPersonalityEnum personality) {
        if (rawZScore == null) {
            return null;
        }
        if (personality == StockPersonalityEnum.NARROW) {
            return rawZScore.doubleValue() * NARROW_BAND_Z_DISCOUNT;
        }
        return rawZScore.doubleValue();
    }

    /**
     * 检测持续性阴跌(窗口指标为null时视为不命中)
     */
    private boolean isPersistentDecline(StockStrategyFeaturePoint point, StockPersonalityEnum personality) {
        if (personality != StockPersonalityEnum.DECLINER && personality != StockPersonalityEnum.WEAK) {
            return false;
        }
        return le(point.zScore30d(), -1.5D)
                && lt(point.return14d(), PERSISTENT_DECLINE_14D_THRESHOLD)
                && le(point.pctAbove30dLow(), 0.005D);
    }

    /**
     * 观望信号
     */
    private StrategySignal holdSignal(StockStrategyTypeEnum strategyType, double score, List<String> reasons) {
        List<String> resultReasons = new ArrayList<>(reasons);
        if (resultReasons.isEmpty()) {
            resultReasons.add("信号强度不足，建议观望");
        } else {
            resultReasons.add("综合分数不足，暂不触发交易");
        }
        return new StrategySignal(StockTradeActionEnum.HOLD, strategyType, score, resultReasons);
    }

    /**
     * 构建"整股不推荐"观望信号(月度风格缺失/特征陈旧/成熟度M0)
     */
    private StrategySignal holdAllSignal(String reason) {
        return new StrategySignal(StockTradeActionEnum.HOLD, StockStrategyTypeEnum.NONE, 0D, List.of(reason));
    }

    /**
     * 由特征点与月度档位构建内部特征(窗口指标保持可空Double,null由评分层null安全分流)
     */
    private StockFeature newFeature(StockStrategyFeaturePoint point,
                                    StockPersonalityEnum personality,
                                    boolean fallingKnife,
                                    boolean persistentDecline,
                                    boolean windowInsufficient) {
        return new StockFeature(
                point.stocksId(),
                point.stocksShortname(),
                point.basePrice(),
                point.basePrice().doubleValue(),
                toDouble(point.ma1d()),
                toDouble(point.ma7d()),
                toDouble(point.ma30d()),
                toDouble(point.zScore1d()),
                toDouble(point.zScore7d()),
                adjustZScoreForNarrowBand(point.zScore30d(), personality),
                point.rsi().doubleValue(),
                toDouble(point.return1d()),
                toDouble(point.return7d()),
                toDouble(point.return14d()),
                toDouble(point.pctAbove30dLow()),
                toDouble(point.pctBelow30dHigh()),
                personality,
                fallingKnife,
                persistentDecline,
                windowInsufficient);
    }

    /**
     * 构建建议(monthlyReasons为月度/就绪装饰文案,追加在信号理由之后)
     */
    private StockTradeAdvice toAdvice(StockFeature feature, StrategySignal signal,
                                      LocalDateTime analysisTime, List<String> monthlyReasons) {
        List<String> reasons = new ArrayList<>(signal.reasons());
        reasons.addAll(monthlyReasons);
        return new StockTradeAdvice(
                feature.stocksId(),
                feature.stocksShortname(),
                signal.action(),
                signal.strategyType(),
                analysisTime,
                feature.basePrice(),
                toBigDecimal(signal.score()),
                toBigDecimal(feature.ma1d()),
                toBigDecimal(feature.ma7d()),
                toBigDecimal(feature.ma30d()),
                toBigDecimal(feature.zScore1d()),
                toBigDecimal(feature.zScore7d()),
                toBigDecimal(feature.zScore30d()),
                toBigDecimal(feature.rsi()),
                toBigDecimal(feature.return1d()),
                toBigDecimal(feature.return7d()),
                toBigDecimal(feature.return14d()),
                toBigDecimal(feature.pctAbove30dLow()),
                toBigDecimal(feature.pctBelow30dHigh()),
                feature.personality() == StockPersonalityEnum.DECLINER || feature.personality() == StockPersonalityEnum.WEAK,
                feature.fallingKnifeRisk(),
                reasons);
    }

    /**
     * BigDecimal转可空Double(null保持null,窗口指标不可计算时不填充伪造值)
     */
    private Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    /**
     * 转换为BigDecimal(可空输入原样透传null)
     */
    private BigDecimal toBigDecimal(Double value) {
        if (value == null) {
            return null;
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 转换为BigDecimal(非空分数字段)
     */
    private BigDecimal toBigDecimal(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * null安全判定: value <= threshold(15m窗口指标在不可计算时为null,该分支一律不命中,
     * 不得把"未知"当常态参与比较)
     */
    private static boolean le(BigDecimal value, double threshold) {
        return value != null && value.doubleValue() <= threshold;
    }

    /**
     * null安全判定: value < threshold
     */
    private static boolean lt(BigDecimal value, double threshold) {
        return value != null && value.doubleValue() < threshold;
    }

    /**
     * null安全判定: value > threshold
     */
    private static boolean gt(BigDecimal value, double threshold) {
        return value != null && value.doubleValue() > threshold;
    }

    /**
     * null安全判定: value >= threshold
     */
    private static boolean ge(BigDecimal value, double threshold) {
        return value != null && value.doubleValue() >= threshold;
    }

    /**
     * null安全判定(Double口径,用于折扣后的Z30)
     */
    private static boolean le(Double value, double threshold) {
        return value != null && value <= threshold;
    }

    /**
     * null安全判定(Double口径): value < threshold
     */
    private static boolean lt(Double value, double threshold) {
        return value != null && value < threshold;
    }

    /**
     * null安全判定(Double口径): value > threshold
     */
    private static boolean gt(Double value, double threshold) {
        return value != null && value > threshold;
    }

    /**
     * null安全判定(Double口径): value >= threshold
     */
    private static boolean ge(Double value, double threshold) {
        return value != null && value >= threshold;
    }

    /**
     * 股票特征(窗口指标为可空Double: 15m特征表在窗口不足或不可计算时为null)
     */
    private record StockFeature(
            Integer stocksId,
            String stocksShortname,
            BigDecimal basePrice,
            double basePriceDouble,
            Double ma1d,
            Double ma7d,
            Double ma30d,
            Double zScore1d,
            Double zScore7d,
            Double zScore30d,
            double rsi,
            Double return1d,
            Double return7d,
            Double return14d,
            Double pctAbove30dLow,
            Double pctBelow30dHigh,
            StockPersonalityEnum personality,
            boolean fallingKnifeRisk,
            boolean persistentDecline,
            boolean windowInsufficient) {
    }

    /**
     * 股票信号
     */
    private record StrategySignal(
            StockTradeActionEnum action,
            StockStrategyTypeEnum strategyType,
            double score,
            List<String> reasons) {
    }
}
