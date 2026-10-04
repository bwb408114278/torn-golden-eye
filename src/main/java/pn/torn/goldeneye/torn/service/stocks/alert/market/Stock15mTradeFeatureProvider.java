package pn.torn.goldeneye.torn.service.stocks.alert.market;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockStrategyFeaturePoint;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.user.StockMinuteRsiCalculator;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 私聊{@code Stock分析}指令的15m特征取数组件(只读)。
 * <p>
 * 消费{@code torn_stock_strategy_feature_15m}每股最新一行(版本过滤、依赖既有部分索引,
 * 不加列、不改表),映射为内部特征点{@link StockStrategyFeaturePoint};RSI不在表中持久化,
 * 由指令触发时经{@link StockMinuteRsiCalculator}现算注入。执行以下分流(设计§3.3):
 * <ul>
 *   <li>A 正常: {@code strategyReady=true}且参考价非空 → 原样输出;</li>
 *   <li>B 窗口未就绪: {@code strategyReady!=true} → 标记windowInsufficient(买入门槛+10,
 *       由评分层叠加),为null的窗口指标一律视为该分支不命中,不加分不扣分;</li>
 *   <li>C 行不可用: {@code referencePrice=null} → 不进入输出,记WARN(含股票ID与桶时间);</li>
 *   <li>D 数据陈旧: 最新{@code barStartTime}距分析时点超过48小时 → 标记stale,
 *       该股不推荐、理由标注"特征陈旧",防止停产后静默输出过期建议。</li>
 * </ul>
 * 本组件不得引用月度风格解析组件(月度风格不进入α/市场包,只被私聊策略服务消费)。
 *
 * @author Bai
 * @version 1.8.0
 * @since 2026.10.04
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Stock15mTradeFeatureProvider {

    /**
     * 特征陈旧判定阈值: 最新bar开始时间距分析时点超过该时长视为陈旧
     */
    private static final Duration STALE_FEATURE_MAX_AGE = Duration.ofHours(48);

    /**
     * 分钟点不足RSI(60)窗口时的兜底值(与{@code StockRollingRsiWindow}不足60期返回50一致)
     */
    private static final BigDecimal DEFAULT_RSI = BigDecimal.valueOf(50);

    private final TornStockStrategyFeature15mDAO feature15mDao;
    private final StockMinuteRsiCalculator rsiCalculator;

    /**
     * 取数结果 - 单支股票的特征点与就绪分流标记。
     *
     * @param point              内部特征点(参考价非空;窗口指标在不可计算时为null)
     * @param windowInsufficient 15m构建期显式结论: 窗口未就绪(等价旧"窗口数据不足",门槛+10)
     * @param dataQualityReason  不可用原因编码(windowInsufficient=true时来自15m表,可为null)
     * @param stale              特征陈旧(最新bar距分析时点超过48小时,该股不推荐)
     */
    public record ProvidedTradeFeature(
            StockStrategyFeaturePoint point,
            boolean windowInsufficient,
            String dataQualityReason,
            boolean stale) {
    }

    /**
     * 加载每股最新15m特征并完成A/B/C/D分流。
     *
     * @param analysisTime 分析时点
     * @return 通过C分流(参考价非空)的特征列表;无特征数据时返回空列表
     */
    public List<ProvidedTradeFeature> loadLatestFeatures(LocalDateTime analysisTime) {
        List<TornStockStrategyFeature15mDO> latestFeatures =
                feature15mDao.selectLatestFeatures(analysisTime, Stock15mFeatureBuildService.FEATURE_VERSION);
        if (CollectionUtils.isEmpty(latestFeatures)) {
            return List.of();
        }

        Map<Integer, BigDecimal> rsiByStock = rsiCalculator.computeAll(analysisTime);
        List<ProvidedTradeFeature> result = new ArrayList<>(latestFeatures.size());
        for (TornStockStrategyFeature15mDO feature : latestFeatures) {
            if (feature == null || feature.getStocksId() == null) {
                continue;
            }
            if (feature.getReferencePrice() == null) {
                log.warn("Stock分析特征取数-参考价为空,该股不进入输出: stocksId={}, barStartTime={}",
                        feature.getStocksId(), feature.getBarStartTime());
            } else {
                result.add(toProvidedFeature(feature, rsiByStock, analysisTime));
            }
        }
        return result;
    }

    /**
     * 单支股票的C/D分流组装: 参考价非空才进入输出;
     * 最新bar距分析时点超过{@link #STALE_FEATURE_MAX_AGE}标记stale(该股不推荐)。
     *
     * @param feature      15m特征DO(参考价非空)
     * @param rsiByStock   现算RSI映射
     * @param analysisTime 分析时点
     * @return 分流后的特征结果
     */
    private ProvidedTradeFeature toProvidedFeature(TornStockStrategyFeature15mDO feature,
                                                   Map<Integer, BigDecimal> rsiByStock,
                                                   LocalDateTime analysisTime) {
        boolean windowInsufficient = !Boolean.TRUE.equals(feature.getStrategyReady());
        boolean stale = feature.getBarStartTime() != null
                && feature.getBarStartTime().isBefore(analysisTime.minus(STALE_FEATURE_MAX_AGE));
        return new ProvidedTradeFeature(
                toFeaturePoint(feature, rsiByStock),
                windowInsufficient,
                feature.getDataQualityReason(),
                stale);
    }

    /**
     * 15m特征DO映射为内部特征点(设计§3.1.1行级映射)。
     * <p>
     * 改名映射: {@code referencePrice→basePrice}、{@code barStartTime→featureTime};
     * RSI不在15m表持久化,由现算结果注入(无分钟数据时兜底50,与滚动窗口不足60期口径一致);
     * 窗口指标在不可计算时保持null,由评分层null安全分流,不填充伪造值。
     *
     * @param feature    15m特征DO(参考价非空)
     * @param rsiByStock 现算RSI映射
     * @return 内部特征点
     */
    private StockStrategyFeaturePoint toFeaturePoint(TornStockStrategyFeature15mDO feature,
                                                     Map<Integer, BigDecimal> rsiByStock) {
        BigDecimal rsi = rsiByStock.get(feature.getStocksId());
        return new StockStrategyFeaturePoint(
                feature.getStocksId(),
                feature.getStocksShortname(),
                feature.getBarStartTime(),
                feature.getReferencePrice(),
                feature.getMa1d(),
                feature.getMa7d(),
                feature.getMa30d(),
                feature.getZscore1d(),
                feature.getZscore7d(),
                feature.getZscore30d(),
                rsi != null ? rsi : DEFAULT_RSI,
                feature.getReturn1d(),
                feature.getReturn7d(),
                feature.getReturn14d(),
                feature.getPctAbove30dLow(),
                feature.getPctBelow30dHigh());
    }
}
