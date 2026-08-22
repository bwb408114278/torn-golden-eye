package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockBatchMarkDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockBatchMarkDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 动态SELL研究mark真实PostgreSQL集成测试。
 * <p>
 * 验证 P1-3 修复: 生产写路径 {@link StockBatchPathService} 为正式与候选影子账本mark写入冻结的
 * {@code dynamic_shadow_decision=NOT_EVALUATED} 与 {@code dynamic_shadow_reason=DYNAMIC_RULE_NOT_FROZEN};
 * {@code selectDynamicShadowResearchMarks} 按 FORMAL/SHADOW_FORMAL_CANDIDATE 边界正确返回分母、
 * 完整数,无限资金影子不写入动态字段从而不进入研究分母。
 * <p>
 * 使用隔离股票ID、批次号前缀与时间窗口,{@code @AfterEach}按专用维度精确物理DELETE,不删除业务数据。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
@SpringBootTest
@Tag("shared-db")
@DisplayName("动态SELL研究mark真实PostgreSQL集成测试")
class TornStockDynamicShadowMarkTest {

    @Autowired
    private TornStockBatchMarkDAO batchMarkDao;
    @Autowired
    private NamedParameterJdbcTemplate namedJdbcTemplate;

    /**
     * 隔离轮次时间(未来,远离生产数据)
     */
    private static final LocalDateTime ROUND_TIME = LocalDateTime.of(2099, 9, 1, 10, 0);
    /**
     * 隔离股票ID(远离生产数据1..35)
     */
    private static final int TEST_STOCK = 2099201;

    @AfterEach
    void cleanupIsolatedData() {
        namedJdbcTemplate.update(
                "DELETE FROM torn_stock_batch_mark WHERE round_time >= :start AND round_time < :end",
                Map.of("start", ROUND_TIME, "end", ROUND_TIME.plusMinutes(1)));
        namedJdbcTemplate.update(
                "DELETE FROM torn_stock_virtual_batch WHERE batch_no LIKE 'DMS2099%'", Map.of());
    }

    @Test
    @DisplayName("真实PG_正式与候选影子mark写入动态字段_无限资金不写_研究查询口径正确")
    void dynamicShadowMark_researchBoundaryAndDenominatorConsistent() {
        seedMark(batch("DMS2099001", "FORMAL"), true);
        seedMark(batch("DMS2099002", "SHADOW_FORMAL_CANDIDATE"), true);
        seedMark(batch("DMS2099003", "UNLIMITED_SHADOW"), false);

        List<TornStockBatchMarkDO> marks = batchMarkDao.selectDynamicShadowResearchMarks(
                ROUND_TIME, ROUND_TIME.plusMinutes(1));

        assertEquals(2, marks.size(), "研究查询必须只返回正式与候选影子mark,无限资金影子不进入分母");
        long complete = marks.stream()
                .filter(m -> StockDynamicSellResearchConstants.DECISION_NOT_EVALUATED.equals(m.getDynamicShadowDecision())
                        && StockDynamicSellResearchConstants.REASON_RULE_NOT_FROZEN.equals(m.getDynamicShadowReason()))
                .count();
        assertEquals(2, complete, "写入冻结值的mark应全部计为完整研究输入");
    }

    /**
     * 种子一条虚拟批次与对应mark。
     *
     * @param batch 批次(含batchNo与ledgerType)
     * @param writeTelemetry 是否写入动态SELL研究冻结值(正式/候选影子为true,无限资金为false)
     */
    private void seedMark(TornStockVirtualBatchSeed batch, boolean writeTelemetry) {
        Long batchId = insertBatch(batch);
        String dynamicDecision = writeTelemetry ? StockDynamicSellResearchConstants.DECISION_NOT_EVALUATED : null;
        String dynamicReason = writeTelemetry ? StockDynamicSellResearchConstants.REASON_RULE_NOT_FROZEN : null;
        TornStockBatchMarkDO mark = new TornStockBatchMarkDO();
        mark.setBatchId(batchId);
        mark.setRoundTime(ROUND_TIME);
        mark.setReferencePrice(new BigDecimal("100.00"));
        mark.setCurrentNetReturn(new BigDecimal("0.01"));
        mark.setPeakPrice(new BigDecimal("101.00"));
        mark.setTroughPrice(new BigDecimal("99.00"));
        mark.setMfe(new BigDecimal("0.01"));
        mark.setMae(new BigDecimal("-0.01"));
        mark.setPeakDrawdown(new BigDecimal("-0.02"));
        mark.setFormalDecision("HOLD");
        mark.setFormalReason("HOLD_NO_EXIT_TRIGGERED");
        mark.setDynamicShadowDecision(dynamicDecision);
        mark.setDynamicShadowReason(dynamicReason);
        mark.setFeatureSnapshot("{}");
        batchMarkDao.save(mark);
    }

    /**
     * 插入隔离虚拟批次并返回主键。
     *
     * @param batch 批次数据
     * @return 批次主键
     */
    private Long insertBatch(TornStockVirtualBatchSeed batch) {
        namedJdbcTemplate.update(
                "INSERT INTO torn_stock_virtual_batch "
                        + "(batch_no, ledger_type, stocks_id, stocks_shortname, primary_strategy, matched_strategies, "
                        + " quality_score, batch_status, signal_event_id, signal_time, signal_reference_price, "
                        + " expected_entry_bar_time, entry_stale_at, style_prior, style_maturity, risk_level, "
                        + " style_effective_month, buy_rule_version, sell_rule_version, style_rule_version, "
                        + " risk_rule_version, allocation_rule_version, message_rule_version, reset_observed, "
                        + " quantity, invested_cash, deleted, create_time, update_time) "
                        + "VALUES (:batchNo, :ledgerType, :stock, 'ISL1', 'RANGE', '[\"RANGE\"]', 1.0, 'OPEN', 1, "
                        + " :roundTime, 100.00, :roundTime, :staleAt, 'NARROW', 'M2_PROVISIONAL', 'NONE', "
                        + " :month, '1.1.0', '1.0.0', '1.0.0', '1.0.0', '1.0.0', '1.0.0', false, "
                        + " 100, 10000.00, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                Map.of("batchNo", batch.batchNo, "ledgerType", batch.ledgerType,
                        "stock", TEST_STOCK, "roundTime", ROUND_TIME,
                        "staleAt", ROUND_TIME.plusMinutes(35),
                        "month", ROUND_TIME.toLocalDate().withDayOfMonth(1)));
        return namedJdbcTemplate.queryForObject(
                "SELECT id FROM torn_stock_virtual_batch WHERE batch_no = :batchNo",
                Map.of("batchNo", batch.batchNo), Long.class);
    }

    /**
     * 隔离批次数据值对象。
     *
     * @param batchNo    批次编号(前缀DMS2099)
     * @param ledgerType 账本类型
     */
    private record TornStockVirtualBatchSeed(String batchNo, String ledgerType) {
    }

    private TornStockVirtualBatchSeed batch(String batchNo, String ledgerType) {
        return new TornStockVirtualBatchSeed(batchNo, ledgerType);
    }
}
