package pn.torn.goldeneye.repository.dao.torn.stocks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import pn.torn.goldeneye.configuration.socket.BaseWithoutSocketTest;
import pn.torn.goldeneye.repository.model.torn.stocks.StockPricePoint;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 股票策略特征价格点Mapper测试。
 *
 * <p>验证旧特征计算读取历史价格点时，Mapper输出字段能完整映射至
 * {@link StockPricePoint}的六参数record构造器，避免新增历史记录ID后发生构造器参数不匹配。</p>
 *
 * @author Bai
 * @version 1.2.13
 * @since 2026.08.05
 */
@SpringBootTest
@DisplayName("股票策略特征价格点Mapper测试")
class TornStockStrategyFeatureDAOTest extends BaseWithoutSocketTest {

    @Autowired
    private TornStockStrategyFeatureDAO featureDao;
    @Autowired
    private TornStocksHistoryDAO stocksHistoryDao;

    /**
     * 验证查询历史价格点时完整映射原始记录ID及全部record构造参数。
     */
    @Test
    @DisplayName("查询历史价格点_完整映射含原始记录ID的六参数record")
    void selectHistoryPointsBetween_mapsAllStockPricePointArguments() {
        List<LocalDateTime> recordTimes = stocksHistoryDao.getLatestTwoRecordTimes();
        assertFalse(recordTimes.isEmpty(), "测试数据库必须存在股票历史采样");

        LocalDateTime endTime = recordTimes.getFirst();
        List<StockPricePoint> points = featureDao.selectHistoryPointsBetween(endTime.minusMinutes(1), endTime);

        assertFalse(points.isEmpty(), "最新采样分钟必须可查询到股票历史价格点");
        StockPricePoint point = points.getFirst();
        assertNotNull(point.id());
        assertNotNull(point.stocksId());
        assertNotNull(point.stocksShortname());
        assertNotNull(point.price());
        assertNotNull(point.investors());
        assertNotNull(point.time());
    }
}
