package pn.torn.goldeneye.torn.service.stocks.alert.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 15分钟策略特征构建服务单元测试 - 覆盖因果性、收益率、通道宽度、位置与策略就绪的边界条件
 * <p>
 * 验证 {@link Stock15mFeatureBuildService} 的以下规则:
 * <ul>
 *   <li>只使用当前及历史bar(因果性),不读取未来bar</li>
 *   <li>return6h/1d/7d/14d 收益率计算: currentPrice / pastPrice - 1</li>
 *   <li>30日高低(low30d/high30d)取实际bar最低/最高价</li>
 *   <li>width30d = (high30d - low30d) / low30d</li>
 *   <li>position30 在 high30d == low30d 时为null(fail-closed)</li>
 *   <li>strategyReady 需总bar数 >= 2880(BARS_30D)且窗口连续可用</li>
 *   <li>当前bar不可用时不产生特征</li>
 *   <li>窗口不足或指标不可计算时窗口指标为null(不伪造值),特征仍UPSERT且strategyReady=false</li>
 * </ul>
 * 通过Mock DAO注入固定历史bar,验证特征输出数值。
 *
 * @author Bai
 * @version 1.2.18
 * @since 2026.07.24
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("15分钟策略特征构建服务测试")
class Stock15mFeatureBuildServiceTest {

    private static final int STOCKS_ID = 1;
    private static final String SHORTNAME = "TST";
    private static final int BARS_PER_DAY = Stock15mFeatureBuildService.BARS_PER_DAY;     // 96
    private static final int BARS_14D = Stock15mFeatureBuildService.BARS_14D;             // 1344
    private static final int BARS_30D = Stock15mFeatureBuildService.BARS_30D;             // 2880
    private static final String BUILD_VERSION = Stock15mBarBuildService.BUILD_VERSION;

    @Mock
    private TornStockMarketBar15mDAO bar15mDAO;

    @Mock
    private TornStockStrategyFeature15mDAO feature15mDAO;

    @InjectMocks
    private Stock15mFeatureBuildService featureBuildService;

    // ==================== 正常计算 ====================

    @Test
    @DisplayName("特征构建_97根bar预热_未计算窗口指标为null且仍调用UPSERT")
    void buildFeatures_97BarsPrewarm_uncomputedWindowMetricsNullAndUpsertCalled() {
        LocalDateTime barStart = LocalDateTime.of(2026, 7, 24, 10, 0);
        TornStockMarketBar15mDO currentBar = buildUsableBar(barStart, new BigDecimal("200.00"));
        // 96条历史bar,价格均为100 -> 总bar数97
        List<TornStockMarketBar15mDO> historyBars = buildHistoryBars(barStart, BARS_PER_DAY,
                new BigDecimal("100.00"));

        mockBarDao(barStart, currentBar, historyBars);

        List<TornStockStrategyFeature15mDO> features = featureBuildService.buildFeatures(barStart);

        assertEquals(1, features.size());
        TornStockStrategyFeature15mDO f = features.getFirst();
        assertEquals(STOCKS_ID, f.getStocksId());
        assertEquals(barStart, f.getBarStartTime());
        assertEquals(0, f.getReferencePrice().compareTo(new BigDecimal("200.00")),
                "参考价应为当前bar收盘价");
        // 97根bar可计算ma1d/return6h/return1d及可用窗口的30日高低区间
        // historyBars(96条,100) + currentBar(200) = 97条,取最后96条 = historyBars[1..95] + currentBar
        BigDecimal expectedMa1d = new BigDecimal("100.00").multiply(new BigDecimal("95"))
                .add(new BigDecimal("200.00"))
                .divide(new BigDecimal("96"), 18, java.math.RoundingMode.HALF_UP);
        assertEquals(0, f.getMa1d().compareTo(expectedMa1d), "ma1d应为最近96个bar均价");
        assertNotNull(f.getZscore1d(), "97根bar满足1日窗口,zscore1d应可计算");
        assertNotNull(f.getReturn6h(), "97根bar满足6小时窗口,return6h应可计算");
        assertNotNull(f.getReturn1d(), "97根bar满足1日窗口,return1d应可计算");
        assertNotNull(f.getLow30d(), "30日高低按可用bar计算,low30d不应为空");
        assertNotNull(f.getHigh30d(), "30日高低按可用bar计算,high30d不应为空");
        // 窗口不足的指标必须为null,而非0/参考价等伪造值
        assertNull(f.getMa7d(), "97根bar不足7日窗口,ma7d应为null");
        assertNull(f.getMa30d(), "97根bar不足30日窗口,ma30d应为null");
        assertNull(f.getZscore7d(), "ma7d为null则zscore7d应为null");
        assertNull(f.getZscore30d(), "ma30d为null则zscore30d应为null");
        assertNull(f.getReturn7d(), "97根bar不足7日窗口,return7d应为null");
        assertNull(f.getReturn14d(), "97根bar不足14日窗口,return14d应为null");
        assertFalse(f.getStrategyReady(), "总bar数97 < 2880 -> 策略未就绪");
        assertEquals("INSUFFICIENT_HISTORY", f.getDataQualityReason());
        // 预热特征仍必须UPSERT,不允许跳过写入
        verify(feature15mDAO).upsertFeature(any(TornStockStrategyFeature15mDO.class));
    }

