package pn.torn.goldeneye.torn.service.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockStrategyFeaturePoint;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mFeatureBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mTradeFeatureProvider;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mTradeFeatureProvider.ProvidedTradeFeature;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMinuteRsiCalculator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 私聊{@code Stock分析}15m特征取数组件单元测试 - 覆盖设计§3.6主路径:
 * DO→内部特征点改名映射(窗口指标null原样保留)、C分流(参考价空不进入输出)、
 * B分流(windowInsufficient+质量原因透传)与D分流(48小时陈旧边界)。
 *
 * @author Bai
 * @version 1.8.0
 * @since 2026.10.04
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("私聊Stock分析15m特征取数组件测试")
class Stock15mTradeFeatureProviderTest {

    /**
     * 分析时点(远端未来,隔离于生产数据)
     */
    private static final LocalDateTime ANALYSIS_TIME = LocalDateTime.of(2026, 10, 1, 12, 0);
    private static final int TEST_STOCKS_ID = 2099201;
    private static final String TEST_SHORTNAME = "ITSP";
    private static final LocalDateTime TEST_BAR_START_TIME = LocalDateTime.of(2026, 9, 30, 10, 0);
    private static final BigDecimal TEST_REFERENCE_PRICE = new BigDecimal("150.000000");

    @Mock
    private TornStockStrategyFeature15mDAO feature15mDao;
    @Mock
    private StockMinuteRsiCalculator rsiCalculator;

    @InjectMocks
    private Stock15mTradeFeatureProvider provider;

    @Test
    @DisplayName("映射_DO改名映射为内部特征点_现算RSI注入且null窗口指标原样保留")
    void loadLatestFeatures_mapsDoToFeaturePoint_preservingNullWindowMetrics() {
        TornStockStrategyFeature15mDO feature = buildReadyFeature(TEST_STOCKS_ID, TEST_BAR_START_TIME);
        feature.setZscore1d(new BigDecimal("1.23"));
        feature.setZscore7d(new BigDecimal("2.34"));
        feature.setZscore30d(new BigDecimal("3.45"));
        when(feature15mDao.selectLatestFeatures(ANALYSIS_TIME, Stock15mFeatureBuildService.FEATURE_VERSION))
                .thenReturn(List.of(feature));
        when(rsiCalculator.computeAll(ANALYSIS_TIME))
                .thenReturn(Map.of(TEST_STOCKS_ID, new BigDecimal("63.5")));

        List<ProvidedTradeFeature> result = provider.loadLatestFeatures(ANALYSIS_TIME);

        assertEquals(1, result.size(), "正常特征应进入输出");
        StockStrategyFeaturePoint point = result.getFirst().point();
        assertEquals(TEST_STOCKS_ID, point.stocksId(), "股票ID应原样映射");
        assertEquals(TEST_SHORTNAME, point.stocksShortname(), "股票简称应原样映射");
        assertEquals(TEST_BAR_START_TIME, point.featureTime(), "barStartTime必须改名映射为featureTime");
        assertEquals(0, TEST_REFERENCE_PRICE.compareTo(point.basePrice()),
                "referencePrice必须改名映射为basePrice");
        assertEquals(0, new BigDecimal("63.5").compareTo(point.rsi()), "现算RSI必须注入特征点");
        assertEquals(0, new BigDecimal("1.23").compareTo(point.zScore1d()), "zscore1d必须映射为zScore1d");
        assertEquals(0, new BigDecimal("2.34").compareTo(point.zScore7d()), "zscore7d必须映射为zScore7d");
        assertEquals(0, new BigDecimal("3.45").compareTo(point.zScore30d()), "zscore30d必须映射为zScore30d");
        assertNull(point.ma1d(), "不可计算窗口指标必须保持null,不得填充伪造值");
        assertNull(point.ma30d(), "不可计算窗口指标必须保持null,不得填充伪造值");
        assertNull(point.return1d(), "不可计算窗口指标必须保持null,不得填充伪造值");
        assertNull(point.return14d(), "不可计算窗口指标必须保持null,不得填充伪造值");
        assertNull(point.pctAbove30dLow(), "不可计算窗口指标必须保持null,不得填充伪造值");
        assertFalse(result.getFirst().windowInsufficient(), "strategyReady=true不得标记窗口未就绪");
        assertFalse(result.getFirst().stale(), "新鲜特征不得标记陈旧");
        assertNull(result.getFirst().dataQualityReason(), "正常特征无质量原因");
    }

