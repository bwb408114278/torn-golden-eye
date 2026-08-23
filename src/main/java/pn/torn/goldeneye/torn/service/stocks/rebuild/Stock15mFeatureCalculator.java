package pn.torn.goldeneye.torn.service.stocks.rebuild;

import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mFeatureBuildService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 15 分钟策略特征纯计算器。
 * <p>
 * 从 {@link Stock15mFeatureBuildService} 中抽取的无状态计算逻辑，供实时单桶路径与
 * 全范围派生数据重建批处理共同调用，禁止维护第二份指标公式。所有方法均为纯函数，
 * 不访问数据库、不依赖系统时钟。
 * <p>
 * 因果性由调用方保证：传入的 {@code historyBars} 必须严格早于当前 bar，且按时间升序。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
public final class Stock15mFeatureCalculator {

    /**
     * 计算精度。
     */
    private static final int CALC_SCALE = 18;
    /**
     * 质量原因：历史不足。
     */
    private static final String QUALITY_REASON_INSUFFICIENT = "INSUFFICIENT_HISTORY";
    /**
     * 质量原因：历史不连续。
     */
    private static final String QUALITY_REASON_NOT_CONSECUTIVE = "HISTORY_NOT_CONSECUTIVE";

    private Stock15mFeatureCalculator() {
    }

    /**
     * 为单支股票构建策略特征。
     *
     * @param currentBar  当前 bar（必须可用）
     * @param historyBars 该股票的历史 bar 列表（不含当前 bar，按时间升序排列）
     * @return 填充完整的特征 DO；当前 bar 不可用时返回 {@code null}
     */
    public static TornStockStrategyFeature15mDO buildSingleFeature(TornStockMarketBar15mDO currentBar,
                                                                   List<TornStockMarketBar15mDO> historyBars) {
        if (!Stock15mBarBuildService.isUsable(currentBar)) {
            return null;
        }

        List<TornStockMarketBar15mDO> allBars = new ArrayList<>(historyBars);
        allBars.add(currentBar);
        allBars.sort(Comparator.comparing(TornStockMarketBar15mDO::getBarStartTime));

        BigDecimal referencePrice = currentBar.getLastPrice();
        List<BigDecimal> prices = allBars.stream()
                .map(TornStockMarketBar15mDO::getLastPrice)
                .toList();

        int totalBars = prices.size();
        int barsPerDay = Stock15mFeatureBuildService.BARS_PER_DAY;
        int bars6h = Stock15mFeatureBuildService.BARS_6H;
        int bars7d = Stock15mFeatureBuildService.BARS_7D;
        int bars14d = Stock15mFeatureBuildService.BARS_14D;
        int bars30d = Stock15mFeatureBuildService.BARS_30D;

        BigDecimal ma1d = calculateMa(prices, totalBars, barsPerDay);
        BigDecimal ma7d = calculateMa(prices, totalBars, bars7d);
        BigDecimal ma30d = calculateMa(prices, totalBars, bars30d);

        BigDecimal zscore1d = calculateZScore(referencePrice, ma1d, prices, totalBars, barsPerDay);
        BigDecimal zscore7d = calculateZScore(referencePrice, ma7d, prices, totalBars, bars7d);
        BigDecimal zscore30d = calculateZScore(referencePrice, ma30d, prices, totalBars, bars30d);

        BigDecimal return6h = calculateReturn(prices, totalBars, bars6h);
        BigDecimal return1d = calculateReturn(prices, totalBars, barsPerDay);
        BigDecimal return7d = calculateReturn(prices, totalBars, bars7d);
        BigDecimal return14d = calculateReturn(prices, totalBars, bars14d);

        BigDecimal low30d = calculateLow(allBars, bars30d);
        BigDecimal high30d = calculateHigh(allBars, bars30d);
        BigDecimal width30d = calculateWidth30(low30d, high30d);
        BigDecimal position30 = calculatePosition30(referencePrice, low30d, high30d);
        BigDecimal pctAbove30dLow = calculatePctAboveLow(referencePrice, low30d);
        BigDecimal pctBelow30dHigh = calculatePctBelowHigh(referencePrice, high30d);

        boolean strategyReady = checkStrategyReady(allBars);
        String dataQualityReason = strategyReady ? null : resolveDataQualityReason(allBars);

        TornStockStrategyFeature15mDO feature = new TornStockStrategyFeature15mDO();
        feature.setStocksId(currentBar.getStocksId());
        feature.setStocksShortname(currentBar.getStocksShortname());
        feature.setBarStartTime(currentBar.getBarStartTime());
        feature.setReferencePrice(referencePrice);
        feature.setMa1d(ma1d);
        feature.setMa7d(ma7d);
        feature.setMa30d(ma30d);
        feature.setZscore1d(zscore1d);
        feature.setZscore7d(zscore7d);
        feature.setZscore30d(zscore30d);
        feature.setReturn6h(return6h);
        feature.setReturn1d(return1d);
        feature.setReturn7d(return7d);
        feature.setReturn14d(return14d);
        feature.setLow30d(low30d);
        feature.setHigh30d(high30d);
        feature.setWidth30d(width30d);
        feature.setPosition30(position30);
        feature.setPctAbove30dLow(pctAbove30dLow);
        feature.setPctBelow30dHigh(pctBelow30dHigh);
        feature.setStrategyReady(strategyReady);
        feature.setDataQualityReason(dataQualityReason);
        feature.setFeatureVersion(Stock15mFeatureBuildService.FEATURE_VERSION);
        return feature;
    }