    // ==================== high30 == low30 ====================

    @Test
    @DisplayName("特征构建_high30d等于low30d时position30为null且特征仍可持久化")
    void buildFeatures_high30EqualsLow30_position30Null() {
        LocalDateTime barStart = LocalDateTime.of(2026, 7, 24, 10, 0);
        BigDecimal fixedPrice = new BigDecimal("100.00");
        // 所有bar价格相同 -> high30d == low30d -> position30 = null
        TornStockMarketBar15mDO currentBar = buildUsableBar(barStart, fixedPrice);
        List<TornStockMarketBar15mDO> historyBars = buildHistoryBars(barStart, BARS_30D - 1, fixedPrice);

        mockBarDao(barStart, currentBar, historyBars);

        List<TornStockStrategyFeature15mDO> features = featureBuildService.buildFeatures(barStart);

        assertEquals(1, features.size(), "position30为空不能当作整个特征不完整,特征仍应产生");
        TornStockStrategyFeature15mDO f = features.getFirst();
        assertEquals(0, f.getLow30d().compareTo(fixedPrice));
        assertEquals(0, f.getHigh30d().compareTo(fixedPrice));
        assertNull(f.getPosition30(), "高低价相同时position30应为null");
        assertEquals(0, f.getWidth30d().compareTo(BigDecimal.ZERO),
                "高低价相同时width30d应为0");
        assertNotNull(f.getMa1d(), "除position30外其它条件性指标仍应可计算");
        assertTrue(f.getStrategyReady(), "总bar数2880 -> 策略就绪");
        verify(feature15mDAO).upsertFeature(any(TornStockStrategyFeature15mDO.class));
    }

    // ==================== 历史不足 ====================

    @Test
    @DisplayName("特征构建_2879根bar_不就绪且30日相关指标不可计算")
    void buildFeatures_historyLessThan2880Bars_strategyReadyFalse() {
        LocalDateTime barStart = LocalDateTime.of(2026, 7, 24, 10, 0);
        TornStockMarketBar15mDO currentBar = buildUsableBar(barStart, new BigDecimal("100.00"));
        // 总bar数 = 2878(历史) + 1(当前) = 2879 < 2880
        List<TornStockMarketBar15mDO> historyBars = buildHistoryBars(barStart, BARS_30D - 2,
                new BigDecimal("100.00"));

        mockBarDao(barStart, currentBar, historyBars);

        List<TornStockStrategyFeature15mDO> features = featureBuildService.buildFeatures(barStart);

        assertEquals(1, features.size());
        TornStockStrategyFeature15mDO f = features.getFirst();
        assertFalse(f.getStrategyReady(),
                "总bar数2879 < 2880 -> 策略未就绪");
        assertEquals("INSUFFICIENT_HISTORY", f.getDataQualityReason());
        // 30日相关指标不可计算: ma30d/zscore30d必须为null
        assertNull(f.getMa30d(), "2879根bar不足30日窗口,ma30d应为null");
        assertNull(f.getZscore30d(), "ma30d为null则zscore30d应为null");
        // 短窗口指标仍按可计算结果保留
        assertNotNull(f.getMa1d(), "2879根bar满足1日窗口,ma1d应可计算");
        assertNotNull(f.getMa7d(), "2879根bar满足7日窗口,ma7d应可计算");
        assertNotNull(f.getReturn14d(), "2879根bar满足14日窗口,return14d应可计算");
        assertNotNull(f.getLow30d(), "30日高低按可用bar计算,low30d不应为空");
        assertNotNull(f.getHigh30d(), "30日高低按可用bar计算,high30d不应为空");
        verify(feature15mDAO).upsertFeature(any(TornStockStrategyFeature15mDO.class));
    }

