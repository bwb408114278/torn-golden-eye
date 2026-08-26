package pn.torn.goldeneye.torn.service.stocks.rebuild;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mFeatureBuildService;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * @version 1.4.8
 * @since 2026.08.23
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
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

    /**
     * 为单支股票构建策略特征（纯历史列表入口）。
     *
     * @param currentBar  当前 bar（必须可用）
     * @param historyBars 该股票的历史 bar 列表（不含当前 bar，按时间升序排列）
     * @return 填充完整的特征 DO；当前 bar 不可用时返回 {@code null}
     */
    public static TornStockStrategyFeature15mDO buildSingleFeature(TornStockMarketBar15mDO currentBar,
                                                                   List<TornStockMarketBar15mDO> historyBars) {
        Stock15mFeatureRollingWindow window = new Stock15mFeatureRollingWindow();
        if (historyBars != null) {
            for (TornStockMarketBar15mDO historyBar : historyBars) {
                window.advance(historyBar);
            }
        }
        window.advance(currentBar);
        return window.materializeCurrent();
    }

    /**
     * 为单支股票构建策略特征（滚动窗口入口）。
     * <p>
     * 窗口已包含当前 bar 之前的所有应保留历史，调用方须先通过
     * {@link Stock15mFeatureRollingWindow#advance} 将当前 bar 加入窗口。
     *
     * @param currentBar 当前 bar（必须可用）
     * @param window     已追加当前 bar 的滚动窗口
     * @return 填充完整的特征 DO；当前 bar 不可用时返回 {@code null}
     */
    public static TornStockStrategyFeature15mDO buildFeature(TornStockMarketBar15mDO currentBar,
                                                             Stock15mFeatureRollingWindow window) {
        if (!Stock15mBarBuildService.isUsable(currentBar)) {
            return null;
        }

        BigDecimal referencePrice = currentBar.getLastPrice();
        int barsPerDay = Stock15mFeatureBuildService.BARS_PER_DAY;
        int bars6h = Stock15mFeatureBuildService.BARS_6H;
        int bars7d = Stock15mFeatureBuildService.BARS_7D;
        int bars14d = Stock15mFeatureBuildService.BARS_14D;
        int bars30d = Stock15mFeatureBuildService.BARS_30D;

        BigDecimal ma1d = window.calculateMa(barsPerDay);
        BigDecimal ma7d = window.calculateMa(bars7d);
        BigDecimal ma30d = window.calculateMa(bars30d);

        BigDecimal zscore1d = calculateZScore(referencePrice, ma1d, window.calculateStd(barsPerDay, ma1d));
        BigDecimal zscore7d = calculateZScore(referencePrice, ma7d, window.calculateStd(bars7d, ma7d));
        BigDecimal zscore30d = calculateZScore(referencePrice, ma30d, window.calculateStd(bars30d, ma30d));

        BigDecimal return6h = window.calculateReturn(bars6h);
        BigDecimal return1d = window.calculateReturn(barsPerDay);
        BigDecimal return7d = window.calculateReturn(bars7d);
        BigDecimal return14d = window.calculateReturn(bars14d);

        BigDecimal low30d = window.low30d();
        BigDecimal high30d = window.high30d();
        BigDecimal width30d = calculateWidth30(low30d, high30d);
        BigDecimal position30 = calculatePosition30(referencePrice, low30d, high30d);
        BigDecimal pctAbove30dLow = calculatePctAboveLow(referencePrice, low30d);
        BigDecimal pctBelow30dHigh = calculatePctBelowHigh(referencePrice, high30d);

        boolean strategyReady = window.isStrategyReady();
        String dataQualityReason = strategyReady ? null : resolveDataQualityReason(window.size());

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

    /**
     * 计算 Z-Score。
     *
     * @param price 当前参考价
     * @param ma    窗口均线
     * @param std   窗口标准差
     * @return Z-Score；均线为空时返回 {@code null}，标准差为 0 时返回 0
     */
    private static BigDecimal calculateZScore(BigDecimal price, BigDecimal ma, BigDecimal std) {
        if (ma == null) {
            return null;
        }
        if (std == null || std.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return price.subtract(ma).divide(std, CALC_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算 30 日振幅宽度。
     *
     * @param low30d  30 日最低价
     * @param high30d 30 日最高价
     * @return 振幅宽度;预热不足30日(基准缺失)时返回 {@code null},基准为 0 时返回 0
     */
    private static BigDecimal calculateWidth30(BigDecimal low30d, BigDecimal high30d) {
        if (low30d == null || high30d == null) {
            return null;
        }
        if (low30d.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return high30d.subtract(low30d)
                .divide(low30d, CALC_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算当前价格相对 30 日最低价的涨幅。
     *
     * @param currentPrice 当前价格
     * @param low30d       30 日最低价
     * @return 相对最低价涨幅;预热不足30日(基准缺失)时返回 {@code null},基准为 0 时返回 0
     */
    private static BigDecimal calculatePctAboveLow(BigDecimal currentPrice, BigDecimal low30d) {
        if (low30d == null) {
            return null;
        }
        if (currentPrice == null || low30d.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return currentPrice.divide(low30d, CALC_SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
    }

    /**
     * 计算当前价格相对 30 日最高价的跌幅（正数表示低于最高价的幅度）。
     *
     * @param currentPrice 当前价格
     * @param high30d      30 日最高价
     * @return 相对最高价跌幅;预热不足30日(基准缺失)时返回 {@code null},基准为 0 时返回 0
     */
    private static BigDecimal calculatePctBelowHigh(BigDecimal currentPrice, BigDecimal high30d) {
        if (high30d == null) {
            return null;
        }
        if (currentPrice == null || high30d.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return currentPrice.divide(high30d, CALC_SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
    }

    /**
     * 计算当前价格在 30 日高低区间中的位置。
     *
     * @param currentPrice 当前价格
     * @param low30d       30 日最低价
     * @param high30d      30 日最高价
     * @return 0 到 1 区间位置；数据缺失或区间为 0 时返回 {@code null}
     */
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

    /**
     * 解析数据质量原因：历史不足或历史不连续。
     *
     * @param totalBars 当前窗口总 bar 数
     * @return 质量原因枚举字符串
     */
    private static String resolveDataQualityReason(int totalBars) {
        if (totalBars < Stock15mFeatureBuildService.BARS_30D) {
            return QUALITY_REASON_INSUFFICIENT;
        }
        return QUALITY_REASON_NOT_CONSECUTIVE;
    }
}
