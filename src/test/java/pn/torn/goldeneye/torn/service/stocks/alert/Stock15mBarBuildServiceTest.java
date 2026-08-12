package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockPricePoint;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 15分钟bar构建服务单元测试 - 覆盖桶对齐、可用性判定、连续性判定与bar构建的边界条件
 * <p>
 * 验证 {@link Stock15mBarBuildService} 的以下规则:
 * <ul>
 *   <li>{@code alignToBucket} 向下对齐到15分钟桶边界(00/15/30/45分)</li>
 *   <li>{@code isUsable} 同时满足 sampleCount >= 10 且 lastSampleTime >= barEnd - 5分钟</li>
 *   <li>{@code isConsecutive} 仅在时间紧邻且两者均可用时为true</li>
 *   <li>{@code buildBars} 去重(同一时间按最后一条保留)、计算OHLC、判定可用性并批量保存</li>
 * </ul>
 * 静态方法直接调用,实例方法通过Mock DAO验证交互。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("15分钟bar构建服务测试")
class Stock15mBarBuildServiceTest {

    @Mock
    private TornStocksHistoryDAO stocksHistoryDAO;

    @Mock
    private TornStockMarketBar15mDAO bar15mDAO;

    @InjectMocks
    private Stock15mBarBuildService barBuildService;

    // ==================== alignToBucket ====================

    @Test
    @DisplayName("桶对齐_各种时间正确对齐到15分钟边界")
    void alignToBucket_variousTimes_correctlyAlignTo15MinBoundary() {
        assertEquals(LocalDateTime.of(2026, 7, 24, 10, 0),
                Stock15mBarBuildService.alignToBucket(LocalDateTime.of(2026, 7, 24, 10, 7, 23)));
        assertEquals(LocalDateTime.of(2026, 7, 24, 10, 15),
                Stock15mBarBuildService.alignToBucket(LocalDateTime.of(2026, 7, 24, 10, 22, 59)));
        assertEquals(LocalDateTime.of(2026, 7, 24, 10, 30),
                Stock15mBarBuildService.alignToBucket(LocalDateTime.of(2026, 7, 24, 10, 38, 0)));
        assertEquals(LocalDateTime.of(2026, 7, 24, 10, 45),
                Stock15mBarBuildService.alignToBucket(LocalDateTime.of(2026, 7, 24, 10, 45, 0)));
    }

    // ==================== isUsable ====================

    @Test
    @DisplayName("可用性判定_采样数等于10且桶尾新鲜 -> 返回true")
    void isUsable_sampleCount10AndFreshBarEnd_returnsTrue() {
        LocalDateTime barEnd = LocalDateTime.of(2026, 7, 24, 10, 15);
        TornStockMarketBar15mDO bar = buildBar(10, barEnd.minusMinutes(3), barEnd);
        assertTrue(Stock15mBarBuildService.isUsable(bar));
    }

    @Test
    @DisplayName("可用性判定_采样数等于9 -> 返回false")
    void isUsable_sampleCount9_returnsFalse() {
        LocalDateTime barEnd = LocalDateTime.of(2026, 7, 24, 10, 15);
        TornStockMarketBar15mDO bar = buildBar(9, barEnd.minusMinutes(1), barEnd);
        assertFalse(Stock15mBarBuildService.isUsable(bar));
    }

    @Test
    @DisplayName("可用性判定_采样数足够但桶尾不新鲜 -> 返回false")
    void isUsable_sampleCountSufficientButBarEndNotFresh_returnsFalse() {
        LocalDateTime barEnd = LocalDateTime.of(2026, 7, 24, 10, 15);
        // lastSampleTime 比 barEnd-5min 还早 -> 不新鲜
        TornStockMarketBar15mDO bar = buildBar(15, barEnd.minusMinutes(6), barEnd);
        assertFalse(Stock15mBarBuildService.isUsable(bar));
    }

    @Test
    @DisplayName("可用性判定_最后采样恰好等于barEnd减5分钟 -> 返回true(边界包含)")
    void isUsable_lastSampleExactlyBarEndMinus5Min_returnsTrue() {
        LocalDateTime barEnd = LocalDateTime.of(2026, 7, 24, 10, 15);
        // lastSampleTime == barEnd - 5min -> isBefore(barEnd-5min) 为 false -> 满足
        TornStockMarketBar15mDO bar = buildBar(10, barEnd.minusMinutes(5), barEnd);
        assertTrue(Stock15mBarBuildService.isUsable(bar));
    }

    @Test
    @DisplayName("可用性判定_null参数 -> 返回false")
    void isUsable_nullArg_returnsFalse() {
        assertFalse(Stock15mBarBuildService.isUsable(null));
    }

    // ==================== isConsecutive ====================

