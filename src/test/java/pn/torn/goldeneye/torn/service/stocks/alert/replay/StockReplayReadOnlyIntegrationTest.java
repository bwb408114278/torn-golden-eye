package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 股票回放只读PostgreSQL集成测试。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@SpringBootTest
@Transactional
@Rollback
@DisplayName("股票回放只读PostgreSQL集成测试")
class StockReplayReadOnlyIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TornStockMarketBar15mDAO barDao;
    @Autowired
    private TornStockStrategyFeature15mDAO featureDao;

    @Test
    @DisplayName("固定时间范围读取_不改变正式股票表")
    void loadFixedRange_doesNotWriteFormalStockTables() {
        LocalDateTime start = LocalDateTime.of(2001, 1, 1, 0, 0);
        LocalDateTime end = start.plusDays(1);
        long beforeCount = count("torn_stock_market_bar_15m");
        String beforeUpdated = maxUpdated("torn_stock_market_bar_15m");

        List<Integer> stocksIds = List.of(Integer.MAX_VALUE - 999);
        barDao.selectByStocksAndTimeRange(stocksIds, start, end, "REPLAY_READ_ONLY_TEST");
        featureDao.selectLatestByStocksIds(stocksIds, end, "REPLAY_READ_ONLY_TEST");

        assertEquals(beforeCount, count("torn_stock_market_bar_15m"));
        assertEquals(beforeUpdated, maxUpdated("torn_stock_market_bar_15m"));
    }

    private long count(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
    }

    private String maxUpdated(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COALESCE(MAX(update_time)::text, '') FROM " + tableName,
                String.class);
    }
}