    // ==================== 历史充足 ====================

    @Test
    @DisplayName("特征构建_历史充足2880个bar -> strategyReady为true")
    void buildFeatures_historySufficient2880Bars_strategyReadyTrue() {
        LocalDateTime barStart = LocalDateTime.of(2026, 7, 24, 10, 0);
        TornStockMarketBar15mDO currentBar = buildUsableBar(barStart, new BigDecimal("100.00"));
        // 总bar数 = 2879(历史) + 1(当前) = 2880 -> 刚好满足
        List<TornStockMarketBar15mDO> historyBars = buildHistoryBars(barStart, BARS_30D - 1,
                new BigDecimal("100.00"));

        mockBarDao(barStart, currentBar, historyBars);

        List<TornStockStrategyFeature15mDO> features = featureBuildService.buildFeatures(barStart);

        assertEquals(1, features.size());
        assertTrue(features.getFirst().getStrategyReady(),
                "总bar数2880 >= 2880 -> 策略就绪");
        assertNull(features.getFirst().getDataQualityReason());
    }

    @Test
    @DisplayName("特征构建_2880根连续bar_所有条件性指标非空且strategyReady为true")
    void buildFeatures_2880ConsecutiveBars_allConditionalMetricsNonNull() {
        LocalDateTime barStart = LocalDateTime.of(2026, 7, 24, 10, 0);
        BigDecimal lowPrice = new BigDecimal("100.00");
        BigDecimal highPrice = new BigDecimal("150.00");
        BigDecimal currentPrice = new BigDecimal("120.00");

        TornStockMarketBar15mDO currentBar = buildUsableBar(barStart, currentPrice);
        // 2879条历史bar + 当前bar = 2880根连续可用bar,价格跨档使high30d > low30d
        List<TornStockMarketBar15mDO> historyBars = IntStream.range(0, BARS_30D - 1)
                .mapToObj(i -> {
                    BigDecimal p = (i < 1439) ? lowPrice : highPrice;
                    return buildUsableBar(
                            barStart.minusMinutes(15L * (BARS_30D - 1 - i)), p);
                })
                .toList();

        mockBarDao(barStart, currentBar, historyBars);

        List<TornStockStrategyFeature15mDO> features = featureBuildService.buildFeatures(barStart);

        assertEquals(1, features.size());
        TornStockStrategyFeature15mDO f = features.getFirst();
        assertTrue(f.getStrategyReady(), "2880根连续bar -> 策略就绪");
        assertNull(f.getDataQualityReason());
        assertNotNull(f.getMa1d(), "ma1d应可计算");
        assertNotNull(f.getMa7d(), "ma7d应可计算");
        assertNotNull(f.getMa30d(), "ma30d应可计算");
        assertNotNull(f.getZscore1d(), "zscore1d应可计算");
        assertNotNull(f.getZscore7d(), "zscore7d应可计算");
        assertNotNull(f.getZscore30d(), "zscore30d应可计算");
        assertNotNull(f.getReturn6h(), "return6h应可计算");
        assertNotNull(f.getReturn1d(), "return1d应可计算");
        assertNotNull(f.getReturn7d(), "return7d应可计算");
        assertNotNull(f.getReturn14d(), "return14d应可计算");
        assertNotNull(f.getLow30d(), "low30d应可计算");
        assertNotNull(f.getHigh30d(), "high30d应可计算");
        assertNotNull(f.getWidth30d(), "width30d应可计算");
        assertNotNull(f.getPosition30(), "high30d > low30d,position30应可计算");
        assertNotNull(f.getPctAbove30dLow(), "pctAbove30dLow应可计算");
        assertNotNull(f.getPctBelow30dHigh(), "pctBelow30dHigh应可计算");
        verify(feature15mDAO).upsertFeature(any(TornStockStrategyFeature15mDO.class));
    }

