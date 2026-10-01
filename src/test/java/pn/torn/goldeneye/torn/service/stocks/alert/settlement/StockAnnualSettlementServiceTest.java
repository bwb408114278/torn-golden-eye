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
 * α年度结算服务编排测试,覆盖幂等短路、可证窗口、三道门禁、累计提取连续性与单语句落库。
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
     * 边界轮次完成时刻(00:00:10)
     */
    private static final LocalDateTime BOUNDARY_ROUND_COMPLETED_AT = LocalDateTime.of(2027, 1, 1, 0, 0, 10);
    /**
     * 首笔α批次入场时间
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
                .thenReturn(settlementRow(9L, 2026, StockAnnualSettlementStatusEnum.SETTLED));

        assertNull(service.settle(2026, businessNow(2026)));

        verify(settlementDao, never()).upsertSettled(any(), any());
        verifyNoInteractions(slotDao, noticeService);
    }

    @Test
    @DisplayName("正式仓槽位缺失_落人工核验且不写金额不建通知")
    void settle_missingSlots_degradesToManualReview() {
        when(settlementDao.selectByBusinessKey(anyString(), anyInt(), anyString())).thenReturn(null);
        when(slotDao.selectAllByPortfolioCode(anyString())).thenReturn(List.of());
        when(settlementDao.insertIgnoreConflict(any())).thenReturn(1);

        assertNull(service.settle(2026, businessNow(2026)));

        assertEquals(StockAnnualSettlementStatusEnum.MANUAL_REVIEW.getCode(),
                capturedInsertedRow().getSettlementStatus());
        verify(settlementDao, never()).upsertSettled(any(), any());
        verifyNoInteractions(noticeService);
    }

    @Test
    @DisplayName("边界桶轮次未完成_落待边界就绪等待窗口内重试")
    void settle_boundaryRoundNotCompleted_waitsWithPendingBoundary() {
        when(settlementDao.selectByBusinessKey(anyString(), anyInt(), anyString())).thenReturn(null);
        when(slotDao.selectAllByPortfolioCode(anyString())).thenReturn(List.of(alphaSlot()));
        when(roundDao.selectByRoundTime(boundaryBarStart(2026))).thenReturn(null);
        when(settlementDao.insertIgnoreConflict(any())).thenReturn(1);

        assertNull(service.settle(2026, businessNow(2026)));

        TornStockPortfolioAnnualSettlementDO row = capturedInsertedRow();
        assertEquals(StockAnnualSettlementStatusEnum.PENDING_BOUNDARY.getCode(), row.getSettlementStatus());
        assertNotNull(row.getDegradeReason());
        verify(settlementDao, never()).upsertSettled(any(), any());
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

        assertNull(service.settle(2026, businessNow(2026)));

        assertEquals(StockAnnualSettlementStatusEnum.DEGRADED_NOT_PROVABLE.getCode(),
                capturedInsertedRow().getSettlementStatus());
        verify(settlementDao, never()).upsertSettled(any(), any());
        verifyNoInteractions(noticeService);
    }

    @Test
    @DisplayName("边界轮次完成后槽位被再次写入_落不可证终态")
    void settle_slotRewrittenAfterBoundaryRound_degradesNotProvable() {
        when(settlementDao.selectByBusinessKey(anyString(), anyInt(), anyString())).thenReturn(null);
        when(slotDao.selectAllByPortfolioCode(anyString())).thenReturn(List.of(rewrittenSlot()));
        when(roundDao.selectByRoundTime(boundaryBarStart(2026))).thenReturn(completedRound());
        when(batchDao.selectAlphaActionBatches(anyString(), any(), any())).thenReturn(List.of());
        when(settlementDao.insertIgnoreConflict(any())).thenReturn(1);

        assertNull(service.settle(2026, businessNow(2026)));

        TornStockPortfolioAnnualSettlementDO row = capturedInsertedRow();
        assertEquals(StockAnnualSettlementStatusEnum.DEGRADED_NOT_PROVABLE.getCode(), row.getSettlementStatus());
        assertTrue(row.getDegradeReason().contains("槽位被再次写入"), "原因必须定位到边界轮次完成后的槽位写入");
        verify(settlementDao, never()).upsertSettled(any(), any());
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

        assertNull(service.settle(2026, businessNow(2026)));

        TornStockPortfolioAnnualSettlementDO row = capturedInsertedRow();
        assertEquals(StockAnnualSettlementStatusEnum.DEGRADED_PRICE_MISSING.getCode(), row.getSettlementStatus());
        assertNull(row.getClosingEquity(), "缺边界行情时绝不落金额");
        assertTrue(row.getDegradeReason().contains("TCC"));
        verify(settlementDao, never()).upsertSettled(any(), any());
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

        assertNull(service.settle(2027, businessNow(2027)));

        assertEquals(StockAnnualSettlementStatusEnum.BLOCKED_PRIOR_YEAR.getCode(),
                capturedInsertedRow().getSettlementStatus());
        verify(settlementDao, never()).upsertSettled(any(), any());
        verifyNoInteractions(noticeService);
    }

    @Test
    @DisplayName("已离开可证窗口_落人工核验且不读槽位不写金额")
    void settle_leftProvableWindow_degradesToManualReview() {
        when(settlementDao.selectByBusinessKey(anyString(), anyInt(), anyString())).thenReturn(null);
        when(settlementDao.insertIgnoreConflict(any())).thenReturn(1);

        assertNull(service.settle(2026, LocalDateTime.of(2027, 1, 1, 0, 20)));

        TornStockPortfolioAnnualSettlementDO row = capturedInsertedRow();
        assertEquals(StockAnnualSettlementStatusEnum.MANUAL_REVIEW.getCode(), row.getSettlementStatus());
        assertTrue(row.getDegradeReason().contains("可证窗口"), "原因必须定位到可证窗口");
        verifyNoInteractions(slotDao, noticeService);
    }

    @Test
    @DisplayName("全部门禁通过_窗口内最晚时刻仍以单语句落已结算金额并创建年度报告PENDING通知")
    void settle_success_settlesAndCreatesAnnualNotice() {
        LocalDateTime lateInWindow = LocalDateTime.of(2027, 1, 1, 0, 13);
        stubSuccessfulGatesWithOpenPosition(2026);
        when(settlementDao.selectByBusinessKey(anyString(), anyInt(), anyString()))
                .thenReturn(null, settledRow(55L, 2026, 106, true));
        when(noticeService.saveOrReusePendingNotice(any(), anyString(), any())).thenReturn(pendingAnnualNotice(77L));

        StockAnnualSettlementService.AnnualSettlementOutcome outcome = service.settle(2026, lateInWindow);

        assertNotNull(outcome);
        assertEquals(77L, outcome.notice().getId());
        assertTrue(outcome.reportText().contains("【VIP股票 α 年度报告 · 2026】"));
        assertTrue(outcome.reportText().contains("年度状态：试运行（2026-09-17 起，共 106 天）"));

        TornStockPortfolioAnnualSettlementDO settled = capturedUpsertedRow();
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
        verify(settlementDao, never()).insertIgnoreConflict(any());
        verify(settlementDao).updateNoticeById(55L, 77L, "PENDING", lateInWindow);
    }

    @Test
    @DisplayName("既有降级行重试_同键UPSERT补齐金额翻转为已结算且不重复建行")
    void settle_retryAfterDegrade_flipsToSettled() {
        LocalDateTime businessNow = businessNow(2026);
        stubSuccessfulGatesWithOpenPosition(2026);
        when(settlementDao.selectByBusinessKey(anyString(), anyInt(), anyString()))
                .thenReturn(settlementRow(55L, 2026, StockAnnualSettlementStatusEnum.PENDING_BOUNDARY),
                        settledRow(55L, 2026, 106, true));
        when(noticeService.saveOrReusePendingNotice(any(), anyString(), any())).thenReturn(pendingAnnualNotice(77L));

        StockAnnualSettlementService.AnnualSettlementOutcome outcome = service.settle(2026, businessNow);

        assertNotNull(outcome, "既有降级行必须在门禁通过后翻转为已结算");
        verify(settlementDao).upsertSettled(any(), eq(businessNow));
        verify(settlementDao, never()).insertIgnoreConflict(any());
        assertEquals(StockAnnualSettlementStatusEnum.SETTLED.getCode(),
                capturedUpsertedRow().getSettlementStatus());
    }

    @Test
    @DisplayName("第2年度_区间起点钳制到被结算年1月1日且落完整年度")
    void settle_secondYear_clampsCoverageToSettleYear() {
        LocalDateTime businessNow = businessNow(2027);
        stubSuccessfulGatesWithOpenPosition(2027);
        when(settlementDao.selectLatestSettledBefore(anyString(), anyString(), anyInt()))
                .thenReturn(settlementRow(9L, 2026, StockAnnualSettlementStatusEnum.SETTLED));
        when(settlementDao.selectByBusinessKey(anyString(), anyInt(), anyString()))
                .thenReturn(null, settledRow(66L, 2027, 365, false));
        when(noticeService.saveOrReusePendingNotice(any(), anyString(), any())).thenReturn(pendingAnnualNotice(88L));

        StockAnnualSettlementService.AnnualSettlementOutcome outcome = service.settle(2027, businessNow);

        assertNotNull(outcome);
        TornStockPortfolioAnnualSettlementDO settled = capturedUpsertedRow();
        assertEquals(365, settled.getCoverageDays(), "首笔入场日早于被结算年时必须从1月1日起算");
        assertFalse(settled.getPartialYear());
        assertFalse(outcome.reportText().contains("年化折算"), "完整年度不得展示年化折算行");
        assertTrue(outcome.reportText().contains("完整年度（365 天）"));
    }

    /**
     * 配置门禁全部通过且存在一个边界开放持仓的场景。
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
                .thenReturn(List.of(usableBoundaryBar(settleYear)));
    }

    /**
     * 构建年度边界当日00:05的业务时间。
     *
     * @param settleYear 被结算的自然年
     * @return 业务时间
     */
    private LocalDateTime businessNow(int settleYear) {
        return LocalDateTime.of(settleYear + 1, 1, 1, 0, 5);
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
     * 读取已结算路径实际UPSERT的台账行。
     *
     * @return 被捕获的台账行
     */
    private TornStockPortfolioAnnualSettlementDO capturedUpsertedRow() {
        ArgumentCaptor<TornStockPortfolioAnnualSettlementDO> rowCaptor =
                ArgumentCaptor.forClass(TornStockPortfolioAnnualSettlementDO.class);
        verify(settlementDao).upsertSettled(rowCaptor.capture(), any());
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
     * 构建边界轮次完成后被再次写入的槽位。
     *
     * @return 槽位
     */
    private TornStockPortfolioSlotDO rewrittenSlot() {
        TornStockPortfolioSlotDO slot = alphaSlot();
        slot.setUpdateTime(BOUNDARY_ROUND_COMPLETED_AT.plusNanos(25_000_000));
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
        round.setCompletedAt(BOUNDARY_ROUND_COMPLETED_AT);
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
     * 构建边界桶可用行情(结束时点必须等于被结算年边界,否则权益估值判为陈旧行情)。
     *
     * @param settleYear 被结算的自然年
     * @return 可用行情bar
     */
    private TornStockMarketBar15mDO usableBoundaryBar(int settleYear) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setId(900L);
        bar.setStocksId(7);
        bar.setUsable(true);
        bar.setLastPrice(new BigDecimal("1000.00"));
        bar.setBarStartTime(boundaryBarStart(settleYear));
        bar.setBarEndTime(LocalDateTime.of(settleYear + 1, 1, 1, 0, 0));
        return bar;
    }

    /**
     * 构建年度结算台账行。
     *
     * @param id         主键ID
     * @param settleYear 被结算的自然年
     * @param status     结算状态
     * @return 台账行
     */
    private TornStockPortfolioAnnualSettlementDO settlementRow(long id, int settleYear,
                                                               StockAnnualSettlementStatusEnum status) {
        TornStockPortfolioAnnualSettlementDO row = new TornStockPortfolioAnnualSettlementDO();
        row.setId(id);
        row.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        row.setSettleYear(settleYear);
        row.setRuleVersion(StockAnnualSettlementService.RULE_VERSION);
        row.setSettlementStatus(status.getCode());
        row.setCumulativeExtractedAfter(BigDecimal.ZERO);
        return row;
    }

    /**
     * 构建金额与派生量齐全的已结算台账行(回读断言口径与计算器一致)。
     *
     * @param id           主键ID
     * @param settleYear   被结算的自然年
     * @param coverageDays 区间自然日数
     * @param partialYear  是否不完整年度
     * @return 已结算台账行
     */
    private TornStockPortfolioAnnualSettlementDO settledRow(long id, int settleYear, int coverageDays,
                                                            boolean partialYear) {
        TornStockPortfolioAnnualSettlementDO row = settlementRow(id, settleYear,
                StockAnnualSettlementStatusEnum.SETTLED);
        row.setBoundaryTime(LocalDateTime.of(settleYear + 1, 1, 1, 0, 0));
        row.setCoverageDays(coverageDays);
        row.setPartialYear(partialYear);
        row.setYearReturn(new BigDecimal("-0.8999900100"));
        row.setAnnualizedReturn(partialYear ? new BigDecimal("-0.998000000000000000") : null);
        row.setExtractedAmount(new BigDecimal("-8999900100.00"));
        row.setCumulativeExtractedAfter(new BigDecimal("-8999900100.00"));
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
