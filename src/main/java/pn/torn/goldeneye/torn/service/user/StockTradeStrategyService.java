package pn.torn.goldeneye.torn.service.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.stocks.StockPersonalityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.StockStrategyTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.StockTradeActionEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStockStrategyFeatureDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockStrategyFeaturePoint;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.model.torn.stocks.trade.StockTradeAdvice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 股票交易策略逻辑层
 *
 * @author Bai
 * @version 1.2.8
 * @since 2026.06.02
 */
@Service
@RequiredArgsConstructor
public class StockTradeStrategyService {
    private final TornStockStrategyFeatureDAO featureDao;
    private final SysSettingManager settingManager;

    private static final int SCALE = 6;
    private static final double BUY_SCORE_THRESHOLD = 50D;
    private static final double TAKE_PROFIT_SELL_SCORE_THRESHOLD = 55D;
    private static final double REBOUND_SELL_SCORE_THRESHOLD = 45D;
    // 阴跌持续检测：两周跌幅阈值
    private static final double PERSISTENT_DECLINE_14D_THRESHOLD = -0.005D;
    // 月初数据不足时额外提高的买入阈值
    private static final double BEGIN_MONTH_BUY_PENALTY = 10D;
    // 窄幅股 Z-Score 折扣系数
    private static final double NARROW_BAND_Z_DISCOUNT = 0.6;

    /**
     * 分析股票
     */
    public List<StockTradeAdvice> analyze(LocalDateTime analysisTime, boolean debug) {
        Map<String, StockPersonalityEnum> personalities = settingManager.getStockPersonalities();
        boolean isBeginOfMonth = isBeginOfMonth(analysisTime);

        List<StockStrategyFeaturePoint> featurePoints = featureDao.selectLatestFeatures(analysisTime);
        if (CollectionUtils.isEmpty(featurePoints)) {
            return List.of();
        }

        List<StockTradeAdvice> advices = featurePoints.stream()
                .map(point -> analyzeSingleFeature(point, analysisTime, personalities, isBeginOfMonth))
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
    private StockTradeAdvice analyzeSingleFeature(StockStrategyFeaturePoint point, LocalDateTime analysisTime,
                                                  Map<String, StockPersonalityEnum> personalities,
                                                  boolean isBeginOfMonth) {
        StockPersonalityEnum personality = resolvePersonality(personalities, point.stocksShortname());
        double adjustedZ30d = adjustZScoreForNarrowBand(point.zScore30d().doubleValue(), personality);
        boolean fallingKnife = isFallingKnife(point.pctAbove30dLow().doubleValue(),
                point.return1d().doubleValue(), point.zScore30d().doubleValue(), personality);
        boolean persistentDecline = isPersistentDecline(point, personality);

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
                adjustedZ30d,
                point.rsi().doubleValue(),
                point.return1d().doubleValue(),
                point.return7d().doubleValue(),
                point.return14d().doubleValue(),
                point.pctAbove30dLow().doubleValue(),
                point.pctBelow30dHigh().doubleValue(),
                personality,
                fallingKnife,
                persistentDecline,
                isBeginOfMonth);
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

        int effectiveThreshold = feature.beginOfMonth()
                ? feature.personality().getBuyThreshold() + (int) BEGIN_MONTH_BUY_PENALTY
                : feature.personality().getBuyThreshold();

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

        if (feature.personality() == StockPersonalityEnum.DECLINER) {
            score += 10D;
            reasons.add("阴跌型股票已出现确认信号，允许小仓位参与");
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
            score += feature.personality().getDeclinePenalty();
            reasons.add("接近30日低点但仍在走弱，存在接" + feature.personality().getDescription() + "风险");
        }

        if (feature.persistentDecline()) {
            score -= 30D;
            reasons.add("阴跌持续中：近14日跌幅超0.5%且接近历史低点，不建议裸买入");
        }

        if (feature.personality() == StockPersonalityEnum.DECLINER && feature.return1d() <= 0D) {
            score -= 22D;
            reasons.add("阴跌型股票尚未出现1日反弹确认，容易长时间套牢");
        } else if (feature.personality() == StockPersonalityEnum.WEAK && feature.return1d() <= 0D) {
            score -= 14D;
            reasons.add("弱势股票尚未出现反弹确认，建议等待");
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
    private boolean isFallingKnife(double pctAbove30dLow, double return1d, double zScore30d,
                                   StockPersonalityEnum personality) {
        double zThreshold = personality != null ? personality.getFallingKnifeZThreshold() : -2.5D;
        return pctAbove30dLow <= 0.001D && return1d < 0D && zScore30d <= zThreshold;
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
     * 解析股票的个性分类
     */
    private StockPersonalityEnum resolvePersonality(Map<String, StockPersonalityEnum> personalities, String shortname) {
        if (personalities != null && personalities.containsKey(shortname.toUpperCase())) {
            return personalities.get(shortname.toUpperCase());
        }
        return StockPersonalityEnum.STEADY; // 默认按稳步上行处理
    }

    /**
     * 窄幅震荡股 Z-Score 打折 — 价格带<4%的股票 Z-Score 虚高，需要缩小
     */
    private double adjustZScoreForNarrowBand(double rawZScore, StockPersonalityEnum personality) {
        if (personality == StockPersonalityEnum.NARROW) {
            return rawZScore * NARROW_BAND_Z_DISCOUNT;
        }
        return rawZScore;
    }

    /**
     * 是否月初数据不足 — 前3天 Z1D/Z7D/Z30D 同值表明窗口数据不足
     */
    private boolean isBeginOfMonth(LocalDateTime analysisTime) {
        return analysisTime.getDayOfMonth() <= 3;
    }

    /**
     * 检测持续性阴跌 — 非一次性暴跌而是温水煮青蛙式下跌
     */
    private boolean isPersistentDecline(StockStrategyFeaturePoint point, StockPersonalityEnum personality) {
        if (personality != StockPersonalityEnum.DECLINER && personality != StockPersonalityEnum.WEAK) {
            return false;
        }
        return point.zScore30d().doubleValue() <= -1.5D
                && point.return14d().doubleValue() < PERSISTENT_DECLINE_14D_THRESHOLD
                && point.pctAbove30dLow().doubleValue() <= 0.005D;
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
                feature.personality() == StockPersonalityEnum.DECLINER || feature.personality() == StockPersonalityEnum.WEAK,
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
            Integer stocksId,
            String stocksShortname,
            BigDecimal basePrice,
            double basePriceDouble,
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
            StockPersonalityEnum personality,
            boolean fallingKnifeRisk,
            boolean persistentDecline,
            boolean beginOfMonth) {
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