    @Test
    @DisplayName("特征构建_2880根bar含15分钟缺口_指标按可计算结果保留且strategyReady为false")
    void buildFeatures_2880BarsWithGap_strategyReadyFalseNotConsecutive() {
        LocalDateTime barStart = LocalDateTime.of(2026, 7, 24, 10, 0);
        BigDecimal price = new BigDecimal("100.00");

        TornStockMarketBar15mDO currentBar = buildUsableBar(barStart, price);
        // 构造2879条历史bar,但跳过中间一个15分钟槽位(缺口),总bar数仍为2880
        List<TornStockMarketBar15mDO> historyBars = IntStream.range(0, BARS_30D - 1)
                .mapToObj(i -> {
                    // i从0..2878,时间 = barStart - 15*(i+1);跳过i=1000对应的槽位
                    long offsetSlots = i + 1 + (i >= 1000 ? 1 : 0);
                    return buildUsableBar(barStart.minusMinutes(15L * offsetSlots), price);
                })
                .toList();

        mockBarDao(barStart, currentBar, historyBars);

        List<TornStockStrategyFeature15mDO> features = featureBuildService.buildFeatures(barStart);

        assertEquals(1, features.size());
        TornStockStrategyFeature15mDO f = features.getFirst();
        assertFalse(f.getStrategyReady(), "窗口内存在15分钟缺口 -> 策略不就绪");
        assertEquals("HISTORY_NOT_CONSECUTIVE", f.getDataQualityReason(),
                "缺口应识别为历史不连续");
        // 缺口不影响按可用bar计算指标: 数量仍满足窗口
        assertNotNull(f.getMa1d(), "缺口后指标仍按可计算结果保留,ma1d应可计算");
        assertNotNull(f.getMa30d(), "缺口后指标仍按可计算结果保留,ma30d应可计算");
        assertNotNull(f.getReturn14d(), "缺口后指标仍按可计算结果保留,return14d应可计算");
        verify(feature15mDAO).upsertFeature(any(TornStockStrategyFeature15mDO.class));
    }

    // ==================== 当前bar不可用 ====================

    @Test
    @DisplayName("特征构建_当前bar不可用 -> 返回空列表")
    void buildFeatures_currentBarNotUsable_returnsEmptyList() {
        LocalDateTime barStart = LocalDateTime.of(2026, 7, 24, 10, 0);
        // 采样数不足 -> 不可用
        TornStockMarketBar15mDO currentBar = buildUsableBar(barStart, new BigDecimal("100.00"));
        currentBar.setSampleCount(5); // < 10 -> 不可用

        // selectByTimeRange不会被调用(当前桶bar不可用时buildSingleFeature返回null,features为空提前返回)
        // 仅mock当前桶查询,返回不可用bar使buildSingleFeature判定为null
        when(bar15mDAO.selectByBarStartTime(barStart, BUILD_VERSION))
                .thenReturn(List.of(currentBar));

        List<TornStockStrategyFeature15mDO> features = featureBuildService.buildFeatures(barStart);

        assertTrue(features.isEmpty(), "当前bar不可用时应返回空列表");
        verify(feature15mDAO, never()).upsertFeature(any());
    }

    // ==================== return计算 ====================

