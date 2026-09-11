package pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDecisionDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDecisionDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision.StockAlphaDecisionService;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision.StockAlphaTargetPolicy;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.NoticeRebalanceAssociation;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;
import pn.torn.goldeneye.torn.service.stocks.alert.shadow.StockShadowRecordWriter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * α策略原子换仓服务编排契约测试。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@DisplayName("α策略原子换仓服务编排契约测试")
@ExtendWith(MockitoExtension.class)
class StockAlphaRebalanceServiceTest {
    private static final LocalDateTime EXECUTION_TIME = LocalDateTime.of(2026, 9, 5, 0, 15);
    /**
     * 决策业务日。
     */
    private static final LocalDate DECISION_DATE = LocalDate.of(2026, 9, 5);
    /**
     * 本次实际处理时刻,仅用于执行bar已结束校验。
     */
    private static final LocalDateTime PROCESSING_TIME = LocalDateTime.of(2026, 9, 5, 0, 31);
    /**
     * 目标变化决策桶(执行桶前一根15分钟桶),与执行bar必须可区分。
     */
    private static final LocalDateTime DECISION_BUCKET = EXECUTION_TIME.minusMinutes(15);
    /**
     * 决策时点参考价,必须与执行bar价格不同才能证明信号参考价来自决策事实。
     */
    private static final BigDecimal DECISION_PRICE = new BigDecimal("9.90");

    @Mock
    private TornStockAlphaDecisionDAO decisionDAO;
    @Mock
    private TornStockPortfolioSlotDAO slotDAO;
    @Mock
    private TornStockVirtualBatchDAO batchDAO;
    @Mock
    private StockPortfolioService portfolioService;
    @Mock
    private StockShadowRecordWriter noticeWriter;
    @Mock
    private StockAlphaDecisionService decisionService;

