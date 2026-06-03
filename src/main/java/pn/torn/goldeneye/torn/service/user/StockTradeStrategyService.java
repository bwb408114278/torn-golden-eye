package pn.torn.goldeneye.torn.service.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.stocks.StockStrategyTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.StockTradeActionEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStockStrategyFeatureDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockStrategyFeaturePoint;
import pn.torn.goldeneye.torn.model.torn.stocks.trade.StockTradeAdvice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 股票交易策略逻辑层
 *
 * @author Bai
 * @version 1.1.6
 * @since 2026.06.02
 */
@Service
@RequiredArgsConstructor
public class StockTradeStrategyService {
    private final TornStockStrategyFeatureDAO featureDao;

    private static final int SCALE = 6;
    private static final double BUY_SCORE_THRESHOLD = 50D;
    private static final double TAKE_PROFIT_SELL_SCORE_THRESHOLD = 55D;
    private static final double REBOUND_SELL_SCORE_THRESHOLD = 45D;

    private static final Set<String> SLOW_REBOUND_STOCKS = Set.of("HRG", "LSC", "IOU", "TCI", "TCC");

    /**
     * 分析股票
     */
    public List<StockTradeAdvice> analyze(LocalDateTime analysisTime, boolean debug) {
        Objects.requireNonNull(analysisTime, "analysisTime must not be null");

        List<StockStrategyFeaturePoint> featurePoints = featureDao.selectLatestFeatures(analysisTime);
        if (CollectionUtils.isEmpty(featurePoints)) {
            return List.of();
        }

        List<StockTradeAdvice> advices = featurePoints.stream()
                .map(point -> analyzeSingleFeature(point, analysisTime))
                .toList();
        if (debug) {
            return advices.stream()
                    .sorted(Comparator.comparing(StockTradeAdvice::stocksShortname))
                    .toList();
        }

        return advices.stream()
                .filter(advice -> advice.action() != StockTradeActionEnum.HOLD)
                .sorted(Comparator.comparing(StockTradeAdvice::score).reversed())
                .toList();
    }

    /**
     * 分析单个特征
     */
    private StockTradeAdvice analyzeSingleFeature(StockStrategyFeaturePoint point, LocalDateTime analysisTime) {
        StockFeature feature = new StockFeature(
                point.stocksId(),
                point.stocksShortname(),
                point.basePrice(),
                point.basePrice().doubleValue(),
                point.ma1d().doubleValue(),
                point.ma7d().doubleValue(),
                point.ma30d().doubleValue(),
                point.zScore1d().doubleValue(),
                point.zScore7d().doubleValue(),
                point.zScore30d().doubleValue(),
                point.rsi().doubleValue(),
                point.return1d().doubleValue(),
                point.return7d().doubleValue(),
                point.return14d().doubleValue(),
                point.pctAbove30dLow().doubleValue(),
                point.pctBelow30dHigh().doubleValue(),
                SLOW_REBOUND_STOCKS.contains(point.stocksShortname()),
                isFallingKnife(point.pctAbove30dLow().doubleValue(),
                        point.return1d().doubleValue(),
                        point.zScore30d().doubleValue()));
        StrategySignal bestSignal = selectBestSignal(List.of(
                buildSwingLowBuySignal(feature),
                buildSwingReversalBuySignal(feature),
                buildSwingTakeProfitSellSignal(feature),
                buildSwingReboundSellSignal(feature)));

        return toAdvice(feature, bestSignal, analysisTime);
    }