    @Test
    @DisplayName("特征构建_return6h/1d/7d/14d计算正确")
    void buildFeatures_returnCalculationCorrect() {
        LocalDateTime barStart = LocalDateTime.of(2026, 7, 24, 10, 0);
        BigDecimal currentPrice = new BigDecimal("200.00");
        BigDecimal pastPrice = new BigDecimal("100.00");

        TornStockMarketBar15mDO currentBar = buildUsableBar(barStart, currentPrice);
        // 构造足够多的历史bar覆盖14d窗口(1344),且全部价格为pastPrice
        // 需要总bar数 > BARS_14D(1344) 才能计算return14d
        List<TornStockMarketBar15mDO> historyBars = buildHistoryBars(barStart, BARS_14D, pastPrice);

        mockBarDao(barStart, currentBar, historyBars);

        List<TornStockStrategyFeature15mDO> features = featureBuildService.buildFeatures(barStart);

        assertEquals(1, features.size());
        TornStockStrategyFeature15mDO f = features.getFirst();
        // return = currentPrice / pastPrice - 1 = 200/100 - 1 = 1.0
        BigDecimal expectedReturn = BigDecimal.ONE;
        assertEquals(0, f.getReturn6h().compareTo(expectedReturn),
                "return6h = 200/100 - 1 = 1.0");
        assertEquals(0, f.getReturn1d().compareTo(expectedReturn),
                "return1d = 200/100 - 1 = 1.0");
        assertEquals(0, f.getReturn7d().compareTo(expectedReturn),
                "return7d = 200/100 - 1 = 1.0");
        assertEquals(0, f.getReturn14d().compareTo(expectedReturn),
                "return14d = 200/100 - 1 = 1.0");
    }

    // ==================== width30d计算 ====================

    @Test
    @DisplayName("特征构建_width30d计算正确")
    void buildFeatures_width30dCalculationCorrect() {
        LocalDateTime barStart = LocalDateTime.of(2026, 7, 24, 10, 0);
        BigDecimal lowPrice = new BigDecimal("100.00");
        BigDecimal highPrice = new BigDecimal("150.00");
        BigDecimal currentPrice = new BigDecimal("120.00");

        TornStockMarketBar15mDO currentBar = buildUsableBar(barStart, currentPrice);
        currentBar.setLowPrice(currentPrice);
        currentBar.setHighPrice(currentPrice);
        // 构造2879条历史bar: 前1439条lastPrice=100,后1440条lastPrice=150
        // low30d = 100, high30d = 150, width30d = (150-100)/100 = 0.5
        List<TornStockMarketBar15mDO> historyBars = IntStream.range(0, BARS_30D - 1)
                .mapToObj(i -> {
                    BigDecimal p = (i < 1439) ? lowPrice : highPrice;
                    TornStockMarketBar15mDO b = buildUsableBar(
                            barStart.minusMinutes(15L * (BARS_30D - 1 - i)), p);
                    b.setLowPrice(p);
                    b.setHighPrice(p);
                    return b;
                })
                .toList();

        mockBarDao(barStart, currentBar, historyBars);

        List<TornStockStrategyFeature15mDO> features = featureBuildService.buildFeatures(barStart);

        assertEquals(1, features.size());
        TornStockStrategyFeature15mDO f = features.getFirst();
        assertEquals(0, f.getLow30d().compareTo(lowPrice), "low30d应为100");
        assertEquals(0, f.getHigh30d().compareTo(highPrice), "high30d应为150");
        BigDecimal expectedWidth = new BigDecimal("0.5");
        assertEquals(0, f.getWidth30d().compareTo(expectedWidth),
                "width30d = (150-100)/100 = 0.5");
        assertTrue(f.getStrategyReady());
        // position30 = (120-100)/(150-100) = 0.4
        BigDecimal expectedPosition = new BigDecimal("0.4");
        assertEquals(0, f.getPosition30().compareTo(expectedPosition),
                "position30 = (120-100)/(150-100) = 0.4");
    }

    // ==================== 回填后重算 ====================

