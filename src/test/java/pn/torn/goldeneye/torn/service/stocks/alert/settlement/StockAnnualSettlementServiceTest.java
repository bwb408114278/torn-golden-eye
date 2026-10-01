package pn.torn.goldeneye.torn.service.stocks.alert.settlement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockAnnualSettlementStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRoundStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.*;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.*;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.PortfolioEquityCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * α年度结算服务编排测试,覆盖幂等短路、三道门禁、累计提取连续性与单一短事务落库。
 * <p>
 * 金额与区间派生量的穷举边界由 {@link StockAnnualSettlementCalculatorTest} 保护,本类只验证编排、
 * 状态落地与通知建行接线,不重复纯函数的全部边界。
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@DisplayName("α年度结算服务测试")
@ExtendWith(MockitoExtension.class)
class StockAnnualSettlementServiceTest {
    /**
     * 固定业务时间(年度边界当日00:05)
     */
    private static final LocalDateTime BUSINESS_NOW = LocalDateTime.of(2027, 1, 1, 0, 5);
    /**
     * 首笔α批次入场时间(区间起点)
     */
    private static final LocalDateTime EARLIEST_ENTRY_TIME = LocalDateTime.of(2026, 9, 17, 16, 15);

    @Mock
    private TornStockPortfolioAnnualSettlementDAO settlementDao;

    @Mock
    private TornStockPortfolioSlotDAO slotDao;

    @Mock
    private TornStockVirtualBatchDAO batchDao;

    @Mock
    private TornStockMarketRoundDAO roundDao;

    @Mock
    private TornStockMarketBar15mDAO barDao;

    @Mock
    private StockAnnualSettlementNoticeService noticeService;

    private StockAnnualSettlementService service;

    @BeforeEach
    void setUp() {
        service = new StockAnnualSettlementService(settlementDao, slotDao, batchDao, roundDao, barDao,
                new PortfolioEquityCalculator(new StockPortfolioService()), new StockAnnualSettlementCalculator(),
                new StockAnnualSettlementRenderer(), noticeService);
    }

    @Test
    @DisplayName("已结算年度_幂等短路且不读槽位不重复提取")
    void settle_alreadySettled_shortCircuits() {
        when(settlementDao.selectByBusinessKey(anyString(), anyInt(), anyString()))
                .thenReturn(settlementRow(9L, StockAnnualSettlementStatusEnum.SETTLED));

        assertNull(service.settle(2026, BUSINESS_NOW));

        verify(settlementDao, never()).updateSettledById(any(), any());
        verifyNoInteractions(slotDao, noticeService);
    }

    @Test
    @DisplayName("正式仓槽位缺失_落人工核验且不写金额不建通知")
    void settle_missingSlots_degradesToManualReview() {
        when(settlementDao.selectByBusinessKey(anyString(), anyInt(), anyString())).thenReturn(null);
        when(slotDao.selectAllByPortfolioCode(anyString())).thenReturn(List.of());
        when(settlementDao.insertIgnoreConflict(any())).thenReturn(1);

        assertNull(service.settle(2026, BUSINESS_NOW));

        assertEquals(StockAnnualSettlementStatusEnum.MANUAL_REVIEW.getCode(),
                capturedInsertedRow().getSettlementStatus());
        verify(settlementDao, never()).updateSettledById(any(), any());
        verifyNoInteractions(noticeService);
    }

    @Test
    @DisplayName("边界桶轮次未完成_落待边界就绪等待窗口内重试")
    void settle_boundaryRoundNotCompleted_waitsWithPendingBoundary() {
        when(settlementDao.selectByBusinessKey(anyString(), anyInt(), anyString())).thenReturn(null);
        when(slotDao.selectAllByPortfolioCode(anyString())).thenReturn(List.of(alphaSlot()));
        when(roundDao.selectByRoundTime(boundaryBarStart(2026))).thenReturn(null);
        when(settlementDao.insertIgnoreConflict(any())).thenReturn(1);

        assertNull(service.settle(2026, BUSINESS_NOW));

        TornStockPortfolioAnnualSettlementDO row = capturedInsertedRow();
        assertEquals(StockAnnualSettlementStatusEnum.PENDING_BOUNDARY.getCode(), row.getSettlementStatus());
        assertNotNull(row.getDegradeReason());
        verify(settlementDao, never()).updateSettledById(any(), any());
        verifyNoInteractions(noticeService);
    }

