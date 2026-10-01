package pn.torn.goldeneye.torn.service.stocks.alert.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockAnnualSettlementStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioAnnualSettlementDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioAnnualSettlementDO;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;
import pn.torn.goldeneye.utils.image.render.html.PlaywrightBrowserManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * α年度结算台账Mapper真实PostgreSQL集成测试,覆盖单语句UPSERT、降级行补齐与数据库层守恒约束。
 * <p>
 * 测试数据全部落在测试专用年度(2099)并由 {@link Rollback} 回滚,不触碰任何生产年度台账行。
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@SpringBootTest
@Tag("shared-db")
@Transactional
@Rollback
@DisplayName("α年度结算台账Mapper真实PostgreSQL集成测试")
class TornStockPortfolioAnnualSettlementMapperTest {
    /**
     * 测试专用年度(与生产年度隔离)
     */
    private static final int TEST_SETTLE_YEAR = 2099;
    /**
     * 测试年度边界时点
     */
    private static final LocalDateTime TEST_BOUNDARY_TIME = LocalDateTime.of(2100, 1, 1, 0, 0);
    /**
     * 测试业务时间
     */
    private static final LocalDateTime TEST_BUSINESS_NOW = LocalDateTime.of(2100, 1, 1, 0, 5);

    @Autowired
    private TornStockPortfolioAnnualSettlementDAO settlementDao;
    /**
     * 表格图片渲染浏览器与年度结算台账的真实数据库行为无关,且需要下载/启动外部Chromium;
     * 测试上下文按既有集成测试口径以替身替换,不改变真实结算DAO与真实约束语义。
     */
    @MockitoBean
    private PlaywrightBrowserManager playwrightBrowserManager;

    @Test
    @DisplayName("真实PG_单语句UPSERT首次落库且已结算金额可回读")
    void upsertSettled_firstWrite_persistsAmounts() {
        assertEquals(1, settlementDao.upsertSettled(settledRow(), TEST_BUSINESS_NOW), "首次UPSERT必须落一行");

        TornStockPortfolioAnnualSettlementDO settled = readBack();
        assertNotNull(settled.getId());
        assertEquals(StockAnnualSettlementStatusEnum.SETTLED.getCode(), settled.getSettlementStatus());
        assertNull(settled.getDegradeReason(), "已结算行不得残留降级原因");
        assertEquals(0, new BigDecimal("10000000000.00").compareTo(settled.getClosingEquity()));
        assertEquals(0, BigDecimal.ZERO.compareTo(settled.getExtractedAmount()));
        assertEquals(365, settled.getCoverageDays());
    }

    @Test
    @DisplayName("真实PG_既有降级行UPSERT后翻转为已结算且清空降级原因")
    void upsertSettled_existingDegradedRow_flipsToSettled() {
        assertEquals(1, settlementDao.insertIgnoreConflict(
                degradedRow("边界行情缺失: TCC")), "降级行必须能落库且不带金额");

        assertEquals(1, settlementDao.upsertSettled(settledRow(), TEST_BUSINESS_NOW),
                "同业务唯一键必须命中DO UPDATE而不是放弃");

        TornStockPortfolioAnnualSettlementDO settled = readBack();
        assertEquals(StockAnnualSettlementStatusEnum.SETTLED.getCode(), settled.getSettlementStatus(),
                "降级后重试必须收敛为已结算");
        assertNull(settled.getDegradeReason(), "转为已结算必须清空历史降级原因");
        assertEquals(0, new BigDecimal("10000000000.00").compareTo(settled.getClosingEquity()));
        assertEquals(365, settled.getCoverageDays());
    }

    @Test
    @DisplayName("真实PG_已结算行缺少金额时被守恒CHECK约束拒绝")
    void upsertSettled_missingAmounts_rejectedByCheckConstraint() {
        TornStockPortfolioAnnualSettlementDO row = degradedRow("边界行情缺失: TCC");
        row.setSettlementStatus(StockAnnualSettlementStatusEnum.SETTLED.getCode());

        assertThrows(DataIntegrityViolationException.class,
                () -> settlementDao.upsertSettled(row, TEST_BUSINESS_NOW),
                "SETTLED行字段不齐必须被ck_annual_settlement_settled_fields拒绝");
    }

    @Test
    @DisplayName("真实PG_提取恒等式被数据库约束兜底")
    void upsertSettled_brokenExtractionIdentity_rejectedByCheckConstraint() {
        TornStockPortfolioAnnualSettlementDO row = pendingRow();
        fillSettledAmounts(row, new BigDecimal("10000000000.00"), new BigDecimal("100000000.00"));

        assertThrows(DataIntegrityViolationException.class,
                () -> settlementDao.upsertSettled(row, TEST_BUSINESS_NOW),
                "提取额与权益不满足恒等式必须被ck_annual_settlement_extraction_identity拒绝");
    }