    @Test
    @DisplayName("特征重算_更新前序bar后_后续MA/收益重新计算并UPSERT")
    void buildFeatures_recomputeAfterPredecessorBarUpdate_reflectsUpdatedInput() {
        LocalDateTime barStart = LocalDateTime.of(2026, 7, 24, 10, 0);
        TornStockMarketBar15mDO currentBar = buildUsableBar(barStart, new BigDecimal("200.00"));
        // 第一轮: 96条历史bar价格100
        List<TornStockMarketBar15mDO> oldHistory = buildHistoryBars(barStart, BARS_PER_DAY,
                new BigDecimal("100.00"));
        mockBarDao(barStart, currentBar, oldHistory);
        List<TornStockStrategyFeature15mDO> first = featureBuildService.buildFeatures(barStart);
        BigDecimal oldMa1d = first.getFirst().getMa1d();

        // 第二轮: 前序bar回填更新为价格150,再次重算必须反映更新后的输入
        List<TornStockMarketBar15mDO> newHistory = buildHistoryBars(barStart, BARS_PER_DAY,
                new BigDecimal("150.00"));
        mockBarDao(barStart, currentBar, newHistory);
        List<TornStockStrategyFeature15mDO> second = featureBuildService.buildFeatures(barStart);

        BigDecimal expectedNewMa1d = new BigDecimal("150.00").multiply(new BigDecimal("95"))
                .add(new BigDecimal("200.00"))
                .divide(new BigDecimal("96"), 18, java.math.RoundingMode.HALF_UP);
        assertEquals(0, expectedNewMa1d.compareTo(second.getFirst().getMa1d()),
                "重算后ma1d必须反映更新后的前序bar");
        assertEquals(0, oldMa1d.compareTo(new BigDecimal("100.00").multiply(new BigDecimal("95"))
                        .add(new BigDecimal("200.00"))
                        .divide(new BigDecimal("96"), 18, java.math.RoundingMode.HALF_UP)),
                "首轮ma1d应基于旧前序bar");
        verify(feature15mDAO, times(2)).upsertFeature(any(TornStockStrategyFeature15mDO.class));
    }

    // ==================== 辅助方法 ====================

    /**
     * 构造一个可用的bar(采样数=15,尾部新鲜)
     *
     * @param barStart  桶开始时间
     * @param lastPrice 收盘价(同时作为OHLC)
     * @return 可用的bar
     */
    private static TornStockMarketBar15mDO buildUsableBar(LocalDateTime barStart, BigDecimal lastPrice) {
        LocalDateTime barEnd = barStart.plusMinutes(15);
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(STOCKS_ID);
        bar.setStocksShortname(SHORTNAME);
        bar.setBarStartTime(barStart);
        bar.setBarEndTime(barEnd);
        bar.setFirstSampleTime(barStart.plusMinutes(0));
        bar.setLastSampleTime(barEnd.minusMinutes(1));
        bar.setFirstPrice(lastPrice);
        bar.setLastPrice(lastPrice);
        bar.setLowPrice(lastPrice);
        bar.setHighPrice(lastPrice);
        bar.setSampleCount(15);
        bar.setDuplicateCount(0);
        bar.setUsable(true);
        bar.setBuildVersion(BUILD_VERSION);
        return bar;
    }

    /**
     * 构造连续的历史bar列表(不含当前桶),每条bar价格相同,时间从远到近
     *
     * @param currentBarStart 当前桶开始时间
     * @param count           历史bar数量
     * @param price           每条bar的价格(OHLC相同)
     * @return 历史bar列表(按时间升序)
     */
    private static List<TornStockMarketBar15mDO> buildHistoryBars(LocalDateTime currentBarStart,
                                                                  int count, BigDecimal price) {
        return IntStream.range(0, count)
                .mapToObj(i -> {
                    // 第i条历史bar在 currentBarStart - (count-i)*15min 处
                    LocalDateTime histBarStart = currentBarStart
                            .minusMinutes(15L * (count - i));
                    return buildUsableBar(histBarStart, price);
                })
                .toList();
    }

    /**
     * Mock bar15mDAO的当前桶查询与历史范围查询
     * <p>
     * selectByBarStartTime(barStart, BUILD_VERSION)返回currentBar;
     * selectByTimeRange(historySince, barStart-15min, BUILD_VERSION)返回historyBars。
     * historyBars会被服务按股票分组后与当前bar拼接,需保证时间早于当前桶。
     *
     * @param barStart    当前桶开始时间
     * @param currentBar  当前bar
     * @param historyBars 历史bar列表
     */
    private void mockBarDao(LocalDateTime barStart, TornStockMarketBar15mDO currentBar,
                            List<TornStockMarketBar15mDO> historyBars) {
        LocalDateTime historySince = barStart.minusDays(30).minusMinutes(15);
        when(bar15mDAO.selectByBarStartTime(barStart, BUILD_VERSION))
                .thenReturn(List.of(currentBar));
        when(bar15mDAO.selectByTimeRange(historySince, barStart.minusMinutes(15), BUILD_VERSION))
                .thenReturn(historyBars);
    }
}
