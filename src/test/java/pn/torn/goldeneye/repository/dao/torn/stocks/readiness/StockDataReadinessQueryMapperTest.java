package pn.torn.goldeneye.repository.dao.torn.stocks.readiness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksHistoryDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.StockMinuteCoverage;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.StockMinuteCoverageSummary;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.backfill.StockHistoryDataSourceEnum;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据就绪只读查询 Mapper 真实 PostgreSQL 测试。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@SpringBootTest
@Tag("shared-db")
@DisplayName("数据就绪只读查询Mapper真实PostgreSQL测试")
@Transactional
@Rollback
class StockDataReadinessQueryMapperTest {

    @Autowired
    private StockDataReadinessQueryDAO queryDao;
    @Autowired
    private TornStocksHistoryDAO stocksHistoryDao;
    @Autowired
    private TornStocksDAO stocksDao;
    @Autowired
    private TornStockMarketBar15mDAO bar15mDao;

    private static final LocalDateTime WIN_START = LocalDateTime.of(2099, 5, 1, 0, 0);
    private static final LocalDateTime WIN_END = WIN_START.plusDays(1);

    @Test
    @DisplayName("真实PG_空范围覆盖统计返回全空缺口而非0")
    void emptyRange_returnsFullEmptyCoverage() {
        StockMinuteCoverageSummary summary = queryDao.selectMinuteCoverageSummary(WIN_START, WIN_END);
        assertNotNull(summary);
        assertTrue(summary.stockWithoutAnyMinuteCount() > 0, "未来空窗口应识别全空股票");
        assertTrue(summary.gapSegmentCount() > 0, "全空股票应计入缺口段数");
        assertTrue(summary.totalMissingStockMinutes() > 0, "全空股票应计入累计缺失分钟");
        assertEquals(0L, queryDao.selectValidMinuteCount(WIN_START, WIN_END));
        assertEquals(0L, queryDao.selectInvalidMinuteCount(WIN_START, WIN_END));
        assertEquals(0L, queryDao.selectBarCount(WIN_START, WIN_END, Stock15mBarBuildService.BUILD_VERSION));
        assertEquals(0L, queryDao.selectFeatureCount(WIN_START, WIN_END, "1.0.0"));
    }

    @Test
    @DisplayName("真实PG_分钟范围左闭右开且逻辑删除不计入")
    void minuteRange_isHalfOpenAndDeletedExcluded() {
        int stocksId = activeStockIds().get(0);
        insertHistory(stocksId, WIN_START);
        insertHistory(stocksId, WIN_END.minusMinutes(1));
        insertHistory(stocksId, WIN_END);
        TornStocksHistoryDO deleted = history(stocksId, WIN_START.plusMinutes(1));
        deleted.setDeleted(1);
        stocksHistoryDao.save(deleted);

        assertEquals(2L, queryDao.selectValidMinuteCount(WIN_START, WIN_END), "终点本身排除且deleted=1不计入");
        StockMinuteCoverage coverage = findCoverage(stocksId);
        assertEquals(2L, coverage.minuteCount(), "deleted=1不计入自然分钟");
        assertEquals(0L, coverage.leadingGapMinutes());
        assertEquals(0L, coverage.trailingGapMinutes());
    }

    @Test
    @DisplayName("真实PG_bar版本过滤_只统计指定版本")
    void barVersion_isFiltered() {
        int stocksId = 998201;
        LocalDateTime barStart = WIN_START;
        bar15mDao.upsertBars(List.of(
                bar(stocksId, barStart, Stock15mBarBuildService.BUILD_VERSION, true),
                bar(stocksId, barStart.plusMinutes(15), "9.9.9", true)));

        assertEquals(1L, queryDao.selectBarCount(WIN_START, WIN_END, Stock15mBarBuildService.BUILD_VERSION));
        assertEquals(1L, queryDao.selectUsableBarCount(WIN_START, WIN_END, Stock15mBarBuildService.BUILD_VERSION));
    }

