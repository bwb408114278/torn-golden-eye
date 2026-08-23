package pn.torn.goldeneye.torn.service.stocks.alert.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 15分钟bar Mapper真实PostgreSQL集成测试。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@SpringBootTest
@Tag("shared-db")
@Transactional
@Rollback
@DisplayName("15分钟bar Mapper真实PostgreSQL集成测试")
class TornStockMarketBar15mMapperTest {

    @Autowired
    private TornStockMarketBar15mDAO bar15mDao;

    private static final int TEST_STOCKS_ID = 2099201;
    private static final String TEST_SHORTNAME = "ITST";
    private static final LocalDateTime TEST_BAR_START = LocalDateTime.of(2099, 9, 1, 10, 0);
    private static final LocalDateTime TEST_BAR_END = TEST_BAR_START.plusMinutes(15);

    @Test
    @DisplayName("真实PG_批量UPSERT幂等更新且按股票范围读取")
    void upsertBars_idempotentAndReadable() {
        bar15mDao.upsertBars(List.of(bar(new BigDecimal("100.00"))));

        List<TornStockMarketBar15mDO> rows = bar15mDao.selectByStockAndTimeRange(
                TEST_STOCKS_ID, TEST_BAR_START, TEST_BAR_END, Stock15mBarBuildService.BUILD_VERSION);
        assertEquals(1, rows.size());
        assertEquals(0, rows.getFirst().getLastPrice().compareTo(new BigDecimal("100.00")));

        bar15mDao.upsertBars(List.of(bar(new BigDecimal("120.00"))));
        rows = bar15mDao.selectByStockAndTimeRange(
                TEST_STOCKS_ID, TEST_BAR_START, TEST_BAR_END, Stock15mBarBuildService.BUILD_VERSION);
        assertEquals(1, rows.size(), "同唯一键重复UPSERT不得产生重复行");
        assertEquals(0, rows.getFirst().getLastPrice().compareTo(new BigDecimal("120.00")),
                "重复UPSERT应更新已有bar");
    }

    @Test
    @DisplayName("真实PG_selectByStockAndTimeRange使用左闭右开_endExclusive本身不返回")
    void selectByStockAndTimeRange_isHalfOpenExclusiveEnd() {
        LocalDateTime start = TEST_BAR_START;
        LocalDateTime endExclusive = start.plusMinutes(15);
        LocalDateTime next = endExclusive.plusMinutes(15);
        bar15mDao.upsertBars(List.of(
                bar(start, new BigDecimal("100.00")),
                bar(endExclusive, new BigDecimal("200.00")),
                bar(next, new BigDecimal("300.00"))));

        List<TornStockMarketBar15mDO> rows = bar15mDao.selectByStockAndTimeRange(
                TEST_STOCKS_ID, start, endExclusive, Stock15mBarBuildService.BUILD_VERSION);
        assertEquals(1, rows.size(), "半开区间只应返回start桶");
        assertEquals(0, rows.getFirst().getLastPrice().compareTo(new BigDecimal("100.00")));
        assertEquals(start, rows.getFirst().getBarStartTime());
    }

    private TornStockMarketBar15mDO bar(LocalDateTime barStart, BigDecimal lastPrice) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(TEST_STOCKS_ID);
        bar.setStocksShortname(TEST_SHORTNAME);
        bar.setBarStartTime(barStart);
        bar.setBarEndTime(barStart.plusMinutes(15));
        bar.setFirstSampleTime(barStart.plusMinutes(1));
        bar.setLastSampleTime(barStart.plusMinutes(14));
        bar.setFirstPrice(lastPrice);
        bar.setLastPrice(lastPrice);
        bar.setLowPrice(lastPrice);
        bar.setHighPrice(lastPrice);
        bar.setSampleCount(10);
        bar.setDuplicateCount(0);
        bar.setTailGapSeconds(60);
        bar.setUsable(true);
        bar.setBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
        return bar;
    }

    private TornStockMarketBar15mDO bar(BigDecimal lastPrice) {
        return bar(TEST_BAR_START, lastPrice);
    }
}