    @Test
    @DisplayName("C分流_referencePrice为空的股票不进入输出且不影响其余股票")
    void loadLatestFeatures_nullReferencePrice_excludedFromResult() {
        TornStockStrategyFeature15mDO nullPrice = buildReadyFeature(TEST_STOCKS_ID, TEST_BAR_START_TIME);
        nullPrice.setReferencePrice(null);
        TornStockStrategyFeature15mDO normal = buildReadyFeature(2099202, TEST_BAR_START_TIME);
        when(feature15mDao.selectLatestFeatures(ANALYSIS_TIME, Stock15mFeatureBuildService.FEATURE_VERSION))
                .thenReturn(List.of(nullPrice, normal));
        when(rsiCalculator.computeAll(ANALYSIS_TIME)).thenReturn(Map.of());

        List<ProvidedTradeFeature> result = provider.loadLatestFeatures(ANALYSIS_TIME);

        assertEquals(1, result.size(), "参考价为空的股票必须被C分流剔除");
        assertEquals(Integer.valueOf(2099202), result.getFirst().point().stocksId(),
                "仅保留参考价非空的股票");
    }

    @Test
    @DisplayName("B分流_strategyReady非true标记windowInsufficient且质量原因透传")
    void loadLatestFeatures_notStrategyReady_windowInsufficientWithReason() {
        TornStockStrategyFeature15mDO feature = buildReadyFeature(TEST_STOCKS_ID, TEST_BAR_START_TIME);
        feature.setStrategyReady(false);
        feature.setDataQualityReason("INSUFFICIENT_HISTORY");
        when(feature15mDao.selectLatestFeatures(ANALYSIS_TIME, Stock15mFeatureBuildService.FEATURE_VERSION))
                .thenReturn(List.of(feature));
        when(rsiCalculator.computeAll(ANALYSIS_TIME)).thenReturn(Map.of());

        List<ProvidedTradeFeature> result = provider.loadLatestFeatures(ANALYSIS_TIME);

        assertEquals(1, result.size(), "窗口未就绪股票仍进入输出,由评分层叠加门槛加成");
        ProvidedTradeFeature provided = result.getFirst();
        assertTrue(provided.windowInsufficient(), "strategyReady!=true必须标记windowInsufficient");
        assertEquals("INSUFFICIENT_HISTORY", provided.dataQualityReason(),
                "15m表的质量原因必须原样透传");
        assertEquals(0, BigDecimal.valueOf(50).compareTo(provided.point().rsi()),
                "无分钟数据的股票RSI按兜底值50注入");
    }

    @Test
    @DisplayName("D分流_最新bar距分析时点恰好48小时不陈旧,早于48小时标记stale")
    void loadLatestFeatures_staleBoundary_exact48hNotStale() {
        LocalDateTime boundaryBar = ANALYSIS_TIME.minusHours(48);
        LocalDateTime staleBar = ANALYSIS_TIME.minusHours(48).minusMinutes(15);
        TornStockStrategyFeature15mDO boundary = buildReadyFeature(2099203, boundaryBar);
        TornStockStrategyFeature15mDO stale = buildReadyFeature(2099204, staleBar);
        when(feature15mDao.selectLatestFeatures(ANALYSIS_TIME, Stock15mFeatureBuildService.FEATURE_VERSION))
                .thenReturn(List.of(boundary, stale));
        when(rsiCalculator.computeAll(ANALYSIS_TIME)).thenReturn(Map.of());

        List<ProvidedTradeFeature> result = provider.loadLatestFeatures(ANALYSIS_TIME);

        ProvidedTradeFeature boundaryFeature = result.stream()
                .filter(provided -> provided.point().stocksId() == 2099203).findFirst().orElseThrow();
        ProvidedTradeFeature staleFeature = result.stream()
                .filter(provided -> provided.point().stocksId() == 2099204).findFirst().orElseThrow();
        assertFalse(boundaryFeature.stale(), "恰好48小时边界不得标记陈旧");
        assertTrue(staleFeature.stale(), "早于48小时必须标记陈旧(该股不推荐)");
    }

    /**
     * 构建strategyReady=true的正常特征DO(窗口指标默认null)。
     *
     * @param stocksId     股票ID
     * @param barStartTime bar开始时间
     * @return 正常特征DO
     */
    private TornStockStrategyFeature15mDO buildReadyFeature(int stocksId, LocalDateTime barStartTime) {
        TornStockStrategyFeature15mDO feature = new TornStockStrategyFeature15mDO();
        feature.setStocksId(stocksId);
        feature.setStocksShortname(TEST_SHORTNAME);
        feature.setBarStartTime(barStartTime);
        feature.setReferencePrice(TEST_REFERENCE_PRICE);
        feature.setStrategyReady(true);
        feature.setFeatureVersion(Stock15mFeatureBuildService.FEATURE_VERSION);
        return feature;
    }
}
