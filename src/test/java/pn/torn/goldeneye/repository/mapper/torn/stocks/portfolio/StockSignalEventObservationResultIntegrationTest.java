package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 拒绝观察结果码和数据缺口字段的PostgreSQL持久化集成测试。
 *
 * <p>测试使用事务回滚隔离数据，验证结果回写只更新尚未结算事件。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@SpringBootTest
@Transactional
@Rollback
@DisplayName("拒绝观察结果持久化PostgreSQL集成测试")
class StockSignalEventObservationResultIntegrationTest {

    private static final int TEST_STOCKS_ID = Integer.MAX_VALUE - 201;
    private static final String EVENT_NO = "OBS_RESULT_IT_001";
    private static final LocalDateTime ROUND_TIME = LocalDateTime.of(2001, 2, 1, 10, 0);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TornStockSignalEventMapper signalEventMapper;

    @Test
    @DisplayName("未结算事件_批量回写结果码和数据缺口标记")
    void unresolvedEvent_updatesObservationResultAndIncompleteFlag() {
        long eventId = insertEvent();
        TornStockSignalEventDO result = event(eventId, "NO_THEORETICAL_ENTRY", false,
                null, null, ROUND_TIME.plusMinutes(35));

        int updated = signalEventMapper.updateObservationResultsByIds(List.of(result));

        assertEquals(1, updated);
        assertEquals("NO_THEORETICAL_ENTRY", queryString(eventId, "observation_result"));
        assertFalse(queryBoolean(eventId, "observation_data_incomplete"));
        assertEquals(ROUND_TIME.plusMinutes(35), queryTime(eventId, "resolved_at"));
    }

    @Test
    @DisplayName("已结算事件_重复回写不覆盖原始观察结果")
    void resolvedEvent_doesNotOverwriteExistingObservationResult() {
        long eventId = insertEvent();
        TornStockSignalEventDO firstResult = event(eventId, "OBSERVATION_COMPLETED", true,
                new BigDecimal("0.05"), new BigDecimal("-0.02"), ROUND_TIME.plusDays(14));
        assertEquals(1, signalEventMapper.updateObservationResultsByIds(List.of(firstResult)));

        TornStockSignalEventDO secondResult = event(eventId, "OBSERVATION_DATA_INSUFFICIENT", false,
                null, null, ROUND_TIME.plusDays(15));
        int updated = signalEventMapper.updateObservationResultsByIds(List.of(secondResult));

        assertEquals(0, updated);
        assertEquals("OBSERVATION_COMPLETED", queryString(eventId, "observation_result"));
        assertTrue(queryBoolean(eventId, "observation_data_incomplete"));
        assertEquals(0, queryDecimal(eventId, "later_mfe").compareTo(new BigDecimal("0.05")));
    }

    private long insertEvent() {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO torn_stock_signal_event
                            (event_no, round_time, stocks_id, stocks_shortname, strategy_type,
                             signal_reference_price, buy_rule_version, quality_score,
                             feature_snapshot, style_snapshot, eligibility_result,
                             eligibility_reasons, portfolio_decision, reject_reason,
                             deleted, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?, ?,
                                0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        RETURNING id
                        """, Long.class, EVENT_NO, Timestamp.valueOf(ROUND_TIME), TEST_STOCKS_ID,
                "IT", "TEST_BUY", new BigDecimal("100.00"), "TEST_RULE", new BigDecimal("1.00"),
                "{}", "{}", "REJECTED", "{}", "REJECTED", "NO_AVAILABLE_SLOT");
    }

    private TornStockSignalEventDO event(long id, String resultCode, boolean incomplete,
                                         BigDecimal laterMfe, BigDecimal laterMae,
                                         LocalDateTime resolvedAt) {
        TornStockSignalEventDO event = new TornStockSignalEventDO();
        event.setId(id);
        event.setObservationResult(resultCode);
        event.setObservationDataIncomplete(incomplete);
        event.setLaterMfe(laterMfe);
        event.setLaterMae(laterMae);
        event.setResolvedAt(resolvedAt);
        return event;
    }

    private String queryString(long eventId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM torn_stock_signal_event WHERE id = ?",
                String.class, eventId);
    }

    private Boolean queryBoolean(long eventId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM torn_stock_signal_event WHERE id = ?",
                Boolean.class, eventId);
    }

    private LocalDateTime queryTime(long eventId, String column) {
        Timestamp timestamp = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM torn_stock_signal_event WHERE id = ?", Timestamp.class, eventId);
        return timestamp.toLocalDateTime();
    }

    private BigDecimal queryDecimal(long eventId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM torn_stock_signal_event WHERE id = ?",
                BigDecimal.class, eventId);
    }
}