    private static BigDecimal calculateMa(List<BigDecimal> prices, int totalBars, int window) {
        if (totalBars < window) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = totalBars - window; i < totalBars; i++) {
            sum = sum.add(prices.get(i));
        }
        return sum.divide(BigDecimal.valueOf(window), CALC_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal calculateStd(List<BigDecimal> prices, int totalBars, int window, BigDecimal ma) {
        if (ma == null || totalBars < window) {
            return null;
        }
        BigDecimal sumSqDiff = BigDecimal.ZERO;
        for (int i = totalBars - window; i < totalBars; i++) {
            BigDecimal diff = prices.get(i).subtract(ma);
            sumSqDiff = sumSqDiff.add(diff.multiply(diff));
        }
        BigDecimal variance = sumSqDiff.divide(BigDecimal.valueOf(window), CALC_SCALE, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue())).setScale(CALC_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal calculateZScore(BigDecimal price, BigDecimal ma,
                                              List<BigDecimal> prices, int totalBars, int window) {
        if (ma == null) {
            return null;
        }
        BigDecimal std = calculateStd(prices, totalBars, window, ma);
        if (std == null || std.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return price.subtract(ma).divide(std, CALC_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal calculateReturn(List<BigDecimal> prices, int totalBars, int windowAgo) {
        if (totalBars <= windowAgo) {
            return null;
        }
        BigDecimal pastPrice = prices.get(totalBars - 1 - windowAgo);
        BigDecimal currentPrice = prices.get(totalBars - 1);
        if (pastPrice.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return currentPrice.divide(pastPrice, CALC_SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
    }

    private static BigDecimal calculateLow(List<TornStockMarketBar15mDO> bars, int window) {
        int start = Math.max(0, bars.size() - window);
        return bars.subList(start, bars.size()).stream()
                .map(TornStockMarketBar15mDO::getLastPrice)
                .min(BigDecimal::compareTo)
                .orElse(null);
    }

    private static BigDecimal calculateHigh(List<TornStockMarketBar15mDO> bars, int window) {
        int start = Math.max(0, bars.size() - window);
        return bars.subList(start, bars.size()).stream()
                .map(TornStockMarketBar15mDO::getLastPrice)
                .max(BigDecimal::compareTo)
                .orElse(null);
    }

    private static BigDecimal calculateWidth30(BigDecimal low30d, BigDecimal high30d) {
        if (low30d == null || high30d == null || low30d.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return high30d.subtract(low30d)
                .divide(low30d, CALC_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal calculatePosition30(BigDecimal currentPrice, BigDecimal low30d, BigDecimal high30d) {
        if (currentPrice == null || low30d == null || high30d == null) {
            return null;
        }
        BigDecimal range = high30d.subtract(low30d);
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return currentPrice.subtract(low30d)
                .divide(range, CALC_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal calculatePctAboveLow(BigDecimal currentPrice, BigDecimal low30d) {
        if (currentPrice == null || low30d == null || low30d.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return currentPrice.divide(low30d, CALC_SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
    }

    private static BigDecimal calculatePctBelowHigh(BigDecimal currentPrice, BigDecimal high30d) {
        if (currentPrice == null || high30d == null || high30d.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return currentPrice.divide(high30d, CALC_SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
    }

    private static boolean checkStrategyReady(List<TornStockMarketBar15mDO> allBars) {
        if (allBars.size() < Stock15mFeatureBuildService.BARS_30D) {
            return false;
        }
        List<TornStockMarketBar15mDO> windowBars =
                allBars.subList(allBars.size() - Stock15mFeatureBuildService.BARS_30D, allBars.size());
        return isConsecutiveWindow(windowBars);
    }

    private static boolean isConsecutiveWindow(List<TornStockMarketBar15mDO> bars) {
        for (int i = 1; i < bars.size(); i++) {
            if (!Stock15mBarBuildService.isConsecutive(bars.get(i - 1), bars.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static String resolveDataQualityReason(List<TornStockMarketBar15mDO> allBars) {
        if (allBars.size() < Stock15mFeatureBuildService.BARS_30D) {
            return QUALITY_REASON_INSUFFICIENT;
        }
        return QUALITY_REASON_NOT_CONSECUTIVE;
    }
}