    /**
     * 构建摇摆低点购买信号
     */
    private StrategySignal buildSwingLowBuySignal(StockFeature feature) {
        List<String> reasons = new ArrayList<>();
        double score = 0D;

        if (feature.pctAbove30dLow() <= 0.005D) {
            score += 30D;
            reasons.add("价格距离30日低点不足0.5%");
        } else if (feature.pctAbove30dLow() <= 0.01D) {
            score += 22D;
            reasons.add("价格距离30日低点不足1%");
        } else if (feature.pctAbove30dLow() <= 0.02D) {
            score += 12D;
            reasons.add("价格距离30日低点不足2%");
        }

        if (feature.zScore30d() <= -1.5D) {
            score += 20D;
            reasons.add("当前价格明显低于近30日常态价格");
        } else if (feature.zScore30d() <= -0.8D) {
            score += 12D;
            reasons.add("当前价格低于近30日常态价格");
        }

        if (feature.zScore7d() <= -1.5D) {
            score += 15D;
            reasons.add("当前价格明显低于近7日常态价格");
        } else if (feature.zScore7d() <= -0.8D) {
            score += 8D;
            reasons.add("当前价格低于近7日常态价格");
        }

        if (feature.rsi() <= 35D) {
            score += 8D;
            reasons.add("RSI偏低，短线卖压释放");
        }

        score = applyLowBuyRiskPenalty(feature, score, reasons);

        if (score < BUY_SCORE_THRESHOLD) {
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

        boolean lowArea = feature.pctAbove30dLow() <= 0.02D && feature.zScore30d() <= 0.2D;
        boolean reboundConfirmed = feature.return1d() > 0D && feature.zScore1d() > 0.8D;

        if (lowArea) {
            score += 25D;
            reasons.add("价格仍处于30日低位区域");
        }

        if (reboundConfirmed) {
            score += 25D;
            reasons.add("低位出现短线反弹确认");
        }

        if (feature.zScore7d() <= 0.5D) {
            score += 8D;
            reasons.add("7日位置未明显过热");
        }

        if (feature.slowReboundStock()) {
            score += 10D;
            reasons.add("慢反弹股票已出现确认信号，允许小仓位参与");
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

        if (feature.pctBelow30dHigh() >= -0.002D) {
            score += 30D;
            reasons.add("价格距离30日高点不足0.2%");
        } else if (feature.pctBelow30dHigh() >= -0.005D) {
            score += 22D;
            reasons.add("价格距离30日高点不足0.5%");
        } else if (feature.pctBelow30dHigh() >= -0.01D) {
            score += 12D;
            reasons.add("价格距离30日高点不足1%");
        }

        if (feature.zScore30d() >= 2D) {
            score += 25D;
            reasons.add("当前价格明显高于近30日常态价格");
        }

        if (feature.zScore7d() >= 2D) {
            score += 20D;
            reasons.add("当前价格明显高于近7日常态价格");
        }

        if (feature.return14d() >= 0.015D) {
            score += 10D;
            reasons.add("近14日涨幅超过1.5%，具备波段止盈条件");
        }

        if (feature.pctAbove30dLow() <= 0.005D || feature.zScore30d() <= -1D) {
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

        if (feature.zScore1d() >= 1.8D) {
            score += 22D;
            reasons.add("当前价格高于近1日常态价格，短线反弹较强");
        }

        if (feature.zScore7d() >= 1.8D) {
            score += 22D;
            reasons.add("当前价格高于近7日常态价格，存在回落风险");
        }

        if (feature.return7d() >= 0.01D) {
            score += 10D;
            reasons.add("近7日涨幅超过1%，可考虑阶段性落袋");
        }

        if (feature.pctAbove30dLow() <= 0.005D || feature.zScore30d() <= -1D) {
            score = Math.min(score, 20D);
            reasons.add("价格仍处于低位，疑似换仓或止损，不作为普通卖出信号");
        }

        if (score < REBOUND_SELL_SCORE_THRESHOLD) {
            return holdSignal(StockStrategyTypeEnum.SWING_REBOUND_SELL, score, reasons);
        }

        return new StrategySignal(StockTradeActionEnum.SELL, StockStrategyTypeEnum.SWING_REBOUND_SELL, score, reasons);
    }

    /**
     * 低点买入风险
     */
    private double applyLowBuyRiskPenalty(StockFeature feature, double sourceScore, List<String> reasons) {
        double score = sourceScore;

        if (feature.fallingKnifeRisk()) {
            score -= 25D;
            reasons.add("接近30日低点但仍在走弱，存在接飞刀风险");
        }

        if (feature.slowReboundStock() && feature.return1d() <= 0D) {
            score -= 18D;
            reasons.add("慢反弹股票尚未出现1日反弹确认，容易长时间套牢");
        }

        if (feature.zScore30d() <= -3D && feature.return1d() < 0D) {
            score -= 10D;
            reasons.add("价格已显著偏离近30日常态且短线仍在下跌，暂不追低");
        }

        return score;
    }

    /**
     * 是否飞刀
     */
    private boolean isFallingKnife(double pctAbove30dLow, double return1d, double zScore30d) {
        return pctAbove30dLow <= 0.001D && return1d < 0D && zScore30d <= -2.5D;
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
     * 构建建议
     */
    private StockTradeAdvice toAdvice(StockFeature feature, StrategySignal signal, LocalDateTime analysisTime) {
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
                feature.slowReboundStock(),
                feature.fallingKnifeRisk(),
                signal.reasons());
    }

    /**
     * 转换为BigDecimal
     */
    private BigDecimal toBigDecimal(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 股票特征
     */
    private record StockFeature(
            int stocksId,
            String stocksShortname,
            BigDecimal basePrice,
            double latestPrice,
            double ma1d,
            double ma7d,
            double ma30d,
            double zScore1d,
            double zScore7d,
            double zScore30d,
            double rsi,
            double return1d,
            double return7d,
            double return14d,
            double pctAbove30dLow,
            double pctBelow30dHigh,
            boolean slowReboundStock,
            boolean fallingKnifeRisk) {
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