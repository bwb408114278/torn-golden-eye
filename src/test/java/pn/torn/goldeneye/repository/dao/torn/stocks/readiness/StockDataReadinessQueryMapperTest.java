package pn.torn.goldeneye.repository.dao.torn.stocks.readiness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksHistoryDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.GapSummary;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.backfill.StockHistoryDataSourceEnum;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    private TornStockMarketBar15mDAO bar15mDao;

    private static final LocalDateTime WIN_START = LocalDateTime.of(2099, 5, 1, 0, 0);
    private static final LocalDateTime WIN_END = WIN_START.plusDays(1);

    @Test
    @DisplayName("真实PG_空范围全部统计返回0而非null")
    void emptyRange_returnsZeroNotNull() {
        assertEquals(0L, queryDao.selectValidMinuteCount(WIN_START, WIN_END));
        assertEquals(0L, queryDao.selectDuplicateMinuteGroupCount(WIN_START, WIN_END));
        assertEquals(0L, queryDao.selectInvalidMinuteCount(WIN_START, WIN_END));
        assertEquals(0L, queryDao.selectBarCount(WIN_START, WIN_END, Stock15mBarBuildService.BUILD_VERSION));
        assertEquals(0L, queryDao.selectFeatureCount(WIN_START, WIN_END, "1.0.0"));
        GapSummary gap = queryDao.selectGapSummary(WIN_START, WIN_END);
        assertNotNull(gap);
        assertEquals(0L, gap.gapSegmentCount());
        assertEquals(0L, gap.maxGapMinutes());
    }

    @Test
    @DisplayName("真实PG_分钟范围左闭右开且逻辑删除不计入")
    void minuteRange_isHalfOpenAndDeletedExcluded() {
        int stocksId = 998101;
        insertHistory(stocksId, WIN_START);
        insertHistory(stocksId, WIN_END.minusMinutes(1));
        insertHistory(stocksId, WIN_END);
        TornStocksHistoryDO deleted = history(stocksId, WIN_START.plusMinutes(1));
        deleted.setDeleted(1);
        stocksHistoryDao.save(deleted);

        assertEquals(2L, queryDao.selectValidMinuteCount(WIN_START, WIN_END), "终点本身排除且deleted=1不计入");
        assertEquals(2L, queryDao.selectStockMinuteBoundaries(WIN_START, WIN_END).getFirst().minuteCount());
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
        bar.setUsable(usable);
        bar.setBuildVersion(version);
        return bar;
    }
}