    @Test
    @DisplayName("原子换仓_持久化新仓并绑定数据库批次ID")
    void rebalance_persistsAndBindsDatabaseReplacementId() {
        TornStockAlphaDecisionDO decision = decision(11L);
        TornStockVirtualBatchDO current = currentBatch();
        TornStockPortfolioSlotDO slot = slot();
        TornStockVirtualBatchDO persisted = new TornStockVirtualBatchDO();
        persisted.setId(99L);
        persisted.setBatchNo("AR-2026-09-05-0-11");
        persisted.setEntryReferencePrice(new BigDecimal("10"));
        persisted.setQuantity(1L);
        persisted.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        persisted.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        persisted.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        when(decisionDAO.selectByExecutionKeyForUpdate(LocalDate.of(2026, 9, 5), 0, EXECUTION_TIME))
                .thenReturn(decision);
        when(decisionService.isSourceReproducible(decision)).thenReturn(true);
        when(slotDAO.selectAllByPortfolioCodeForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(slot));
        when(batchDAO.selectActiveAlphaBatchesForUpdate()).thenReturn(List.of(current));
        when(batchDAO.insertIgnoreConflict(any())).thenReturn(1);
        when(batchDAO.selectByBatchNoForUpdate(persisted.getBatchNo()))
                .thenAnswer(invocation -> probeBeforeInsert(persisted))
                .thenReturn(persisted);
        when(portfolioService.settleSlotBacked(current, slot, new BigDecimal("110"),
                StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenAnswer(invocation -> settleAlphaSlot(slot));

        StockAlphaRebalanceService service = new StockAlphaRebalanceService(
                decisionDAO, slotDAO, batchDAO, portfolioService, noticeWriter, decisionService);
        StockAlphaRebalanceService.RebalanceResult result = service.rebalance(
                DECISION_DATE, 0, PROCESSING_TIME, snapshot());

        assertEquals(99L, result.boughtBatchId());
        assertReplacementBatchFacts(captureInsertedReplacement(), decision);
        assertOriginalBatchClosedAndRebound(current, slot, decision);

        ArgumentCaptor<List<TornStockVirtualBatchDO>> entries = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<NoticeRebalanceAssociation> associationCaptor =
                ArgumentCaptor.forClass(NoticeRebalanceAssociation.class);
        verify(noticeWriter).writeNoticeAudits(entries.capture(), any(), any(), associationCaptor.capture());
        assertRebalanceNoticeAudit(entries.getValue(), associationCaptor.getValue());
    }

    @Test
    @DisplayName("原子换仓_换仓批次编号已存在时拒绝")
    void rebalance_rejectsExistingReplacementBatchNo() {
        TornStockAlphaDecisionDO decision = decision(11L);
        TornStockVirtualBatchDO current = currentBatch();
        when(decisionDAO.selectByExecutionKeyForUpdate(LocalDate.of(2026, 9, 5), 0, EXECUTION_TIME))
                .thenReturn(decision);
        when(decisionService.isSourceReproducible(decision)).thenReturn(true);
        when(slotDAO.selectAllByPortfolioCodeForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(slot()));
        when(batchDAO.selectActiveAlphaBatchesForUpdate()).thenReturn(List.of(current));
        when(batchDAO.selectByBatchNoForUpdate("AR-2026-09-05-0-11")).thenReturn(current);

        StockAlphaRebalanceService service = new StockAlphaRebalanceService(
                decisionDAO, slotDAO, batchDAO, portfolioService, noticeWriter, decisionService);

        RoundSnapshot snapshot = snapshot();
        LocalDateTime processingTime = PROCESSING_TIME;
        assertThrows(IllegalStateException.class, () -> service.rebalance(
                DECISION_DATE, 0, processingTime, snapshot));
    }

    @Test
    @DisplayName("原子换仓_已执行决策重试不重复写入事实与通知")
    void rebalance_alreadyExecuted_retryWritesNoDuplicateNoticeOrFact() {
        TornStockAlphaDecisionDO decision = decision(11L);
        decision.setExecutionStatus("EXECUTED");
        decision.setRebalanceBatchId(99L);
        when(decisionDAO.selectByExecutionKeyForUpdate(LocalDate.of(2026, 9, 5), 0, EXECUTION_TIME))
                .thenReturn(decision);

        StockAlphaRebalanceService service = new StockAlphaRebalanceService(
                decisionDAO, slotDAO, batchDAO, portfolioService, noticeWriter, decisionService);
        RoundSnapshot snapshot = snapshot();
        LocalDate decisionDate = LocalDate.of(2026, 9, 5);
        LocalDateTime processingTime = LocalDateTime.of(2026, 9, 5, 0, 31);

        StockAlphaRebalanceService.RebalanceResult result = service.rebalance(
                decisionDate, 0, processingTime, snapshot);

        assertNull(result.soldBatchId(), "已执行换仓重试不得再次卖出原仓");
        assertEquals(99L, result.boughtBatchId(), "重试必须复用已持久化的换仓批次");
        assertEquals(EXECUTION_TIME, result.executionBarStartTime(), "重试不得改写已持久化执行桶");
        verifyNoInteractions(slotDAO, batchDAO);
        verify(noticeWriter, never()).writeNoticeAudits(any(), any(), any(), any());
        verify(decisionDAO, never()).updateById(decision);
    }

    @Test
    @DisplayName("原子换仓_新仓插入失败不产生单腿事实")
    void rebalance_insertFailure_persistsNoSingleLegFact() {
        TornStockAlphaDecisionDO decision = decision(11L);
        TornStockVirtualBatchDO current = currentBatch();
        TornStockPortfolioSlotDO slot = slot();
        when(decisionDAO.selectByExecutionKeyForUpdate(LocalDate.of(2026, 9, 5), 0, EXECUTION_TIME))
                .thenReturn(decision);
        when(decisionService.isSourceReproducible(decision)).thenReturn(true);
        when(slotDAO.selectAllByPortfolioCodeForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(slot));
        when(batchDAO.selectActiveAlphaBatchesForUpdate()).thenReturn(List.of(current));
        when(batchDAO.insertIgnoreConflict(any())).thenReturn(0);

        StockAlphaRebalanceService service = new StockAlphaRebalanceService(
                decisionDAO, slotDAO, batchDAO, portfolioService, noticeWriter, decisionService);
        RoundSnapshot snapshot = snapshot();
        LocalDateTime processingTime = PROCESSING_TIME;

        assertThrows(IllegalStateException.class, () -> service.rebalance(
                DECISION_DATE, 0, processingTime, snapshot));
        assertEquals(7L, decision.getCurrentBatchId());
        assertNull(decision.getRebalanceBatchId());
        assertEquals("PENDING", decision.getExecutionStatus());
        // 两腿持久化、决策状态、槽位与通知审计均未落库,异常由@Transactional整体回滚,不产生单腿事实
        verify(batchDAO, never()).updateById(any(TornStockVirtualBatchDO.class));
        verify(decisionDAO, never()).updateById(decision);
        verify(slotDAO, never()).updateById(slot);
        verify(noticeWriter, never()).writeNoticeAudits(any(), any(), any(), any());
    }

    @Test
    @DisplayName("原子换仓_来源摘要不可复核时不产生单边事实且不发送通知")
    void rebalance_sourceNotReproducible_writesNoFact() {
        TornStockAlphaDecisionDO decision = decision(11L);
        TornStockVirtualBatchDO current = currentBatch();
        when(decisionDAO.selectByExecutionKeyForUpdate(LocalDate.of(2026, 9, 5), 0, EXECUTION_TIME))
                .thenReturn(decision);
        when(decisionService.isSourceReproducible(decision)).thenReturn(false);
        when(slotDAO.selectAllByPortfolioCodeForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(slot()));
        when(batchDAO.selectActiveAlphaBatchesForUpdate()).thenReturn(List.of(current));

        StockAlphaRebalanceService service = new StockAlphaRebalanceService(decisionDAO, slotDAO, batchDAO,
                portfolioService, noticeWriter, decisionService);

        StockAlphaRebalanceService.RebalanceResult result = service.rebalance(
                LocalDate.of(2026, 9, 5), 0, LocalDateTime.of(2026, 9, 5, 0, 31), snapshot());

        assertNull(result.soldBatchId(), "来源不可复核时不得卖出原仓");
        assertNull(result.boughtBatchId(), "来源不可复核时不得买入新仓");
        assertEquals(StockAlphaDecisionService.SOURCE_NOT_REPRODUCIBLE, decision.getFailureReason());
        assertEquals(StockBatchStatusEnum.OPEN.getCode(), current.getBatchStatus(), "原仓不得被单边关闭");
        verify(batchDAO, never()).insertIgnoreConflict(any());
        verify(batchDAO, never()).updateById(any(TornStockVirtualBatchDO.class));
        verify(slotDAO, never()).updateById(any(TornStockPortfolioSlotDO.class));
        verify(noticeWriter, never()).writeNoticeAudits(any(), any(), any(), any());
    }

    @Test
    @DisplayName("原子换仓_持久化执行桶与当前轮次不一致时不读取决策且不写入任何事实")
    void rebalance_otherExecutionBar_writesNoFact() {
        LocalDateTime laterExecutionBar = EXECUTION_TIME.plusMinutes(15);
        when(decisionDAO.selectByExecutionKeyForUpdate(LocalDate.of(2026, 9, 5), 0, laterExecutionBar))
                .thenReturn(null);

        StockAlphaRebalanceService service = new StockAlphaRebalanceService(
                decisionDAO, slotDAO, batchDAO, portfolioService, noticeWriter, decisionService);
        RoundSnapshot snapshot = new RoundSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), laterExecutionBar);

        StockAlphaRebalanceService.RebalanceResult result = service.rebalance(
                LocalDate.of(2026, 9, 5), 0, LocalDateTime.of(2026, 9, 5, 0, 46), snapshot);

        assertNull(result.soldBatchId(), "跨执行桶不得卖出原仓");
        assertNull(result.boughtBatchId(), "跨执行桶不得买入新仓");
        assertNull(result.executionBarStartTime());
        verify(decisionDAO).selectByExecutionKeyForUpdate(LocalDate.of(2026, 9, 5), 0, laterExecutionBar);
        verifyNoInteractions(slotDAO, batchDAO);
        verify(noticeWriter, never()).writeNoticeAudits(any(), any(), any(), any());
        verify(decisionDAO, never()).updateById(any(TornStockAlphaDecisionDO.class));
    }

