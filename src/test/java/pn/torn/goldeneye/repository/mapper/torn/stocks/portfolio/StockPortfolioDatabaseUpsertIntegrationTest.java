package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 股票组合bar和特征表的PostgreSQL主键默认值及UPSERT集成测试。
 *
 * <p>测试直接使用与生产Mapper一致的UPSERT语句，验证数据库默认主键生成和冲突更新
 * 不会重新生成主键。测试事务结束后回滚，不保留测试数据。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.29
 */
@SpringBootTest
@Transactional
@Rollback
@DisplayName("股票组合PostgreSQL主键与UPSERT集成测试")
class StockPortfolioDatabaseUpsertIntegrationTest {

    private static final int TEST_STOCKS_ID = Integer.MAX_VALUE - 101;
    private static final String BAR_VERSION = "P0_DB_ID_BAR_TEST";
    private static final String FEATURE_VERSION = "P0_DB_ID_FEATURE_TEST";
    private static final LocalDateTime BAR_START_TIME = LocalDateTime.of(2001, 1, 1, 0, 0);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("数据库应为bar和特征表声明sequence默认主键")
    void databaseColumns_shouldUseSequenceDefaultsWithoutIdentity() {
        assertSequenceDefault("torn_stock_market_bar_15m");
        assertSequenceDefault("torn_stock_strategy_feature_15m");
    }

    @Test
    @DisplayName("bar首次UPSERT自动生成ID且冲突更新保留原ID")
    void barUpsert_shouldGenerateIdAndPreserveItOnConflict() {
        upsertBar(new BigDecimal("100.000000"));
        long firstId = queryBarId();
        assertTrue(firstId > 0, "首次bar UPSERT必须生成正数主键");

        upsertBar(new BigDecimal("101.000000"));
        long secondId = queryBarId();
        assertEquals(firstId, secondId, "bar冲突更新不能重新生成主键");
        assertEquals(0, queryBarLastPrice().compareTo(new BigDecimal("101.000000")),
                "bar冲突更新必须写入最新价格");
    }

    @Test
    @DisplayName("特征首次UPSERT自动生成ID且冲突更新保留原ID")
    void featureUpsert_shouldGenerateIdAndPreserveItOnConflict() {
        upsertFeature(new BigDecimal("100.000000"));
        long firstId = queryFeatureId();
        assertTrue(firstId > 0, "首次特征 UPSERT必须生成正数主键");

        upsertFeature(new BigDecimal("101.000000"));
        long secondId = queryFeatureId();
        assertEquals(firstId, secondId, "特征冲突更新不能重新生成主键");
        assertEquals(0, queryFeatureReferencePrice().compareTo(new BigDecimal("101.000000")),
                "特征冲突更新必须写入最新参考价");
    }

    private void assertSequenceDefault(String tableName) {
        String defaultValue = jdbcTemplate.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = 'id'",
                String.class, tableName);
        String identity = jdbcTemplate.queryForObject(
                "SELECT is_identity FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = 'id'",
                String.class, tableName);
        assertNotNull(defaultValue, tableName + ".id必须存在数据库默认值");
        assertTrue(defaultValue.contains("nextval"), tableName + ".id默认值必须使用sequence");
        assertFalse("YES".equalsIgnoreCase(identity), tableName + ".id不能同时声明identity和nextval默认值");
    }

    private void upsertBar(BigDecimal lastPrice) {
        jdbcTemplate.update("""
                        INSERT INTO torn_stock_market_bar_15m
                            (stocks_id, stocks_shortname, bar_start_time, bar_end_time,
                             first_sample_time, last_sample_time, first_price, last_price,
                             low_price, high_price, sample_count, duplicate_count, tail_gap_seconds,
                             usable, quality_reason, build_version, source_max_history_id,
                             deleted, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0,
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        ON CONFLICT (stocks_id, bar_start_time, build_version) WHERE deleted = 0
                        DO UPDATE SET last_price = EXCLUDED.last_price,
                                      update_time = CURRENT_TIMESTAMP
                        """,
                TEST_STOCKS_ID, "P0", Timestamp.valueOf(BAR_START_TIME),
                Timestamp.valueOf(BAR_START_TIME.plusMinutes(15)),
                Timestamp.valueOf(BAR_START_TIME), Timestamp.valueOf(BAR_START_TIME.plusMinutes(14)),
                new BigDecimal("99.000000"), lastPrice, new BigDecimal("98.000000"),
                new BigDecimal("102.000000"), 15, 0, 0, true, null, BAR_VERSION, null);
    }

    private long queryBarId() {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM torn_stock_market_bar_15m "
                        + "WHERE stocks_id = ? AND bar_start_time = ? AND build_version = ? AND deleted = 0",
                Long.class, TEST_STOCKS_ID, Timestamp.valueOf(BAR_START_TIME), BAR_VERSION);
        assertNotNull(id);
        return id;
    }

    private BigDecimal queryBarLastPrice() {
        return jdbcTemplate.queryForObject(
                "SELECT last_price FROM torn_stock_market_bar_15m "
                        + "WHERE stocks_id = ? AND bar_start_time = ? AND build_version = ? AND deleted = 0",
                BigDecimal.class, TEST_STOCKS_ID, Timestamp.valueOf(BAR_START_TIME), BAR_VERSION);
    }

    private void upsertFeature(BigDecimal referencePrice) {
        jdbcTemplate.update("""
                        INSERT INTO torn_stock_strategy_feature_15m
                            (stocks_id, stocks_shortname, bar_start_time, reference_price,
                             ma1d, ma7d, ma30d, zscore1d, zscore7d, zscore30d,
                             return6h, return1d, return7d, return14d,
                             low30d, high30d, width30d, position30,
                             pct_above_30d_low, pct_below_30d_high,
                             strategy_ready, data_quality_reason, feature_version,
                             deleted, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        ON CONFLICT (stocks_id, bar_start_time, feature_version) WHERE deleted = 0
                        DO UPDATE SET reference_price = EXCLUDED.reference_price,
                                      update_time = CURRENT_TIMESTAMP
                        """,
                TEST_STOCKS_ID, "P0", Timestamp.valueOf(BAR_START_TIME), referencePrice,
                new BigDecimal("100.000000"), new BigDecimal("100.000000"), new BigDecimal("100.000000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("90.000000"), new BigDecimal("110.000000"), new BigDecimal("20.000000"),
                new BigDecimal("0.500000"), new BigDecimal("0.1111111111"), new BigDecimal("0.0909090909"),
                true, null, FEATURE_VERSION);
    }

    private long queryFeatureId() {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM torn_stock_strategy_feature_15m "
                        + "WHERE stocks_id = ? AND bar_start_time = ? AND feature_version = ? AND deleted = 0",
                Long.class, TEST_STOCKS_ID, Timestamp.valueOf(BAR_START_TIME), FEATURE_VERSION);
        assertNotNull(id);
        return id;
    }

    private BigDecimal queryFeatureReferencePrice() {
        return jdbcTemplate.queryForObject(
                "SELECT reference_price FROM torn_stock_strategy_feature_15m "
                        + "WHERE stocks_id = ? AND bar_start_time = ? AND feature_version = ? AND deleted = 0",
                BigDecimal.class, TEST_STOCKS_ID, Timestamp.valueOf(BAR_START_TIME), FEATURE_VERSION);
    }
}