    @Test
    @DisplayName("真实PG_覆盖汇总_全空/末分钟/中间缺口/0总股数")
    void coverageSummary_reportsEdgeAndEmptyGaps() {
        List<Integer> ids = activeStockIds();
        int empty = ids.get(0);
        int edge = ids.get(1);
        int internal = ids.get(2);
        int zeroShares = ids.get(3);
        insertHistory(edge, WIN_END.minusMinutes(1));
        insertHistory(internal, WIN_START.plusMinutes(1));
        insertHistory(internal, WIN_START.plusMinutes(4));
        TornStocksHistoryDO zero = history(zeroShares, WIN_START.plusMinutes(2));
        zero.setTotalShares(0L);
        stocksHistoryDao.save(zero);

        StockMinuteCoverageSummary summary = queryDao.selectMinuteCoverageSummary(WIN_START, WIN_END);
        StockMinuteCoverage emptyCoverage = findCoverage(summary, empty);
        assertNotNull(emptyCoverage);
        assertEquals(1440L, emptyCoverage.totalMissingMinutes());
        assertEquals(1440L, emptyCoverage.leadingGapMinutes());
        assertEquals(0L, emptyCoverage.trailingGapMinutes());
        assertTrue(summary.stockWithoutAnyMinuteCount() >= 1);

        StockMinuteCoverage edgeCoverage = findCoverage(summary, edge);
        assertEquals(1439L, edgeCoverage.leadingGapMinutes());
        assertEquals(0L, edgeCoverage.trailingGapMinutes());
        assertEquals(1439L, edgeCoverage.totalMissingMinutes());

        StockMinuteCoverage internalCoverage = findCoverage(summary, internal);
        assertEquals(1L, internalCoverage.leadingGapMinutes());
        assertEquals(1L, internalCoverage.internalGapSegmentCount(), "相邻+1和+4分钟之间是一个连续缺口段");
        assertEquals(2L, internalCoverage.internalMaxGapMinutes(), "该段缺口长度为2分钟");
        assertEquals(1435L, internalCoverage.trailingGapMinutes());
        assertEquals(1L + 2L + 1435L, internalCoverage.totalMissingMinutes());

        assertTrue(queryDao.selectValidMinuteCount(WIN_START, WIN_END) > 0);
        assertTrue(queryDao.selectInvalidMinuteCount(WIN_START, WIN_END) > 0);
    }

    private List<Integer> activeStockIds() {
        return stocksDao.list().stream()
                .map(pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO::getId)
                .sorted()
                .limit(4)
                .toList();
    }

    private StockMinuteCoverage findCoverage(int stocksId) {
        return findCoverage(queryDao.selectMinuteCoverageSummary(WIN_START, WIN_END), stocksId);
    }

    private StockMinuteCoverage findCoverage(StockMinuteCoverageSummary summary, int stocksId) {
        return summary.coverages().stream()
                .filter(c -> c.stocksId() == stocksId)
                .findFirst()
                .orElseThrow();
    }

    private void insertHistory(int stocksId, LocalDateTime minute) {
        stocksHistoryDao.save(history(stocksId, minute));
    }

    private TornStocksHistoryDO history(int stocksId, LocalDateTime minute) {
        TornStocksHistoryDO history = new TornStocksHistoryDO();
        history.setStocksId(stocksId);
        history.setStocksName("Test Stock " + stocksId);
        history.setStocksShortname("T" + stocksId % 100);
        history.setCurrentPrice(new BigDecimal("10.00"));
        history.setMarketCap(null);
        history.setTotalShares(1000000L);
        history.setInvestors(null);
        history.setRegDateTime(minute);
        history.setDataSource(StockHistoryDataSourceEnum.TORNSY_BACKFILL.getCode());
        return history;
    }

    private TornStockMarketBar15mDO bar(int stocksId, LocalDateTime barStart, String version, boolean usable) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(stocksId);
        bar.setStocksShortname("T" + stocksId % 100);
        bar.setBarStartTime(barStart);
        bar.setBarEndTime(barStart.plusMinutes(15));
        bar.setFirstSampleTime(barStart);
        bar.setLastSampleTime(barStart.plusMinutes(14));
        bar.setFirstPrice(new BigDecimal("10.00"));
        bar.setLastPrice(new BigDecimal("10.00"));
        bar.setLowPrice(new BigDecimal("10.00"));
        bar.setHighPrice(new BigDecimal("10.00"));
        bar.setSampleCount(15);
        bar.setDuplicateCount(0);
        bar.setTailGapSeconds(60);
        bar.setUsable(usable);
        bar.setBuildVersion(version);
        return bar;
    }
}
