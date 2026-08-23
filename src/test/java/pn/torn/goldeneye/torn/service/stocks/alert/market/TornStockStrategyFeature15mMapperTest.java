package pn.torn.goldeneye.torn.service.stocks.alert.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 15分钟策略特征Mapper真实PostgreSQL集成测试。
 * <p>
 * 通过{@code @Transactional + @Rollback}回滚保证开发库零残留,不直接编写SQL,
 * 一律调用生产{@link TornStockStrategyFeature15mDAO}方法完成写入与回读。
 * 验证:
 * <ul>
 *   <li>生产{@code upsertFeature}接受{@code strategyReady=false/INSUFFICIENT_HISTORY}
 *       预热特征,窗口指标(ma、zscore、return 等以及 low30d/high30d、width30d、pct_above/below)
 *       为{@code null};</li>
 *   <li>回读生产Mapper结果,空值、{@code false}与质量原因保持原样,不允许被填充或改写;</li>
 *   <li>参考价、股票身份、bar时间与特征版本完整写入。</li>
 * </ul>
 * 使用远离生产数据的固定未来{@code bar_start_time}与专用{@code stocks_id}作为隔离数据。
 *
 * @author Bai
 * @version 1.2.17
 * @since 2026.08.14
 */
@SpringBootTest
@Tag("shared-db")
@Transactional
@Rollback
@DisplayName("15分钟策略特征Mapper真实PostgreSQL集成测试")
class TornStockStrategyFeature15mMapperTest {

    @Autowired
    private TornStockStrategyFeature15mDAO feature15mDao;

    /**
     * 隔离测试股票ID(远离生产数据)
     */
    private static final int TEST_STOCKS_ID = 2099101;
    /**
     * 隔离测试股票简称
     */
    private static final String TEST_SHORTNAME = "ITST";
    /**
     * 隔离测试bar时间(远端未来)
     */
    private static final LocalDateTime TEST_BAR_START_TIME = LocalDateTime.of(2099, 9, 1, 10, 0);
    /**
     * 测试特征版本(使用生产特征版本,验证生产契约)
     */
    private static final String TEST_FEATURE_VERSION = Stock15mFeatureBuildService.FEATURE_VERSION;
    /**
     * 测试参考价
     */
    private static final BigDecimal TEST_REFERENCE_PRICE = new BigDecimal("200.000000");

    @Test
    @DisplayName("真实PG_非就绪预热特征经生产UPSERT写入并完整回读")
    void upsertFeature_notReadyPrewarmFeature_roundTripPreservesNulls() {
        feature15mDao.upsertFeature(buildNotReadyFeature());

        List<TornStockStrategyFeature15mDO> rows = feature15mDao.selectByStocksAndTimeRange(
                List.of(TEST_STOCKS_ID), TEST_BAR_START_TIME, TEST_BAR_START_TIME, TEST_FEATURE_VERSION);

        assertEquals(1, rows.size(), "生产Mapper回读应恰好命中一行");
        TornStockStrategyFeature15mDO read = rows.getFirst();
        assertEquals(TEST_STOCKS_ID, read.getStocksId(), "股票ID应原样回读");
        assertEquals(TEST_SHORTNAME, read.getStocksShortname(), "股票简称应原样回读");
        assertEquals(TEST_BAR_START_TIME, read.getBarStartTime(), "bar开始时间应原样回读");
        assertEquals(TEST_FEATURE_VERSION, read.getFeatureVersion(), "特征版本应原样回读");
        assertEquals(0, TEST_REFERENCE_PRICE.compareTo(read.getReferencePrice()), "参考价应原样回读");
        assertFalse(read.getStrategyReady(), "strategyReady=false应原样回读");
        assertEquals("INSUFFICIENT_HISTORY", read.getDataQualityReason(), "质量原因应原样回读");
        assertNull(read.getMa1d(), "ma1d空值应原样回读");
        assertNull(read.getMa7d(), "ma7d空值应原样回读");
        assertNull(read.getMa30d(), "ma30d空值应原样回读");
        assertNull(read.getZscore1d(), "zscore1d空值应原样回读");
        assertNull(read.getZscore7d(), "zscore7d空值应原样回读");
        assertNull(read.getZscore30d(), "zscore30d空值应原样回读");
        assertNull(read.getReturn6h(), "return6h空值应原样回读");
        assertNull(read.getReturn1d(), "return1d空值应原样回读");
        assertNull(read.getReturn7d(), "return7d空值应原样回读");
        assertNull(read.getReturn14d(), "return14d空值应原样回读");
        assertNull(read.getLow30d(), "low30d空值应原样回读");
        assertNull(read.getHigh30d(), "high30d空值应原样回读");
        assertNull(read.getWidth30d(), "width30d空值应原样回读");
        assertNull(read.getPosition30(), "position30空值应原样回读");
        assertNull(read.getPctAbove30dLow(), "pctAbove30dLow空值应原样回读");
        assertNull(read.getPctBelow30dHigh(), "pctBelow30dHigh空值应原样回读");
    }

    /**
     * 构造{@code strategyReady=false/INSUFFICIENT_HISTORY}的预热特征对象。
     * <p>
     * 模拟生产预热期: 身份、参考价、版本、{@code strategyReady}完整;
     * 所有窗口指标(ma、zscore、return 等以及 low30d/high30d、width30d、position30、
     * pct_above/below)为{@code null}。
     *
     * @return 非就绪预热特征DO
     */
    private TornStockStrategyFeature15mDO buildNotReadyFeature() {
        TornStockStrategyFeature15mDO feature = new TornStockStrategyFeature15mDO();
        feature.setStocksId(TEST_STOCKS_ID);
        feature.setStocksShortname(TEST_SHORTNAME);
        feature.setBarStartTime(TEST_BAR_START_TIME);
        feature.setReferencePrice(TEST_REFERENCE_PRICE);
        feature.setStrategyReady(false);
        feature.setDataQualityReason("INSUFFICIENT_HISTORY");
        feature.setFeatureVersion(TEST_FEATURE_VERSION);
        return feature;
    }
}
