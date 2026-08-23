package pn.torn.goldeneye.repository.dao.torn.stocks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.configuration.socket.BaseWithoutSocketTest;
import pn.torn.goldeneye.repository.model.torn.stocks.StockStrategyFeatureUpsert;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 旧分钟股票特征投资人数空值 Mapper 真实 PostgreSQL 测试。
 *
 * <p>验证 Tornsy 外部补数未提供投资人数时，生产 UPSERT 的首次插入和唯一键冲突更新
 * 均可持久化 {@code null}，不以零伪造未知人数。</p>
 *
 * @author Bai
 * @version 1.4.3
 * @since 2026.08.23
 */
@SpringBootTest
@Tag("shared-db")
@DisplayName("旧分钟股票特征投资人数空值Mapper真实PostgreSQL测试")
@Transactional
@Rollback
class TornStockStrategyFeatureInvestorsMapperTest extends BaseWithoutSocketTest {

    private static final int TEST_STOCKS_ID = 2_099_303;
    private static final String TEST_SHORTNAME = "IFIX";
    private static final LocalDateTime TEST_TIME = LocalDateTime.of(2099, 12, 1, 10, 0);
    private static final BigDecimal TEST_PRICE = new BigDecimal("100.00");

    @Autowired
    private TornStockStrategyFeatureDAO featureDao;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 验证首次插入和冲突更新均可保留外部历史源未知的投资人数。
     */
    @Test
    @DisplayName("真实PG_投资人数未知_首次插入和冲突更新均保持NULL")
    void batchUpsert_unknownInvestors_persistsNullOnInsertAndUpdate() {
        featureDao.batchUpsertFeatures(List.of(feature(100, 10)));
        featureDao.batchUpsertFeatures(List.of(feature(null, null)));

        List<Map<String, Object>> persisted = jdbcTemplate.queryForList(
                "SELECT latest_investors, investors_change_7d "
                        + "FROM torn_stock_strategy_feature "
                        + "WHERE stocks_id = ? AND feature_time = ? AND deleted = 0",
                TEST_STOCKS_ID, TEST_TIME);

        assertEquals(1, persisted.size());
        assertNull(persisted.getFirst().get("latest_investors"),
                "外部补数未提供人数必须持久化为NULL");
        assertNull(persisted.getFirst().get("investors_change_7d"),
                "当前或7日基准人数未知时变化量必须持久化为NULL");
    }

    /**
     * 构造除投资人数外完整的生产特征 UPSERT。
     *
     * @param latestInvestors   最后投资人数；允许 {@code null}
     * @param investorsChange7d 近7日投资人数变化；允许 {@code null}
     * @return 测试特征
     */
    private StockStrategyFeatureUpsert feature(Integer latestInvestors, Integer investorsChange7d) {
        return new StockStrategyFeatureUpsert(
                TEST_STOCKS_ID, TEST_SHORTNAME, TEST_TIME,
                TEST_PRICE, TEST_PRICE, TEST_PRICE, TEST_PRICE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, TEST_PRICE, TEST_PRICE,
                latestInvestors, investorsChange7d);
    }
}
