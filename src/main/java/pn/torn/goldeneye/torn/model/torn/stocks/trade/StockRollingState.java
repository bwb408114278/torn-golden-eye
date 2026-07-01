package pn.torn.goldeneye.torn.model.torn.stocks.trade;

import pn.torn.goldeneye.repository.model.torn.stocks.StockPricePoint;
import pn.torn.goldeneye.repository.model.torn.stocks.StockStrategyFeatureUpsert;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 股票回溯状态
 *
 * @author Bai
 * @version 1.2.8
 * @since 2026.06.02
 */
public class StockRollingState {
    private static final int SCALE = 6;
    private static final int CALC_SCALE = 12;
    private static final BigDecimal EPSILON = BigDecimal.valueOf(0.0000001);

    private final StockRollingWindow window1d = new StockRollingWindow(Duration.ofDays(1));
    private final StockRollingWindow window7d = new StockRollingWindow(Duration.ofDays(7));
    private final StockRollingWindow window30d = new StockRollingWindow(Duration.ofDays(30));
    private final StockRollingRsiWindow rsiWindow = new StockRollingRsiWindow();

    private final NavigableMap<LocalDateTime, StockPricePoint> priceMap = new TreeMap<>();

    /**
     * 预热窗口 — 静默添加历史数据点，不计算特征值
     * <p>用于服务重启后的窗口冷启动回填，避免窗口分化不足导致 Z-Score 失真
     */
    public void warmup(StockPricePoint point) {
        window1d.add(point);
        window7d.add(point);
        window30d.add(point);
        rsiWindow.add(point.price());
        priceMap.put(point.time(), point);
        evictPriceMap(point.time());
    }

    /**
     * 添加并计算特征值
     */
    public StockStrategyFeatureUpsert addAndCalculate(StockPricePoint point) {
        window1d.add(point);
        window7d.add(point);
        window30d.add(point);
        rsiWindow.add(point.price());

        priceMap.put(point.time(), point);
        evictPriceMap(point.time());

        BigDecimal ma1d = window1d.average();
        BigDecimal ma7d = window7d.average();
        BigDecimal ma30d = window30d.average();

        BigDecimal std1d = window1d.standardDeviation();
        BigDecimal std7d = window7d.standardDeviation();
        BigDecimal std30d = window30d.standardDeviation();

        BigDecimal low30d = window30d.min();
        BigDecimal high30d = window30d.max();

        BigDecimal return1d = calculateReturn(point.price(), point.time().minusDays(1));
        BigDecimal return7d = calculateReturn(point.price(), point.time().minusDays(7));
        BigDecimal return14d = calculateReturn(point.price(), point.time().minusDays(14));

        int investorsChange7d = calculateInvestorsChange(point.investors(), point.time().minusDays(7));

        return new StockStrategyFeatureUpsert(
                point.stocksId(),
                point.stocksShortname(),
                point.time(),
                toPrice(point.price()),
                setScale(ma1d),
                setScale(ma7d),
                setScale(ma30d),
                setScale(zScore(point.price(), ma1d, std1d)),
                setScale(zScore(point.price(), ma7d, std7d)),
                setScale(zScore(point.price(), ma30d, std30d)),
                setScale(rsiWindow.rsi()),
                setScale(return1d),
                setScale(return7d),
                setScale(return14d),
                setScale(safeRatio(point.price(), low30d).subtract(BigDecimal.ONE)),
                setScale(safeRatio(point.price(), high30d).subtract(BigDecimal.ONE)),
                toPrice(low30d),
                toPrice(high30d),
                point.investors(),
                investorsChange7d);
    }

    /**
     * 追赶价格Map
     */
    private void evictPriceMap(LocalDateTime now) {
        LocalDateTime minTime = now.minusDays(31);

        while (!priceMap.isEmpty() && priceMap.firstKey().isBefore(minTime)) {
            priceMap.pollFirstEntry();
        }
    }

    /**
     * 计算回调
     */
    private BigDecimal calculateReturn(BigDecimal currentPrice, LocalDateTime targetTime) {
        Map.Entry<LocalDateTime, StockPricePoint> entry = priceMap.floorEntry(targetTime);
        if (entry == null || entry.getValue().price().compareTo(EPSILON) < 0) {
            return BigDecimal.ZERO;
        }

        return currentPrice
                .divide(entry.getValue().price(), CALC_SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
    }

    /**
     * 计算投资人数变化
     */
    private int calculateInvestorsChange(int currentInvestors, LocalDateTime targetTime) {
        Map.Entry<LocalDateTime, StockPricePoint> entry = priceMap.floorEntry(targetTime);

        if (entry == null) {
            return 0;
        }

        return currentInvestors - entry.getValue().investors();
    }

    /**
     * 价格偏离评分
     */
    private BigDecimal zScore(BigDecimal value, BigDecimal average, BigDecimal standardDeviation) {
        if (standardDeviation.compareTo(EPSILON) < 0) {
            return BigDecimal.ZERO;
        }
        return value.subtract(average)
                .divide(standardDeviation, CALC_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 安全比例
     */
    private BigDecimal safeRatio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.compareTo(EPSILON) < 0) {
            return BigDecimal.ZERO;
        }

        return numerator.divide(denominator, CALC_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 设置精度
     */
    private BigDecimal setScale(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }

        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 转换为价格
     */
    private BigDecimal toPrice(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}