    @Test
    @DisplayName("原子换仓_执行桶不是决策桶严格下一根时拒绝且无任何交易或通知写入")
    void rebalance_executionBarNotStrictNext_rejectsWithoutAnySideEffect() {
        LocalDateTime decisionBucket = LocalDateTime.of(2026, 9, 5, 0, 15);
        LocalDateTime gapExecutionBar = LocalDateTime.of(2026, 9, 5, 0, 45);
        TornStockAlphaDecisionDO decision = decision(11L);
        decision.setDecisionBarStartTime(decisionBucket);
        decision.setExecutionBarStartTime(gapExecutionBar);
        when(decisionDAO.selectByExecutionKeyForUpdate(LocalDate.of(2026, 9, 5), 0, gapExecutionBar))
                .thenReturn(decision);
        when(decisionService.isSourceReproducible(decision)).thenReturn(true);
        when(slotDAO.selectAllByPortfolioCodeForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(slot()));
        when(batchDAO.selectActiveAlphaBatchesForUpdate()).thenReturn(List.of(currentBatch()));

        StockAlphaRebalanceService service = new StockAlphaRebalanceService(
                decisionDAO, slotDAO, batchDAO, portfolioService, noticeWriter, decisionService);
        RoundSnapshot snapshot = snapshotAt(gapExecutionBar,
                List.of(bar(1001, new BigDecimal("110")), bar(2002, new BigDecimal("10"))));

        LocalDateTime processingTime = gapExecutionBar.plusMinutes(16);
        assertThrows(IllegalStateException.class,
                () -> service.rebalance(DECISION_DATE, 0, processingTime, snapshot),
                "相差30分钟的执行桶不构成严格相邻,必须拒绝换仓");
        assertNoRebalanceSideEffect();
    }