    @Test
    @DisplayName("边界后已发生资金变动_落不可证终态且不写金额")
    void settle_postBoundaryChange_degradesNotProvable() {
        when(settlementDao.selectByBusinessKey(anyString(), anyInt(), anyString())).thenReturn(null);
        when(slotDao.selectAllByPortfolioCode(anyString())).thenReturn(List.of(alphaSlot()));
        when(roundDao.selectByRoundTime(boundaryBarStart(2026))).thenReturn(completedRound());
        when(batchDao.selectAlphaActionBatches(anyString(), any(), any())).thenReturn(List.of(actionBatch()));
        when(settlementDao.insertIgnoreConflict(any())).thenReturn(1);

        assertNull(service.settle(2026, BUSINESS_NOW));

        assertEquals(StockAnnualSettlementStatusEnum.DEGRADED_NOT_PROVABLE.getCode(),
                capturedInsertedRow().getSettlementStatus());
        verify(settlementDao, never()).updateSettledById(any(), any());
        verifyNoInteractions(noticeService);
    }

    @Test
    @DisplayName("边界行情缺失_落降级状态且金额列保持为空且不建年报通知")
    void settle_missingBoundaryQuote_degradesWithoutAmountsOrNotice() {
        when(settlementDao.selectByBusinessKey(anyString(), anyInt(), anyString())).thenReturn(null);
        when(slotDao.selectAllByPortfolioCode(anyString())).thenReturn(List.of(alphaSlot()));
        when(roundDao.selectByRoundTime(boundaryBarStart(2026))).thenReturn(completedRound());
        when(batchDao.selectAlphaActionBatches(anyString(), any(), any())).thenReturn(List.of());
        when(batchDao.selectActiveAlphaBatches(anyString())).thenReturn(List.of(openBatch(7, 100L, 11L)));
        when(barDao.selectByBarStartTime(eq(boundaryBarStart(2026)), anyString())).thenReturn(List.of());
        when(settlementDao.insertIgnoreConflict(any())).thenReturn(1);

        assertNull(service.settle(2026, BUSINESS_NOW));

        TornStockPortfolioAnnualSettlementDO row = capturedInsertedRow();
        assertEquals(StockAnnualSettlementStatusEnum.DEGRADED_PRICE_MISSING.getCode(), row.getSettlementStatus());
        assertNull(row.getClosingEquity(), "缺边界行情时绝不落金额");
        assertTrue(row.getDegradeReason().contains("TCC"));
        verify(settlementDao, never()).updateSettledById(any(), any());
        verifyNoInteractions(noticeService);
    }

    @Test
    @DisplayName("上一年度未结算_禁止跳年并落阻断状态")
    void settle_priorYearNotSettled_blocksPriorYear() {
        when(settlementDao.selectByBusinessKey(anyString(), anyInt(), anyString())).thenReturn(null);
        when(slotDao.selectAllByPortfolioCode(anyString())).thenReturn(List.of(alphaSlot()));
        when(roundDao.selectByRoundTime(boundaryBarStart(2027))).thenReturn(completedRound());
        when(batchDao.selectAlphaActionBatches(anyString(), any(), any())).thenReturn(List.of());
        when(batchDao.selectActiveAlphaBatches(anyString())).thenReturn(List.of());
        when(batchDao.selectEarliestEntryTime(anyString())).thenReturn(EARLIEST_ENTRY_TIME);
        when(settlementDao.selectLatestSettledBefore(anyString(), anyString(), anyInt())).thenReturn(null);
        when(settlementDao.insertIgnoreConflict(any())).thenReturn(1);

        assertNull(service.settle(2027, BUSINESS_NOW));

        assertEquals(StockAnnualSettlementStatusEnum.BLOCKED_PRIOR_YEAR.getCode(),
                capturedInsertedRow().getSettlementStatus());
        verify(settlementDao, never()).updateSettledById(any(), any());
        verifyNoInteractions(noticeService);
    }

