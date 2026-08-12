package pn.torn.goldeneye.torn.service.stocks.replay;

import pn.torn.goldeneye.torn.service.stocks.replay.model.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 回放轨道摘要构建器。
 *
 * <p>由交易、拒绝/观察与净值点计算确定性摘要: 区间/年化收益(短历史基准)、回撤、占用率、
 * 中位持有、消息条数与原因分布。全部计算无墙钟依赖。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
final class StockReplaySummaryBuilder {

    /**
     * 年化天数。
     */
    private static final double ANNUALIZE_DAYS = 365.25;
    /**
     * 统计精度。
     */
    private static final int STAT_SCALE = 10;

    private final Map<String, List<StockReplayTrade>> tradesByTrack;
    private final Map<String, List<StockReplayRejection>> rejectionsByTrack;
    private final Map<String, List<StockReplayEquityPoint>> equityByTrack;
    private final StockReplayTrackEnum track;
    private final StockReplayMetrics metrics;

    /**
     * 构造摘要构建器。
     *
     * @param tradesByTrack     交易输出映射
     * @param rejectionsByTrack 拒绝/观察输出映射
     * @param equityByTrack     净值点输出映射
     * @param track             正式轨道
     * @param metrics           指标累加器
     */
    StockReplaySummaryBuilder(Map<String, List<StockReplayTrade>> tradesByTrack,
                              Map<String, List<StockReplayRejection>> rejectionsByTrack,
                              Map<String, List<StockReplayEquityPoint>> equityByTrack,
                              StockReplayTrackEnum track,
                              StockReplayMetrics metrics) {
        this.tradesByTrack = tradesByTrack;
        this.rejectionsByTrack = rejectionsByTrack;
        this.equityByTrack = equityByTrack;
        this.track = track;
        this.metrics = metrics;
    }

    /**
     * 构建本引擎产出各轨道的摘要。
     *
     * @param windowDays 回放窗口自然日数
     * @return 轨道编码 → 轨道摘要
     */
    Map<String, StockReplaySummary.TrackSummary> build(long windowDays) {
        Map<String, StockReplaySummary.TrackSummary> summaries = new LinkedHashMap<>();
        for (Map.Entry<String, List<StockReplayTrade>> entry : tradesByTrack.entrySet()) {
            summaries.put(entry.getKey(),
                    buildTradeTrackSummary(entry.getKey(), entry.getValue(), windowDays));
        }
        for (Map.Entry<String, List<StockReplayRejection>> entry : rejectionsByTrack.entrySet()) {
            summaries.put(entry.getKey(), buildRejectionTrackSummary(entry.getKey(), entry.getValue()));
        }
        return summaries;
    }

    private StockReplaySummary.TrackSummary buildTradeTrackSummary(String trackCode,
                                                                   List<StockReplayTrade> trades,
                                                                   long windowDays) {
        StockReplayTrackEnum trackEnum = StockReplayTrackEnum.valueOf(trackCode);
        long buys = trades.stream().filter(t -> "BUY".equals(t.side())).count();
        long sells = trades.stream().filter(t -> "SELL".equals(t.side())).count();
        List<StockReplayEquityPoint> points = equityByTrack.getOrDefault(trackCode, List.of());
        List<BigDecimal> equityValues = points.stream()
                .map(StockReplayEquityPoint::equity)
                .filter(Objects::nonNull)
                .toList();
        BigDecimal intervalReturn = null;
        BigDecimal annualized = null;
        BigDecimal finalEquityValue = null;
        if (trackEnum.isFormal() && !equityValues.isEmpty()) {
            finalEquityValue = equityValues.getLast();
            intervalReturn = finalEquityValue.divide(initialCash(trackEnum), STAT_SCALE, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE);
            annualized = annualize(intervalReturn, windowDays);
        }
        BigDecimal drawdown = trackCode.equals(track.getCode()) ? metrics.maxDrawdown() : BigDecimal.ZERO;
        BigDecimal medianHoldHours = medianHoldHours(trades);
        long totalMessages = trackEnum.isFormal() ? metrics.messageCount() : 0;
        BigDecimal messagesPerDay = windowDays <= 0 ? null
                : BigDecimal.valueOf(totalMessages)
                .divide(BigDecimal.valueOf(windowDays), STAT_SCALE, RoundingMode.HALF_UP);
        return new StockReplaySummary.TrackSummary(
                trackCode, trackEnum.getDisplayName(),
                trackEnum.getSlotCount(), trackEnum.getInitialCashPerSlot(),
                trades.size(), buys, sells,
                intervalReturn, annualized, drawdown, metrics.averageUtilization(), medianHoldHours,
                totalMessages, messagesPerDay, 0, StockReplaySummary.emptyReasonMap(),
                0, StockReplaySummary.emptyReasonMap(),
                finalEquityValue, points.size(), null);
    }

    private StockReplaySummary.TrackSummary buildRejectionTrackSummary(String trackCode,
                                                                       List<StockReplayRejection> rejections) {
        SortedMap<String, Integer> reasons = StockReplaySummary.emptyReasonMap();
        SortedMap<String, Integer> results = StockReplaySummary.emptyReasonMap();
        long observed = 0;
        for (StockReplayRejection rejection : rejections) {
            StockReplaySummary.mergeReason(reasons, rejection.rejectReason());
            StockReplaySummary.mergeReason(results, rejection.observationResult());
            if ("OBSERVATION_COMPLETED".equals(rejection.observationResult())) {
                observed++;
            }
        }
        StockReplayTrackEnum trackEnum = StockReplayTrackEnum.valueOf(trackCode);
        return new StockReplaySummary.TrackSummary(
                trackCode, trackEnum.getDisplayName(),
                0, null, 0, 0, 0,
                null, null, null, null, null, 0, null,
                rejections.size(), reasons, observed, results,
                null, 0, null);
    }

    private static BigDecimal initialCash(StockReplayTrackEnum trackEnum) {
        return trackEnum.getInitialCashPerSlot()
                .multiply(BigDecimal.valueOf(trackEnum.getSlotCount()));
    }

    private static BigDecimal annualize(BigDecimal intervalReturn, long windowDays) {
        if (intervalReturn == null || windowDays <= 0) {
            return null;
        }
        double base = intervalReturn.add(BigDecimal.ONE).doubleValue();
        if (base <= 0) {
            return null;
        }
        double power = ANNUALIZE_DAYS / windowDays;
        double result = Math.pow(base, power) - 1;
        if (Double.isFinite(result)) {
            return BigDecimal.valueOf(result).setScale(STAT_SCALE, RoundingMode.HALF_UP);
        }
        return null;
    }

    private static BigDecimal medianHoldHours(List<StockReplayTrade> trades) {
        List<BigDecimal> hours = trades.stream()
                .filter(t -> "SELL".equals(t.side()) && t.holdHours() != null)
                .map(StockReplayTrade::holdHours)
                .sorted()
                .toList();
        if (hours.isEmpty()) {
            return null;
        }
        int mid = hours.size() / 2;
        return hours.size() % 2 == 0
                ? hours.get(mid - 1).add(hours.get(mid))
                .divide(BigDecimal.valueOf(2), STAT_SCALE, RoundingMode.HALF_UP)
                : hours.get(mid);
    }
}