    @Test
    @DisplayName("原子换仓_决策桶缺失时拒绝且无任何交易或通知写入")
    void rebalance_missingDecisionBar_rejectsWithoutAnySideEffect() {
        TornStockAlphaDecisionDO decision = decision(11L);
        decision.setDecisionBarStartTime(null);
        when(decisionDAO.selectByExecutionKeyForUpdate(LocalDate.of(2026, 9, 5), 0, EXECUTION_TIME))
                .thenReturn(decision);
        when(slotDAO.selectAllByPortfolioCodeForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(slot()));
        when(batchDAO.selectActiveAlphaBatchesForUpdate()).thenReturn(List.of(currentBatch()));

        StockAlphaRebalanceService service = new StockAlphaRebalanceService(
                decisionDAO, slotDAO, batchDAO, portfolioService, noticeWriter, decisionService);

        RoundSnapshot snapshot = snapshot();
        LocalDateTime processingTime = PROCESSING_TIME;
        assertThrows(IllegalStateException.class,
                () -> service.rebalance(DECISION_DATE, 0, processingTime, snapshot),
                "决策桶缺失时禁止换仓,不得用执行桶反推决策事实");
        assertNoRebalanceSideEffect();
    }

    @Test
    @DisplayName("原子换仓_执行bar缺失时拒绝且无任何交易或通知写入")
    void rebalance_missingExecutionBar_rejectsWithoutAnySideEffect() {
        TornStockAlphaDecisionDO decision = decision(11L);
        when(decisionDAO.selectByExecutionKeyForUpdate(LocalDate.of(2026, 9, 5), 0, EXECUTION_TIME))
                .thenReturn(decision);
        when(decisionService.isSourceReproducible(decision)).thenReturn(true);
        when(slotDAO.selectAllByPortfolioCodeForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(slot()));
        when(batchDAO.selectActiveAlphaBatchesForUpdate()).thenReturn(List.of(currentBatch()));

        StockAlphaRebalanceService service = new StockAlphaRebalanceService(
                decisionDAO, slotDAO, batchDAO, portfolioService, noticeWriter, decisionService);
        RoundSnapshot snapshot = snapshotAt(EXECUTION_TIME, List.of());
        LocalDateTime processingTime = PROCESSING_TIME;

        assertThrows(IllegalStateException.class,
                () -> service.rebalance(DECISION_DATE, 0, processingTime, snapshot),
                "执行bar缺失时禁止换仓,不得跨断层追补更晚bar");
        assertNoRebalanceSideEffect();
    }