    @Test
    @DisplayName("并发台账插入未胜出_放弃本次写入且不重复提取")
    void settle_concurrentLedgerInsert_losesWithoutExtraction() {
        stubSuccessfulGatesWithOpenPosition(2026);
        when(settlementDao.insertIgnoreConflict(any())).thenReturn(0);

        assertNull(service.settle(2026, BUSINESS_NOW));

        verify(settlementDao, never()).updateSettledById(any(), any());
        verifyNoInteractions(noticeService);
    }

    @Test
    @DisplayName("全部门禁通过_同事务落已结算金额并创建年度报告PENDING通知")
    void settle_success_settlesAndCreatesAnnualNotice() {
        stubSuccessfulGatesWithOpenPosition(2026);
        when(settlementDao.insertIgnoreConflict(any())).thenReturn(1);
        when(settlementDao.selectByBusinessKey(anyString(), anyInt(), anyString()))
                .thenReturn(null, settlementRow(55L, StockAnnualSettlementStatusEnum.PENDING_BOUNDARY));
        when(settlementDao.updateSettledById(any(), eq(BUSINESS_NOW))).thenReturn(1);
        when(noticeService.saveOrReusePendingNotice(any(), anyString())).thenReturn(pendingAnnualNotice(77L));

        StockAnnualSettlementService.AnnualSettlementOutcome outcome = service.settle(2026, BUSINESS_NOW);

        assertNotNull(outcome);
        assertEquals(77L, outcome.notice().getId());
        assertTrue(outcome.reportText().contains("【VIP股票 α 年度报告 · 2026】"));
        assertTrue(outcome.reportText().contains("年度状态：试运行（2026-09-17 起，共 106 天）"));

        ArgumentCaptor<TornStockPortfolioAnnualSettlementDO> rowCaptor =
                ArgumentCaptor.forClass(TornStockPortfolioAnnualSettlementDO.class);
        verify(settlementDao).updateSettledById(rowCaptor.capture(), eq(BUSINESS_NOW));
        TornStockPortfolioAnnualSettlementDO settled = rowCaptor.getValue();
        assertEquals(StockAnnualSettlementStatusEnum.SETTLED.getCode(), settled.getSettlementStatus());
        assertEquals(0, new BigDecimal("1000099900.00").compareTo(settled.getClosingEquity()));
        assertEquals(0, new BigDecimal("1000000000.00").compareTo(settled.getClosingCash()));
        assertEquals(0, BigDecimal.ZERO.compareTo(settled.getClosingReserved()));
        assertEquals(0, new BigDecimal("99900.00").compareTo(settled.getClosingMarketValue()));
        assertEquals(0, new BigDecimal("-8999900100.00").compareTo(settled.getExtractedAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(settled.getCumulativeExtractedBefore()));
        assertEquals(106, settled.getCoverageDays());
        assertTrue(settled.getPartialYear());
        assertEquals(1, settled.getOpenPositionCount());
        assertNotNull(settled.getBoundaryBarDigest());
        verify(settlementDao).updateNoticeById(55L, 77L, "PENDING", BUSINESS_NOW);
    }

    /**
     * 配置门禁全部通过且无边界开放持仓的场景。
     *
     * @param settleYear 被结算的自然年
     */
    private void stubSuccessfulGatesWithOpenPosition(int settleYear) {
        when(settlementDao.selectByBusinessKey(anyString(), anyInt(), anyString())).thenReturn(null);
        when(slotDao.selectAllByPortfolioCode(anyString())).thenReturn(List.of(alphaSlot()));
        when(roundDao.selectByRoundTime(boundaryBarStart(settleYear))).thenReturn(completedRound());
        when(batchDao.selectAlphaActionBatches(anyString(), any(), any())).thenReturn(List.of());
        when(batchDao.selectActiveAlphaBatches(anyString())).thenReturn(List.of(openBatch(7, 100L, 11L)));
        when(batchDao.selectEarliestEntryTime(anyString())).thenReturn(EARLIEST_ENTRY_TIME);
        when(settlementDao.selectLatestSettledBefore(anyString(), anyString(), anyInt())).thenReturn(null);
        when(barDao.selectByBarStartTime(eq(boundaryBarStart(settleYear)), anyString()))
                .thenReturn(List.of(usableBoundaryBar()));
    }

    /**
     * 读取降级路径实际插入的台账行。
     *
     * @return 被捕获的台账行
     */
    private TornStockPortfolioAnnualSettlementDO capturedInsertedRow() {
        ArgumentCaptor<TornStockPortfolioAnnualSettlementDO> rowCaptor =
                ArgumentCaptor.forClass(TornStockPortfolioAnnualSettlementDO.class);
        verify(settlementDao).insertIgnoreConflict(rowCaptor.capture());
        return rowCaptor.getValue();
    }

    /**
     * 构建年度边界桶起点。
     *
     * @param settleYear 被结算的自然年
     * @return 边界行情桶起点(被结算年12月31日23:45)
     */
    private LocalDateTime boundaryBarStart(int settleYear) {
        return LocalDateTime.of(settleYear + 1, 1, 1, 0, 0).minusMinutes(15);
    }

    /**
     * 构建正式仓槽位。
     *
     * @return 槽位
     */
    private TornStockPortfolioSlotDO alphaSlot() {
        TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
        slot.setId(11L);
        slot.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        slot.setAvailableCash(new BigDecimal("1000000000.00"));
        slot.setReservedCash(BigDecimal.ZERO);
        return slot;
    }

    /**
     * 构建已完成的边界桶轮次。
     *
     * @return 轮次
     */
    private TornStockMarketRoundDO completedRound() {
        TornStockMarketRoundDO round = new TornStockMarketRoundDO();
        round.setRoundStatus(StockRoundStatusEnum.COMPLETED.getCode());
        return round;
    }

    /**
     * 构建边界后发生动作的批次。
     *
     * @return 动作批次
     */
    private TornStockVirtualBatchDO actionBatch() {
        TornStockVirtualBatchDO batch = openBatch(7, 100L, 11L);
        batch.setBatchNo("A20270101-1");
        return batch;
    }

    /**
     * 构建开放持仓批次。
     *
     * @param stocksId 股票ID
     * @param quantity 股数
     * @param slotId   槽位ID
     * @return 开放持仓批次
     */
    private TornStockVirtualBatchDO openBatch(int stocksId, long quantity, long slotId) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setStocksId(stocksId);
        batch.setStocksShortname("TCC");
        batch.setSlotId(slotId);
        batch.setQuantity(quantity);
        batch.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        return batch;
    }