    @Test
    @DisplayName("连续性判定_紧邻且均可用 -> 返回true")
    void isConsecutive_adjacentAndBothUsable_returnsTrue() {
        LocalDateTime barStart1 = LocalDateTime.of(2026, 7, 24, 10, 0);
        LocalDateTime barEnd1 = barStart1.plusMinutes(15);
        LocalDateTime barStart2 = barStart1.plusMinutes(15);
        LocalDateTime barEnd2 = barStart2.plusMinutes(15);
        TornStockMarketBar15mDO prev = buildBar(12, barEnd1.minusMinutes(1), barEnd1);
        prev.setBarStartTime(barStart1);
        TornStockMarketBar15mDO next = buildBar(11, barEnd2.minusMinutes(1), barEnd2);
        next.setBarStartTime(barStart2);
        assertTrue(Stock15mBarBuildService.isConsecutive(prev, next));
    }

    @Test
    @DisplayName("连续性判定_非紧邻 -> 返回false")
    void isConsecutive_notAdjacent_returnsFalse() {
        LocalDateTime barStart1 = LocalDateTime.of(2026, 7, 24, 10, 0);
        LocalDateTime barEnd1 = barStart1.plusMinutes(15);
        LocalDateTime barStart3 = barStart1.plusMinutes(30); // 隔了一个桶
        LocalDateTime barEnd3 = barStart3.plusMinutes(15);
        TornStockMarketBar15mDO prev = buildBar(12, barEnd1.minusMinutes(1), barEnd1);
        prev.setBarStartTime(barStart1);
        TornStockMarketBar15mDO next = buildBar(11, barEnd3.minusMinutes(1), barEnd3);
        next.setBarStartTime(barStart3);
        assertFalse(Stock15mBarBuildService.isConsecutive(prev, next));
    }

    @Test
    @DisplayName("连续性判定_紧邻但前一个不可用 -> 返回false")
    void isConsecutive_adjacentButPrevNotUsable_returnsFalse() {
        LocalDateTime barStart1 = LocalDateTime.of(2026, 7, 24, 10, 0);
        LocalDateTime barEnd1 = barStart1.plusMinutes(15);
        LocalDateTime barStart2 = barStart1.plusMinutes(15);
        LocalDateTime barEnd2 = barStart2.plusMinutes(15);
        // prev 采样数不足 -> 不可用
        TornStockMarketBar15mDO prev = buildBar(3, barEnd1.minusMinutes(1), barEnd1);
        prev.setBarStartTime(barStart1);
        TornStockMarketBar15mDO next = buildBar(11, barEnd2.minusMinutes(1), barEnd2);
        next.setBarStartTime(barStart2);
        assertFalse(Stock15mBarBuildService.isConsecutive(prev, next));
    }

    @Test
    @DisplayName("连续性判定_null参数 -> 返回false")
    void isConsecutive_nullArg_returnsFalse() {
        LocalDateTime barStart = LocalDateTime.of(2026, 7, 24, 10, 0);
        LocalDateTime barEnd = barStart.plusMinutes(15);
        TornStockMarketBar15mDO usable = buildBar(12, barEnd.minusMinutes(1), barEnd);
        usable.setBarStartTime(barStart);
        assertFalse(Stock15mBarBuildService.isConsecutive(null, usable));
        assertFalse(Stock15mBarBuildService.isConsecutive(usable, null));
        assertFalse(Stock15mBarBuildService.isConsecutive(null, null));
    }

    // ==================== buildBars ====================

    @Test
    @DisplayName("构建bar_正常构建去重并判定可用性")
    void buildBars_normalBuild_dedupAndJudgeUsability() {
        LocalDateTime barStart = LocalDateTime.of(2026, 7, 24, 10, 0);
        LocalDateTime barEnd = barStart.plusMinutes(15);
        // 10个不同时间的采样(最后采样在桶尾5分钟内以满足新鲜度),再加2条重复时间(应被去重)
        // 采样时间从10:05到10:14,确保lastSampleTime(10:14) >= barEnd(10:15) - 5min(10:10)
        List<StockPricePoint> points = buildMinutesForStock(1, "TST", barStart.plusMinutes(5), 10, 0);
        // 追加2条重复: 与第1、第2分钟同时间,价格不同 -> 去重后保留最后一条
        points.add(buildPoint(1, "TST", new BigDecimal("999.00"), barStart.plusMinutes(5)));
        points.add(buildPoint(1, "TST", new BigDecimal("888.00"), barStart.plusMinutes(6)));

        when(stocksHistoryDAO.selectHistoryPointsRange(barStart, barEnd)).thenReturn(points);

        List<TornStockMarketBar15mDO> bars = barBuildService.buildBars(barStart);

        assertEquals(1, bars.size());
        TornStockMarketBar15mDO bar = bars.getFirst();
        assertEquals(1, bar.getStocksId());
        assertEquals(barStart, bar.getBarStartTime());
        assertEquals(barEnd, bar.getBarEndTime());
        assertEquals(10, bar.getSampleCount(), "去重后应剩10条");
        assertEquals(2, bar.getDuplicateCount(), "应有2条重复被去除");
        assertTrue(bar.getUsable(), "10样本+尾部新鲜 -> 可用");
        assertNull(bar.getQualityReason());
        // 重复时间保留最后一条: 第1分钟价格应为999(后插入的覆盖)
        assertEquals(0, bar.getFirstPrice().compareTo(new BigDecimal("999.00")),
                "去重后第1分钟价格应为最后插入的999");
        verify(bar15mDAO, atLeastOnce()).upsertBar(any(TornStockMarketBar15mDO.class));
    }