    @Test
    @DisplayName("原子换仓_执行bar不可用_未结束或价格非正时拒绝且无任何交易或通知写入")
    void rebalance_illegalExecutionBarQuality_rejectsWithoutAnySideEffect() {
        List<TornStockMarketBar15mDO> illegalSellBars = List.of(
                bar(1001, new BigDecimal("110"), false, EXECUTION_TIME.plusMinutes(15)),
                bar(1001, new BigDecimal("110"), true, EXECUTION_TIME.plusMinutes(45)),
                bar(1001, BigDecimal.ZERO, true, EXECUTION_TIME.plusMinutes(15)));

        for (TornStockMarketBar15mDO illegalSellBar : illegalSellBars) {
            TornStockAlphaDecisionDO decision = decision(11L);
            when(decisionDAO.selectByExecutionKeyForUpdate(LocalDate.of(2026, 9, 5), 0, EXECUTION_TIME))
                    .thenReturn(decision);
            when(decisionService.isSourceReproducible(decision)).thenReturn(true);
            when(slotDAO.selectAllByPortfolioCodeForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                    .thenReturn(List.of(slot()));
            when(batchDAO.selectActiveAlphaBatchesForUpdate()).thenReturn(List.of(currentBatch()));

            StockAlphaRebalanceService service = new StockAlphaRebalanceService(
                    decisionDAO, slotDAO, batchDAO, portfolioService, noticeWriter, decisionService);
            RoundSnapshot snapshot = snapshotAt(EXECUTION_TIME,
                    List.of(illegalSellBar, bar(2002, new BigDecimal("10"))));

            LocalDateTime processingTime = PROCESSING_TIME;
            assertThrows(IllegalStateException.class,
                    () -> service.rebalance(DECISION_DATE, 0, processingTime, snapshot),
                    "执行bar不可用、未结束或价格非正时必须拒绝换仓且原仓保持OPEN");
        }
        assertNoRebalanceSideEffect();
    }

    /**
     * 捕获实际插入的换仓新仓批次,并校验插入只发生一次。
     *
     * @return 实际插入的换仓新仓批次
     */
    private TornStockVirtualBatchDO captureInsertedReplacement() {
        ArgumentCaptor<TornStockVirtualBatchDO> replacementCaptor =
                ArgumentCaptor.forClass(TornStockVirtualBatchDO.class);
        verify(batchDAO).insertIgnoreConflict(replacementCaptor.capture());
        return replacementCaptor.getValue();
    }

    /**
     * 校验换仓新仓批次的事实字段与冻结的α业务身份。
     *
     * @param replacement 换仓新仓批次
     * @param decision    来源目标变化决策
     */
    private void assertReplacementBatchFacts(TornStockVirtualBatchDO replacement, TornStockAlphaDecisionDO decision) {
        assertEquals(StockLedgerTypeEnum.FORMAL.getCode(), replacement.getLedgerType());
        assertEquals(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE, replacement.getPortfolioCode());
        assertEquals(DECISION_PRICE, replacement.getSignalReferencePrice(),
                "换仓新仓信号参考价必须来自决策事实,不得使用执行bar成交价");
        assertEquals(DECISION_BUCKET, replacement.getSignalTime(), "换仓新仓来源bar必须为决策桶");
        assertNotEquals(replacement.getSignalTime(), replacement.getEntryTime(),
                "换仓新仓来源bar与执行bar必须可区分");
        assertEquals(EXECUTION_TIME, replacement.getEntryTime(), "换仓新仓执行bar必须是持久化执行桶");
        assertNull(replacement.getExpectedExitBarTime(), "OPEN新仓不得写入误导的expectedExitBarTime");
        assertAlphaAuditSource(replacement, decision);
        assertNotNull(replacement.getFollowUntil());
        assertNotNull(replacement.getFollowMaxPrice());
    }

    /**
     * 校验原仓已按决策桶与执行桶关闭,且槽位与决策均已改绑新仓。
     *
     * @param current  原仓批次
     * @param slot     α策略槽位
     * @param decision 目标变化决策
     */
    private void assertOriginalBatchClosedAndRebound(TornStockVirtualBatchDO current, TornStockPortfolioSlotDO slot,
                                                     TornStockAlphaDecisionDO decision) {
        assertEquals(DECISION_BUCKET, current.getExitSignalTime(), "原仓退出信号时间必须为决策事实(决策桶)");
        assertEquals(EXECUTION_TIME, current.getExpectedExitBarTime(), "原仓预期成交bar必须为执行事实(执行桶)");
        assertEquals(StockAlphaRuleDefinition.EXIT_REASON_REBALANCE, current.getExitReason());
        assertEquals(StockAlphaRuleDefinition.SELL_RULE_VERSION, current.getSellRuleVersion());
        assertEquals(99L, slot.getCurrentBatchId());
        assertEquals(99L, decision.getCurrentBatchId());
    }

    /**
     * 校验换仓通知审计携带新仓批次与统一的双腿关联事实。
     *
     * @param entryBatches 通知审计写入的买入腿批次列表
     * @param association  写入的换仓统一关联事实
     */
    private void assertRebalanceNoticeAudit(List<TornStockVirtualBatchDO> entryBatches,
                                            NoticeRebalanceAssociation association) {
        TornStockVirtualBatchDO noticeBatch = entryBatches.getFirst();
        assertEquals(99L, noticeBatch.getId());
        assertNull(noticeBatch.getExpectedExitBarTime(), "换仓新仓通知批次不得残留expectedExitBarTime");
        // 换仓统一关联事实必须由决策ID、原仓批次与新仓批次组成,供两条通知审计直接读回
        assertEquals(11L, association.rebalanceDecisionId(), "换仓关联必须指向目标变化决策ID");
        assertEquals(7L, association.originalBatchId(), "换仓关联必须指向被换出的原仓批次");
        assertEquals(99L, association.replacementBatchId(), "换仓关联必须指向换仓后的新仓批次");
        assertEquals("ALPHA_REBALANCE:11", association.associationId(), "换仓关联标识格式必须固定");
    }

    /**
     * 模拟"按批次号首次查询不存在"的读回结果。
     *
     * @param persisted 插入前待读回批次
     * @return null,表示插入前按批次号查不到记录
     */
    private TornStockVirtualBatchDO probeBeforeInsert(TornStockVirtualBatchDO persisted) {
        persisted.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        persisted.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        return null;
    }

    /**
     * 模拟α槽位结算后的可用资金变化并返回扣费后卖出所得。
     *
     * @param slot α策略槽位
     * @return 扣费后卖出所得
     */
    private BigDecimal settleAlphaSlot(TornStockPortfolioSlotDO slot) {
        slot.setAvailableCash(new BigDecimal("1049.45"));
        return new BigDecimal("549.45");
    }

    /**
     * 校验α换仓批次与初始入场批次引用同一α审计来源。
     *
     * @param batch    换仓新仓批次
     * @param decision 来源决策
     */
    private void assertAlphaAuditSource(TornStockVirtualBatchDO batch, TornStockAlphaDecisionDO decision) {
        assertEquals(decision.getId(), batch.getAlphaDecisionId(), "α换仓批次必须可回查来源决策");
        assertEquals(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE, batch.getPortfolioCode(), "α批次必须保持α组合身份");
        assertEquals(StockAlphaRuleDefinition.PRIMARY_STRATEGY, batch.getPrimaryStrategy(), "α批次必须保持α主策略身份");
        assertEquals(StockAlphaRuleDefinition.RULE_VERSION, batch.getBuyRuleVersion(), "α批次必须保存α规则版本");
        assertEquals(StockAlphaRuleDefinition.SELL_RULE_VERSION, batch.getSellRuleVersion(),
                "公共组装器不得把α卖出规则版本覆盖为旧版默认值");
        assertEquals(StockAlphaRuleDefinition.ALLOCATION_RULE_VERSION, batch.getAllocationRuleVersion(),
                "公共组装器不得把α分配规则版本覆盖为旧版默认值");
        assertEquals(StockAlphaRuleDefinition.MESSAGE_RULE_VERSION, batch.getMessageRuleVersion(),
                "公共组装器不得把α消息规则版本覆盖为旧版默认值");
        assertEquals(StockStrategyFitEnum.ALPHA_NOT_EVALUATED.getCode(), batch.getStylePrior());
        assertEquals(StockMaturityEnum.ALPHA_NOT_EVALUATED.getCode(), batch.getStyleMaturity());
        assertEquals(StockRiskLevelEnum.ALPHA_NOT_EVALUATED.getCode(), batch.getRiskLevel());
        assertEquals(StockAlphaRuleDefinition.STYLE_RULE_VERSION, batch.getStyleRuleVersion());
        assertEquals(StockAlphaRuleDefinition.RISK_RULE_VERSION, batch.getRiskRuleVersion());
    }

    private TornStockAlphaDecisionDO decision(Long id) {
        TornStockAlphaDecisionDO decision = new TornStockAlphaDecisionDO();
        decision.setId(id);
        decision.setDecisionBusinessDate(LocalDate.of(2026, 9, 5));
        decision.setPhase(0);
        decision.setDecisionType(StockAlphaTargetPolicy.TargetEvent.ALPHA_TARGET_CHANGED.name());
        decision.setCurrentBatchId(7L);
        decision.setSelectedStocksId(2002);
        decision.setExecutionStatus("PENDING");
        decision.setDecisionBarStartTime(DECISION_BUCKET);
        decision.setExecutionBarStartTime(EXECUTION_TIME);
        decision.setSignalReferencePrice(DECISION_PRICE);
        return decision;
    }

    private TornStockVirtualBatchDO currentBatch() {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(7L);
        batch.setBatchNo("A-OLD");
        batch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        batch.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        batch.setStocksId(1001);
        batch.setSlotId(1L);
        batch.setSlotNo(1);
        batch.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        batch.setEntryReferencePrice(new BigDecimal("100"));
        batch.setExpectedExitBarTime(EXECUTION_TIME);
        batch.setQuantity(5L);
        batch.setRemainingCash(new BigDecimal("500"));
        return batch;
    }

    private TornStockPortfolioSlotDO slot() {
        TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
        slot.setId(1L);
        slot.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        slot.setSlotNo(1);
        slot.setSlotStatus(StockSlotStatusEnum.OCCUPIED.getCode());
        slot.setAvailableCash(new BigDecimal("500"));
        slot.setCurrentBatchId(7L);
        return slot;
    }

    private RoundSnapshot snapshot() {
        return snapshotAt(EXECUTION_TIME, List.of(bar(1001, new BigDecimal("110")), bar(2002, new BigDecimal("10"))));
    }

    /**
     * 按指定轮次时间和bar事实构建轮次快照。
     *
     * @param roundTime 轮次时间(执行桶)
     * @param bars      行情bar
     * @return 轮次快照
     */
    private RoundSnapshot snapshotAt(LocalDateTime roundTime, List<TornStockMarketBar15mDO> bars) {
        return new RoundSnapshot(bars, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), roundTime);
    }