    /**
     * 构建边界桶可用行情。
     *
     * @return 可用行情bar
     */
    private TornStockMarketBar15mDO usableBoundaryBar() {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setId(900L);
        bar.setStocksId(7);
        bar.setUsable(true);
        bar.setLastPrice(new BigDecimal("1000.00"));
        bar.setBarStartTime(boundaryBarStart(2026));
        bar.setBarEndTime(LocalDateTime.of(2027, 1, 1, 0, 0));
        return bar;
    }

    /**
     * 构建年度结算台账行。
     *
     * @param id     主键ID
     * @param status 结算状态
     * @return 台账行
     */
    private TornStockPortfolioAnnualSettlementDO settlementRow(long id, StockAnnualSettlementStatusEnum status) {
        TornStockPortfolioAnnualSettlementDO row = new TornStockPortfolioAnnualSettlementDO();
        row.setId(id);
        row.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        row.setSettleYear(2026);
        row.setRuleVersion(StockAnnualSettlementService.RULE_VERSION);
        row.setSettlementStatus(status.getCode());
        return row;
    }

    /**
     * 构建待发送的年度报告通知。
     *
     * @param id 通知ID
     * @return 通知审计
     */
    private TornStockNoticeAuditDO pendingAnnualNotice(long id) {
        TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
        notice.setId(id);
        notice.setNoticeNo("A20270101000500000");
        notice.setSendStatus("PENDING");
        return notice;
    }
}
