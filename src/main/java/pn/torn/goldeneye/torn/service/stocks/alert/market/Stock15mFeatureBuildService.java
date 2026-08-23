package pn.torn.goldeneye.torn.service.stocks.alert.market;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.rebuild.Stock15mFeatureCalculator;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * @version 1.4.2
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
        log.debug("桶{}成功构建并保存{}支股票的策略特征, readyCount={}, notReadyCount={}, notReadyCountByReason={}",
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
        return Stock15mFeatureCalculator.buildSingleFeature(currentBar, historyBars);
    }
}