    private TornStockMarketBar15mDO bar(Integer stocksId, BigDecimal price) {
        return bar(stocksId, price, true, EXECUTION_TIME.plusMinutes(15));
    }

    /**
     * 构建指定起点外质量字段的行情bar。
     *
     * @param stocksId 股票ID
     * @param price    最后价
     * @param usable   是否可用
     * @param barEnd   bar结束时间
     * @return 行情bar
     */
    private TornStockMarketBar15mDO bar(Integer stocksId, BigDecimal price, boolean usable, LocalDateTime barEnd) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(stocksId);
        bar.setStocksShortname("S" + stocksId);
        bar.setBarStartTime(EXECUTION_TIME);
        bar.setBarEndTime(barEnd);
        bar.setLastPrice(price);
        bar.setUsable(usable);
        return bar;
    }

    /**
     * 断言本次换仓没有产生任何交易、槽位、决策或通知副作用。
     */
    private void assertNoRebalanceSideEffect() {
        verify(batchDAO, never()).insertIgnoreConflict(any());
        verify(batchDAO, never()).updateById(any(TornStockVirtualBatchDO.class));
        verify(decisionDAO, never()).updateById(any(TornStockAlphaDecisionDO.class));
        verify(slotDAO, never()).updateById(any(TornStockPortfolioSlotDO.class));
        verify(noticeWriter, never()).writeNoticeAudits(any(), any(), any(), any());
    }
}