    @Test
    @DisplayName("真实PG_已结算行不接受降级回写且金额与状态不变")
    void updateDegraded_settledRow_returnsZeroAndKeepsAmounts() {
        assertEquals(1, settlementDao.upsertSettled(settledRow(), TEST_BUSINESS_NOW));
        TornStockPortfolioAnnualSettlementDO settled = readBack();

        TornStockPortfolioAnnualSettlementDO degraded = new TornStockPortfolioAnnualSettlementDO();
        degraded.setId(settled.getId());
        degraded.setSettlementStatus(StockAnnualSettlementStatusEnum.DEGRADED_NOT_PROVABLE.getCode());
        degraded.setDegradeReason("降级不得覆盖已结算行");

        assertEquals(0, settlementDao.updateDegradedById(degraded, TEST_BUSINESS_NOW.plusMinutes(1)),
                "状态守卫必须让已结算行的降级回写返回0");

        TornStockPortfolioAnnualSettlementDO after = readBack();
        assertEquals(StockAnnualSettlementStatusEnum.SETTLED.getCode(), after.getSettlementStatus());
        assertNull(after.getDegradeReason());
        assertEquals(0, new BigDecimal("10000000000.00").compareTo(after.getClosingEquity()));
        assertEquals(365, after.getCoverageDays());
    }

    @Test
    @DisplayName("真实PG_冲突安全插入仍幂等且降级路径不写金额")
    void insertIgnoreConflict_idempotentAndKeepsDegradePath() {
        assertEquals(1, settlementDao.insertIgnoreConflict(pendingRow()), "首次插入必须落一行");
        assertEquals(0, settlementDao.insertIgnoreConflict(pendingRow()), "同业务唯一键重复插入必须被忽略");

        TornStockPortfolioAnnualSettlementDO persisted = readBack();
        assertEquals(StockAnnualSettlementStatusEnum.PENDING_BOUNDARY.getCode(), persisted.getSettlementStatus());
        assertNull(persisted.getClosingEquity(), "降级或阻断路径绝不写金额列");
    }

    /**
     * 按业务唯一键回读测试年度台账行。
     *
     * @return 台账行
     */
    private TornStockPortfolioAnnualSettlementDO readBack() {
        return settlementDao.selectByBusinessKey(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE,
                TEST_SETTLE_YEAR, StockAnnualSettlementService.RULE_VERSION);
    }

    /**
     * 构建金额与派生量齐全的已结算台账行(满足数据库层守恒约束)。
     *
     * @return 已结算台账行
     */
    private TornStockPortfolioAnnualSettlementDO settledRow() {
        TornStockPortfolioAnnualSettlementDO row = pendingRow();
        fillSettledAmounts(row, new BigDecimal("10000000000.00"), BigDecimal.ZERO);
        return row;
    }

    /**
     * 构建带降级原因的台账行。
     *
     * @param reason 降级原因
     * @return 降级台账行
     */
    private TornStockPortfolioAnnualSettlementDO degradedRow(String reason) {
        TornStockPortfolioAnnualSettlementDO row = pendingRow();
        row.setSettlementStatus(StockAnnualSettlementStatusEnum.DEGRADED_PRICE_MISSING.getCode());
        row.setDegradeReason(reason);
        return row;
    }

    /**
     * 填入满足提取恒等式的已结算金额。
     *
     * @param row             台账行
     * @param closingEquity   年末边界权益
     * @param extractedAmount 本年提取额
     */
    private void fillSettledAmounts(TornStockPortfolioAnnualSettlementDO row, BigDecimal closingEquity,
                                    BigDecimal extractedAmount) {
        row.setBoundaryBarStartTime(TEST_BOUNDARY_TIME.minusMinutes(15));
        row.setBoundaryBarDigest("test-digest");
        row.setInitialCash(new BigDecimal("10000000000.00"));
        row.setOpeningEquity(new BigDecimal("10000000000.00"));
        row.setClosingCash(new BigDecimal("10000000000.00"));
        row.setClosingReserved(BigDecimal.ZERO);
        row.setClosingMarketValue(BigDecimal.ZERO);
        row.setClosingEquity(closingEquity);
        row.setCumulativeExtractedBefore(BigDecimal.ZERO);
        row.setExtractedAmount(extractedAmount);
        row.setCumulativeExtractedAfter(extractedAmount);
        row.setYearReturn(BigDecimal.ZERO);
        row.setCoverageDays(365);
        row.setPartialYear(false);
        row.setOpenPositionCount(0);
        row.setSettlementStatus(StockAnnualSettlementStatusEnum.SETTLED.getCode());
    }

    /**
     * 构建测试用待结算台账行。
     *
     * @return 台账行
     */
    private TornStockPortfolioAnnualSettlementDO pendingRow() {
        TornStockPortfolioAnnualSettlementDO row = new TornStockPortfolioAnnualSettlementDO();
        row.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        row.setSettleYear(TEST_SETTLE_YEAR);
        row.setBoundaryTime(TEST_BOUNDARY_TIME);
        row.setRuleVersion(StockAnnualSettlementService.RULE_VERSION);
        row.setSettlementStatus(StockAnnualSettlementStatusEnum.PENDING_BOUNDARY.getCode());
        row.setLastAttemptAt(TEST_BUSINESS_NOW);
        return row;
    }
}