    @Test
    @DisplayName("构建bar_桶首缺失但后续满足 -> 可用")
    void buildBars_barHeadMissingButSubsequentSatisfies_usable() {
        LocalDateTime barStart = LocalDateTime.of(2026, 7, 24, 10, 0);
        LocalDateTime barEnd = barStart.plusMinutes(15);
        // 从第2分钟开始采样到第14分钟,共13条(>=10),最后采样14分钟满足尾部新鲜
        List<StockPricePoint> points = buildMinutesForStock(2, "ABC", barStart.plusMinutes(2), 13, 0);

        when(stocksHistoryDAO.selectHistoryPointsRange(barStart, barEnd)).thenReturn(points);

        List<TornStockMarketBar15mDO> bars = barBuildService.buildBars(barStart);

        assertEquals(1, bars.size());
        TornStockMarketBar15mDO bar = bars.getFirst();
        assertEquals(13, bar.getSampleCount());
        assertEquals(barStart.plusMinutes(2), bar.getFirstSampleTime(),
                "桶首缺失时firstSampleTime应为第一条实际采样");
        assertEquals(barStart.plusMinutes(14), bar.getLastSampleTime());
        assertTrue(bar.getUsable(), "13样本且尾部新鲜 -> 可用");
    }

    @Test
    @DisplayName("构建bar_无采样数据 -> 返回空列表")
    void buildBars_noSampleData_returnsEmptyList() {
        LocalDateTime barStart = LocalDateTime.of(2026, 7, 24, 10, 0);
        LocalDateTime barEnd = barStart.plusMinutes(15);

        when(stocksHistoryDAO.selectHistoryPointsRange(barStart, barEnd)).thenReturn(List.of());

        List<TornStockMarketBar15mDO> bars = barBuildService.buildBars(barStart);

        assertTrue(bars.isEmpty());
        verify(bar15mDAO, never()).saveBatch(any());
    }

    // ==================== 辅助方法 ====================

    /**
     * 构造一个仅含可用性相关字段的bar
     *
     * @param sampleCount    采样数
     * @param lastSampleTime 最后采样时间
     * @param barEndTime     桶结束时间
     * @return 构造的bar
     */
    private static TornStockMarketBar15mDO buildBar(int sampleCount, LocalDateTime lastSampleTime,
                                                    LocalDateTime barEndTime) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(1);
        bar.setStocksShortname("TST");
        bar.setBarStartTime(barEndTime.minusMinutes(15));
        bar.setBarEndTime(barEndTime);
        bar.setFirstSampleTime(lastSampleTime);
        bar.setLastSampleTime(lastSampleTime);
        bar.setFirstPrice(new BigDecimal("100.00"));
        bar.setLastPrice(new BigDecimal("100.00"));
        bar.setLowPrice(new BigDecimal("100.00"));
        bar.setHighPrice(new BigDecimal("100.00"));
        bar.setSampleCount(sampleCount);
        bar.setDuplicateCount(0);
        return bar;
    }

    /**
     * 构造一条StockPricePoint
     *
     * @param stocksId  股票ID
     * @param shortname 股票简称
     * @param price     价格
     * @param time      采样时间
     * @return 构造的价格点
     */
    private static StockPricePoint buildPoint(int stocksId, String shortname, BigDecimal price,
                                              LocalDateTime time) {
        return new StockPricePoint(null, stocksId, shortname, price, 1000, time);
    }

    /**
     * 为单支股票构造连续分钟采样列表,价格按100递增
     *
     * @param stocksId  股票ID
     * @param shortname 股票简称
     * @param startTime 第一条采样时间(含)
     * @param count     采样数量
     * @param dupCount  额外重复采样数量(与已有同时间,不重复计入唯一数)
     * @return 采样列表
     */
    private static List<StockPricePoint> buildMinutesForStock(int stocksId, String shortname,
                                                              LocalDateTime startTime, int count, int dupCount) {
        List<StockPricePoint> points = IntStream.range(0, count)
                .mapToObj(i -> buildPoint(stocksId, shortname,
                        new BigDecimal(100 + i + ".00"),
                        startTime.plusMinutes(i)))
                .collect(Collectors.toList());
        // 追加重复时间采样
        for (int i = 0; i < dupCount; i++) {
            points.add(buildPoint(stocksId, shortname,
                    new BigDecimal("500.00"),
                    startTime.plusMinutes(i % count)));
        }
        return points;
    }
}
