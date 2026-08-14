package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 15分钟策略特征构建服务 - 基于标准15分钟bar计算因果策略特征
 * <p>
 * 严格遵循因果性: 只使用 {@code barStartTime} 及以前的可见bar,不读取未来bar。
 * 数据断层后的预热期可继续为开放持仓提供市场报价,但不得产生新买入信号,
 * 直到全部策略窗口重新满足 {@code strategyReady}。
 *
 * <h3>特征清单</h3>
 * <ul>
 *   <li>ma1d/ma7d/ma30d: 1/7/30日bar均价(1日=96个bar)</li>
 *   <li>zscore1d/zscore7d/zscore30d: 标准化偏离</li>
 *   <li>return6h/return1d/return7d/return14d: 对应时间窗口收益率</li>
 *   <li>low30d/high30d: 30日最低/最高实际bar价格</li>
 *   <li>width30d: 30日价格带宽</li>
 *   <li>position30: 当前价格在30日区间的位置(高低价相同则为null)</li>
 *   <li>pctAbove30dLow/pctBelow30dHigh: 距30日低点涨幅/高点跌幅</li>
 *   <li>strategyReady + dataQualityReason: 策略就绪状态与不可用原因</li>
 * </ul>
 *
 * <h3>空值语义</h3>
 * <p>
 * 当对应时间窗口不足或指标不可计算时,窗口指标(ma、zscore、return 等以及
 * low30d/high30d、width30d、pct_above/below)返回并持久化为{@code null},
 * 绝不填充0、参考价或前值等伪造值;
 * 此时{@code strategyReady=false},由{@code dataQualityReason}解释该空值。
 * 预热期(历史样本不足)仍会为每个可用当前bar构造并UPSERT特征记录,以便下游
 * {@link StockMarketRoundLoader} 加载、存量RANGE持仓退出链取回区间特征以及历史重建
 * 判定bar与feature一一对应;买入评估仅在{@code strategyReady=true}时执行。
 * </p>
 *
 * @author Bai
 * @version 1.2.17
 * @since 2026.07.24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Stock15mFeatureBuildService {
    /**
     * 特征计算版本
     */
    public static final String FEATURE_VERSION = "1.0.0";
    /**
     * 每日bar数量(24小时 × 4)
     */
    public static final int BARS_PER_DAY = 96;
    /**
     * 6小时bar数量
     */
    public static final int BARS_6H = 24;
    /**
     * 7日bar数量
     */
    public static final int BARS_7D = 672;
    /**
     * 14日bar数量
     */
    public static final int BARS_14D = 1344;
    /**
     * 30日bar数量
     */
    public static final int BARS_30D = 2880;
    /**
     * 计算精度
     */
    private static final int CALC_SCALE = 18;

    private final TornStockMarketBar15mDAO bar15mDao;
    private final TornStockStrategyFeature15mDAO feature15mDao;

    /**
     * 构建指定桶的全部股票策略特征
     * <p>
     * 查询本轮bar及该桶之前的历史bar(最多30天),按股票逐股计算因果特征,
     * 判断 {@code strategyReady} 后批量保存。
     *
     * @param barStartTime 决策bar开始时间(无需预先对齐)
     * @return 构建完成的特征列表(可能为空)
     */
    public List<TornStockStrategyFeature15mDO> buildFeatures(LocalDateTime barStartTime) {
        LocalDateTime alignedTime = Stock15mBarBuildService.alignToBucket(barStartTime);
        log.debug("开始构建15分钟特征, barStartTime={}", alignedTime);

        List<TornStockMarketBar15mDO> currentBars = bar15mDao.selectByBarStartTime(alignedTime, Stock15mBarBuildService.BUILD_VERSION);
        if (CollectionUtils.isEmpty(currentBars)) {
            log.warn("桶{}无可用bar,跳过特征构建", alignedTime);
            return List.of();
        }

        LocalDateTime historySince = alignedTime.minusDays(30).minusMinutes(15);
        List<TornStockMarketBar15mDO> historyBars = bar15mDao.selectByTimeRange(
                historySince, alignedTime.minusMinutes(15), Stock15mBarBuildService.BUILD_VERSION);

        Map<Integer, List<TornStockMarketBar15mDO>> historyByStock = historyBars.stream()
                .collect(Collectors.groupingBy(TornStockMarketBar15mDO::getStocksId));

        List<TornStockStrategyFeature15mDO> features = new ArrayList<>(currentBars.size());
        for (TornStockMarketBar15mDO currentBar : currentBars) {
            TornStockStrategyFeature15mDO feature = buildSingleFeature(currentBar,
                    historyByStock.getOrDefault(currentBar.getStocksId(), List.of()));
            if (feature != null) {
                features.add(feature);
            }
        }

        if (features.isEmpty()) {
            log.warn("桶{}构建特征结果为空", alignedTime);
            return List.of();
        }

        for (TornStockStrategyFeature15mDO feature : features) {
            feature15mDao.upsertFeature(feature);
        }
        long readyCount = features.stream()
                .filter(TornStockStrategyFeature15mDO::getStrategyReady)
                .count();
        Map<String, Long> notReadyCountByReason = features.stream()
                .filter(feature -> !Boolean.TRUE.equals(feature.getStrategyReady()))
                .collect(Collectors.groupingBy(TornStockStrategyFeature15mDO::getDataQualityReason,
                        Collectors.counting()));
        log.info("桶{}成功构建并保存{}支股票的策略特征, readyCount={}, notReadyCount={}, notReadyCountByReason={}",
                alignedTime, features.size(), readyCount, features.size() - readyCount, notReadyCountByReason);
        return features;
    }

    /**
     * 为单支股票构建策略特征
     *
     * @param currentBar  当前bar
     * @param historyBars 该股票的历史bar列表(不含当前bar,按时间升序排列)
     * @return 填充完整的特征DO,当前bar不可用时返回null
     */
    private TornStockStrategyFeature15mDO buildSingleFeature(TornStockMarketBar15mDO currentBar,
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

        BigDecimal ma1d = calculateMa(prices, totalBars, BARS_PER_DAY);
        BigDecimal ma7d = calculateMa(prices, totalBars, BARS_7D);
        BigDecimal ma30d = calculateMa(prices, totalBars, BARS_30D);

        BigDecimal zscore1d = calculateZScore(referencePrice, ma1d, prices, totalBars, BARS_PER_DAY);
        BigDecimal zscore7d = calculateZScore(referencePrice, ma7d, prices, totalBars, BARS_7D);
        BigDecimal zscore30d = calculateZScore(referencePrice, ma30d, prices, totalBars, BARS_30D);

        BigDecimal return6h = calculateReturn(prices, totalBars, BARS_6H);
        BigDecimal return1d = calculateReturn(prices, totalBars, BARS_PER_DAY);
        BigDecimal return7d = calculateReturn(prices, totalBars, BARS_7D);
        BigDecimal return14d = calculateReturn(prices, totalBars, BARS_14D);

        BigDecimal low30d = calculateLow(allBars, BARS_30D);
        BigDecimal high30d = calculateHigh(allBars, BARS_30D);
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
        feature.setFeatureVersion(FEATURE_VERSION);
        return feature;
    }

    /**
     * 计算移动平均
     *
     * @param prices    价格序列(按时间升序)
     * @param totalBars 总bar数
     * @param window    窗口大小
     * @return 移动平均值,窗口不足时返回null
     */
    private BigDecimal calculateMa(List<BigDecimal> prices, int totalBars, int window) {
        if (totalBars < window) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = totalBars - window; i < totalBars; i++) {
            sum = sum.add(prices.get(i));
        }
        return sum.divide(BigDecimal.valueOf(window), CALC_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算标准差
     *
     * @param prices    价格序列
     * @param totalBars 总bar数
     * @param window    窗口大小
     * @param ma        对应窗口的移动平均
     * @return 标准差,窗口不足或ma为null时返回null
     */
    private BigDecimal calculateStd(List<BigDecimal> prices, int totalBars, int window, BigDecimal ma) {
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

    /**
     * 计算Z-Score
     *
     * @param price     当前价格
     * @param ma        移动平均
     * @param prices    价格序列
     * @param totalBars 总bar数
     * @param window    窗口大小
     * @return Z-Score,标准差为零或窗口不足时返回BigDecimal.ZERO
     */
    private BigDecimal calculateZScore(BigDecimal price, BigDecimal ma,
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

    /**
     * 计算收益率 = currentPrice / pastPrice - 1
     *
     * @param prices    价格序列
     * @param totalBars 总bar数
     * @param windowAgo 回溯窗口
     * @return 收益率,窗口不足时返回null
     */
    private BigDecimal calculateReturn(List<BigDecimal> prices, int totalBars, int windowAgo) {
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

    /**
     * 计算指定窗口内的最低参考价(基于lastPrice)
     *
     * @param bars   bar列表
     * @param window 窗口大小
     * @return 最低参考价
     */
    private BigDecimal calculateLow(List<TornStockMarketBar15mDO> bars, int window) {
        int start = Math.max(0, bars.size() - window);
        return bars.subList(start, bars.size()).stream()
                .map(TornStockMarketBar15mDO::getLastPrice)
                .min(BigDecimal::compareTo)
                .orElse(null);
    }

    /**
     * 计算指定窗口内的最高参考价(基于lastPrice)
     *
     * @param bars   bar列表
     * @param window 窗口大小
     * @return 最高参考价
     */
    private BigDecimal calculateHigh(List<TornStockMarketBar15mDO> bars, int window) {
        int start = Math.max(0, bars.size() - window);
        return bars.subList(start, bars.size()).stream()
                .map(TornStockMarketBar15mDO::getLastPrice)
                .max(BigDecimal::compareTo)
                .orElse(null);
    }

    /**
     * 计算30日价格带宽 = (high30d - low30d) / low30d
     *
     * @param low30d  30日最低价
     * @param high30d 30日最高价
     * @return 带宽,low30d为零或null时返回BigDecimal.ZERO
     */
    private BigDecimal calculateWidth30(BigDecimal low30d, BigDecimal high30d) {
        if (low30d == null || high30d == null || low30d.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return high30d.subtract(low30d)
                .divide(low30d, CALC_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算当前价格在30日区间的位置 = (currentPrice - low30d) / (high30d - low30d)
     * <p>
     * high30d == low30d 时返回null(fail-closed)。
     *
     * @param currentPrice 当前价格
     * @param low30d       30日最低价
     * @param high30d      30日最高价
     * @return 位置值,高低价相同或参数为null时返回null
     */
    private BigDecimal calculatePosition30(BigDecimal currentPrice, BigDecimal low30d, BigDecimal high30d) {
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
     * 计算距30日低点涨幅 = currentPrice / low30d - 1
     *
     * @param currentPrice 当前价格
     * @param low30d       30日最低价
     * @return 涨幅,low30d为零或null时返回BigDecimal.ZERO
     */
    private BigDecimal calculatePctAboveLow(BigDecimal currentPrice, BigDecimal low30d) {
        if (currentPrice == null || low30d == null || low30d.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return currentPrice.divide(low30d, CALC_SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
    }

    /**
     * 计算距30日高点跌幅 = currentPrice / high30d - 1
     *
     * @param currentPrice 当前价格
     * @param high30d      30日最高价
     * @return 跌幅,high30d为零或null时返回BigDecimal.ZERO
     */
    private BigDecimal calculatePctBelowHigh(BigDecimal currentPrice, BigDecimal high30d) {
        if (currentPrice == null || high30d == null || high30d.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return currentPrice.divide(high30d, CALC_SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
    }

    /**
     * 质量原因: 历史不足
     */
    private static final String QUALITY_REASON_INSUFFICIENT = "INSUFFICIENT_HISTORY";
    /**
     * 质量原因: 历史不连续
     */
    private static final String QUALITY_REASON_NOT_CONSECUTIVE = "HISTORY_NOT_CONSECUTIVE";

    /**
     * 检查策略是否就绪
     * <p>
     * 需要30天(BARS_30D个bar)的连续数据,且全部bar可用、使用同一buildVersion、
     * 按15分钟严格连续无缺口。一旦发现缺口或不可用bar,strategyReady=false。
     *
     * @param allBars 全部bar列表(含当前bar,按时间升序)
     * @return true表示策略就绪
     */
    private boolean checkStrategyReady(List<TornStockMarketBar15mDO> allBars) {
        if (allBars.size() < BARS_30D) {
            return false;
        }
        List<TornStockMarketBar15mDO> windowBars = allBars.subList(allBars.size() - BARS_30D, allBars.size());
        return isConsecutiveWindow(windowBars);
    }

    /**
     * 判断窗口内bar是否按15分钟严格连续且全部可用
     *
     * @param bars 窗口内bar列表(按时间升序)
     * @return true表示连续且全部可用
     */
    private boolean isConsecutiveWindow(List<TornStockMarketBar15mDO> bars) {
        for (int i = 1; i < bars.size(); i++) {
            if (!Stock15mBarBuildService.isConsecutive(bars.get(i - 1), bars.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析数据质量原因
     *
     * @param allBars 全部bar列表
     * @return 质量原因编码
     */
    private String resolveDataQualityReason(List<TornStockMarketBar15mDO> allBars) {
        if (allBars.size() < BARS_30D) {
            return QUALITY_REASON_INSUFFICIENT;
        }
        return QUALITY_REASON_NOT_CONSECUTIVE;
    }
}
