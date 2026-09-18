package pn.torn.goldeneye.torn.service.stocks.alert.market.round;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockBatchMarkDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision.StockAlphaDecisionService;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision.StockAlphaTargetPolicy;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution.StockAlphaEntryService;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution.StockAlphaExecutionBarPolicy;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution.StockAlphaRebalanceService;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.track.StockAlphaPhaseTrack;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.track.StockAlphaTrackRegistry;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundFactory;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeAuditWriter;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockBatchPathService;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockEntrySettlementService;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 股票轮次事务编排测试，验证正式存量结算、灾难关闭与α轨道决策/入场的调用顺序与幂等边界。
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.07.17
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("股票轮次事务编排测试")
class StockRoundTransactionServiceTest {

    /**
     * 本测试使用的正式α相位轨道(唯一恒启用轨道)。
     */
    private static final StockAlphaPhaseTrack TRACK = StockAlphaTrackRegistry.productionTrack();

    @Mock
    private TornStockMarketRoundDAO marketRoundDao;
    @Mock
    private TornStockVirtualBatchDAO virtualBatchDao;
    @Mock
    private TornStockPortfolioSlotDAO portfolioSlotDao;
    @Mock
    private TornStockBatchMarkDAO batchMarkDao;
    @Spy
    private StockEntrySettlementService entrySettlementService = new StockEntrySettlementService(new StockPortfolioService());
    @Mock
    private StockBatchPathService batchPathService;
    @Mock
    private StockAlphaEntryService alphaEntryService;
    @Mock
    private StockAlphaDecisionService alphaDecisionService;
    @Mock
    private StockAlphaRebalanceService alphaRebalanceService;
    @Mock
    private StockNoticeAuditWriter noticeAuditWriter;
    @Mock
    private StockAlphaTrackRegistry trackRegistry;
    @Mock
    private SysSettingManager sysSettingManager;
    @Captor
    private ArgumentCaptor<RoundSnapshot> snapshotCaptor;

    private StockRoundTransactionService transactionService;

    @BeforeEach
    void setUp() {
        lenient().when(trackRegistry.enabledTracks()).thenReturn(List.of(TRACK));
        lenient().when(portfolioSlotDao.selectAllByPortfolioCodeForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of());
        lenient().when(virtualBatchDao.selectActiveAlphaBatchesForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of());
        transactionService = new StockRoundTransactionService(
                marketRoundDao, virtualBatchDao, portfolioSlotDao, batchMarkDao,
                entrySettlementService, alphaEntryService, alphaDecisionService,
                alphaRebalanceService, batchPathService, noticeAuditWriter, trackRegistry,
                sysSettingManager, new StockMarketRoundFactory(), new StockMarketClock());
    }

    @Test
    @DisplayName("生产编排_DATA_STALE_EXIT正式批次灾难关闭_完整审计字段与通知审计")
    void executeRound_dataStaleExitFormalBatch_disasterClosesWithAuditAndNotice() {
        LocalDateTime roundTime = LocalDateTime.of(2026, 8, 1, 10, 0);
        TornStockVirtualBatchDO staleExitBatch = staleExitBatch(31L, 3001, roundTime);
        staleExitBatch.setPortfolioCode(StockPortfolioService.PORTFOLIO_CODE);
        List<TornStockPortfolioSlotDO> lockedSlots = buildFiveFormalSlots(staleExitBatch);
        List<TornStockPortfolioSlotDO> externalSlots = buildFiveFormalSlots(staleExitBatch);
        RoundSnapshot snapshot = new RoundSnapshot(List.of(usableBar(3001, roundTime)),
                List.of(),
                List.of(staleExitBatch),
                externalSlots,
                roundTime);

        TornStockMarketRoundDO round = new TornStockMarketRoundDO();
        when(marketRoundDao.selectByRoundTimeForUpdate(roundTime)).thenReturn(round);
        when(portfolioSlotDao.selectAllByPortfolioCodeForUpdate(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(lockedSlots);
        when(virtualBatchDao.selectActiveFormalBatchesForUpdate()).thenReturn(List.of(staleExitBatch));
        when(batchPathService.updatePathsAndEvaluateExits(any(), any(), any(), eq(roundTime))).thenReturn(List.of());

        transactionService.executeRound(roundTime, snapshot, false, roundTime);

        assertEquals(StockBatchStatusEnum.ADMIN_CLOSED.getCode(), staleExitBatch.getBatchStatus());
        assertEquals(new BigDecimal("101.00"), staleExitBatch.getExitReferencePrice(),
                "灾难关闭参考价应为恢复bar价格");
        assertEquals(roundTime.plusMinutes(15), staleExitBatch.getExitTime());
        assertEquals(roundTime.plusMinutes(15).plusHours(48), staleExitBatch.getCooldownUntil(),
                "灾难关闭冷却应为48小时");
        assertFalse(staleExitBatch.getResetObserved());
        assertEquals("CLOSED_TARGET", staleExitBatch.getOriginalExitReason(), "应冻结原退出原因");
        assertEquals("DATA_STALE_EXIT_RECOVERY_CLOSE", staleExitBatch.getAdminCloseReason());
        assertEquals(roundTime, staleExitBatch.getRecoveryBarStartTime());
        assertEquals(roundTime.plusMinutes(15), staleExitBatch.getRecoveryBarEndTime());
        assertEquals(0L, staleExitBatch.getStaleExitDurationSeconds());
        assertEquals(StockSlotStatusEnum.AVAILABLE.getCode(), lockedSlots.getFirst().getSlotStatus(),
                "正式槽位应同事务释放");
        assertNull(lockedSlots.getFirst().getCurrentBatchId(), "槽位应解绑批次");

        ArgumentCaptor<List<TornStockVirtualBatchDO>> exitFilledCaptor = ArgumentCaptor.forClass(List.class);
        verify(noticeAuditWriter).writeNoticeAudits(any(), exitFilledCaptor.capture(), eq(roundTime));
        assertTrue(exitFilledCaptor.getValue().contains(staleExitBatch),
                "灾难关闭批次应进入SELL通知审计");
        verify(marketRoundDao, atLeastOnce()).updateById(round);
    }

    @Test
    @DisplayName("生产编排_ENTRY_PENDING实际处理时刻晚于staleAt_取消且BUY通知为0")
    void executeRound_entryStaleByActualProcessingTime_noBuyNotice() {
        LocalDateTime roundTime = LocalDateTime.of(2026, 8, 1, 10, 0);
        TornStockVirtualBatchDO entryPendingBatch = entryPendingBatch(
                41L, 4001, roundTime.minusMinutes(30), roundTime.plusMinutes(35));
        // 实际处理时刻晚于staleAt(启动补偿晚恢复)
        LocalDateTime actualProcessingTime = roundTime.plusMinutes(50);
        List<TornStockPortfolioSlotDO> lockedSlots = buildFiveFormalSlots(new TornStockVirtualBatchDO());
        RoundSnapshot snapshot = new RoundSnapshot(List.of(usableBar(4001, roundTime)),
                List.of(),
                List.of(entryPendingBatch),
                lockedSlots,
                roundTime);

        TornStockMarketRoundDO round = new TornStockMarketRoundDO();
        when(marketRoundDao.selectByRoundTimeForUpdate(roundTime)).thenReturn(round);
        when(portfolioSlotDao.selectAllByPortfolioCodeForUpdate(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(lockedSlots);
        when(virtualBatchDao.selectActiveFormalBatchesForUpdate()).thenReturn(List.of(entryPendingBatch));
        when(batchPathService.updatePathsAndEvaluateExits(any(), any(), any(), eq(roundTime))).thenReturn(List.of());

        transactionService.executeRound(roundTime, snapshot, false, actualProcessingTime);

        assertEquals(StockBatchStatusEnum.CANCELLED.getCode(), entryPendingBatch.getBatchStatus(),
                "晚于staleAt的ENTRY_PENDING应取消");
        assertEquals(StockCancelReasonEnum.ENTRY_DATA_STALE.getCode(), entryPendingBatch.getCancelReason());
        ArgumentCaptor<List<TornStockVirtualBatchDO>> entryFilledCaptor = ArgumentCaptor.forClass(List.class);
        verify(noticeAuditWriter).writeNoticeAudits(entryFilledCaptor.capture(), any(), eq(roundTime));
        assertFalse(entryFilledCaptor.getValue().contains(entryPendingBatch),
                "过期正式批次不得进入已成交买入列表");
    }

    @Test
    @DisplayName("生产编排_P1-4灾难关闭缺exitSignalTime_整轮事务抛异常回滚且通知为0")
    void executeRound_disasterCloseMissingExitSignalTime_throwsAndRollsBack() {
        LocalDateTime roundTime = LocalDateTime.of(2026, 8, 1, 10, 0);
        TornStockVirtualBatchDO staleExitBatch = staleExitBatch(31L, 3001, roundTime);
        staleExitBatch.setExitSignalTime(null);
        List<TornStockPortfolioSlotDO> lockedSlots = buildFiveFormalSlots(staleExitBatch);
        RoundSnapshot snapshot = new RoundSnapshot(List.of(usableBar(3001, roundTime)),
                List.of(),
                List.of(staleExitBatch),
                lockedSlots,
                roundTime);

        TornStockMarketRoundDO round = new TornStockMarketRoundDO();
        when(marketRoundDao.selectByRoundTimeForUpdate(roundTime)).thenReturn(round);
        when(portfolioSlotDao.selectAllByPortfolioCodeForUpdate(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(lockedSlots);
        when(virtualBatchDao.selectActiveFormalBatchesForUpdate()).thenReturn(List.of(staleExitBatch));

        assertThrows(IllegalStateException.class,
                () -> transactionService.executeRound(roundTime, snapshot, false, roundTime),
                "灾难关闭缺exitSignalTime必须抛异常,由@Transactional整轮回滚");
        assertEquals(StockBatchStatusEnum.DATA_STALE_EXIT.getCode(), staleExitBatch.getBatchStatus(),
                "回滚后批次状态不得变化");
        assertEquals(StockSlotStatusEnum.OCCUPIED.getCode(), lockedSlots.getFirst().getSlotStatus(),
                "回滚后槽位不得释放");
        assertNotNull(lockedSlots.getFirst().getCurrentBatchId(), "回滚后槽位不得解绑批次");
        verify(noticeAuditWriter, never()).writeNoticeAudits(any(), any(), eq(roundTime));
    }

    @Test
    @DisplayName("无Alpha持仓且执行桶为本轮_先生成或复用决策再消费且入场先于结算")
    void executeRound_withoutAlphaPosition_decidesBeforeCreatingInitialEntry() {
        LocalDateTime roundTime = LocalDateTime.of(2026, 8, 1, 10, 0);
        LocalDate decisionDate = roundTime.toLocalDate().minusDays(1);
        List<TornStockPortfolioSlotDO> lockedSlots = buildFiveFormalSlots(new TornStockVirtualBatchDO());
        TornStockVirtualBatchDO initialAlphaBatch = entryPendingBatch(51L, 5001,
                roundTime.minusMinutes(15), roundTime.plusMinutes(20));
        initialAlphaBatch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        initialAlphaBatch.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        initialAlphaBatch.setAlphaDecisionId(201L);
        RoundSnapshot snapshot = alphaSnapshot(roundTime, List.of(), lockedSlots, List.of());
        stubAlphaRound(roundTime, lockedSlots);
        when(virtualBatchDao.selectActiveAlphaBatchesForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(), List.of(initialAlphaBatch));
        when(alphaDecisionService.decide(eq(TRACK), eq(decisionDate), any(), any(), eq(roundTime), anyMap()))
                .thenReturn(new StockAlphaDecisionService.DecisionResult(
                        decisionDate, true, 60, null, 1001,
                        StockAlphaTargetPolicy.TargetEvent.ALPHA_INITIAL_ENTRY, 0, roundTime));
        doReturn(new StockEntrySettlementService.EntrySettlementResult(List.of(), List.of()))
                .when(entrySettlementService).processEntryPending(any(), any(), eq(roundTime), eq(roundTime));

        transactionService.executeRound(roundTime, snapshot, true, roundTime);

        InOrder inOrder = inOrder(alphaDecisionService, alphaEntryService, entrySettlementService,
                batchPathService, alphaRebalanceService);
        inOrder.verify(alphaDecisionService).decide(eq(TRACK), eq(decisionDate), any(), any(), eq(roundTime), anyMap());
        inOrder.verify(alphaEntryService).createInitialEntry(
                eq(TRACK), eq(roundTime), any(), eq(decisionDate), eq(0), eq(roundTime));
        inOrder.verify(entrySettlementService).processEntryPending(snapshotCaptor.capture(), any(),
                eq(roundTime), eq(roundTime));
        inOrder.verify(batchPathService).updatePathsAndEvaluateExits(any(), any(), any(), eq(roundTime));
        inOrder.verifyNoMoreInteractions();
        assertTrue(snapshotCaptor.getValue().activeBatches().contains(initialAlphaBatch),
                "初始Alpha批次必须在EntrySettlement前进入事务内快照");
        verify(alphaRebalanceService, never()).rebalance(any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("无Alpha持仓且执行桶为下一根bar_本轮只落决策不在本轮入场")
    void executeRound_initialDecisionBarAfterThisRound_skipsEntryInSameRound() {
        LocalDateTime roundTime = LocalDateTime.of(2026, 8, 1, 10, 0);
        LocalDate decisionDate = roundTime.toLocalDate().minusDays(1);
        List<TornStockPortfolioSlotDO> lockedSlots = buildFiveFormalSlots(new TornStockVirtualBatchDO());
        RoundSnapshot snapshot = alphaSnapshot(roundTime, List.of(), lockedSlots, List.of());
        stubAlphaRound(roundTime, lockedSlots);
        when(alphaDecisionService.decide(eq(TRACK), eq(decisionDate), any(), any(), eq(roundTime), anyMap()))
                .thenReturn(new StockAlphaDecisionService.DecisionResult(
                        decisionDate, true, 60, null, 1001,
                        StockAlphaTargetPolicy.TargetEvent.ALPHA_INITIAL_ENTRY, 0, roundTime.plusMinutes(15)));

        transactionService.executeRound(roundTime, snapshot, true, roundTime);

        verify(alphaEntryService, never()).createInitialEntry(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("决策bar不可用_事实携带usable=false进入决策入口且本轮不入场")
    void executeRound_unusableDecisionBar_passesUnusableFactToDecisionService() {
        LocalDateTime roundTime = LocalDateTime.of(2026, 8, 1, 10, 0);
        LocalDate decisionDate = roundTime.toLocalDate().minusDays(1);
        List<TornStockPortfolioSlotDO> lockedSlots = buildFiveFormalSlots(new TornStockVirtualBatchDO());
        TornStockMarketBar15mDO unusableDecisionBar = usableBar(5001, roundTime);
        unusableDecisionBar.setUsable(false);
        RoundSnapshot snapshot = alphaSnapshot(roundTime, List.of(unusableDecisionBar), lockedSlots, List.of());
        stubAlphaRound(roundTime, lockedSlots);
        stubNoAlphaDecision(roundTime);

        transactionService.executeRound(roundTime, snapshot, true, roundTime);

        ArgumentCaptor<Map<Integer, StockAlphaExecutionBarPolicy.DecisionBar>> factsCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(alphaDecisionService).decide(eq(TRACK), eq(decisionDate), any(), any(), eq(roundTime),
                factsCaptor.capture());
        assertFalse(factsCaptor.getValue().get(5001).usable(),
                "不可用决策bar必须以usable=false的事实传给决策服务,不得只传正价");
        assertEquals(roundTime, factsCaptor.getValue().get(5001).barStart(), "决策bar事实必须来自本轮决策桶");
        verify(alphaEntryService, never()).createInitialEntry(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("无Alpha持仓且关闭新入场_不生成或消费初始决策")
    void executeRound_withoutAlphaPositionAndNewEntryDisabled_skipsAlphaEntry() {
        LocalDateTime roundTime = LocalDateTime.of(2026, 8, 1, 10, 0);
        List<TornStockPortfolioSlotDO> lockedSlots = buildFiveFormalSlots(new TornStockVirtualBatchDO());
        RoundSnapshot snapshot = alphaSnapshot(roundTime, List.of(), lockedSlots, List.of());
        transactionService.executeRound(roundTime, snapshot, false, roundTime);

        verifyNoInteractions(alphaDecisionService, alphaEntryService, alphaRebalanceService);
    }

    @Test
    @DisplayName("PROVISIONAL模式_即使门禁传入allowNewEntry=true也不创建VIP_ALPHA正式批次")
    void executeRound_provisionalMode_neverCreatesAlphaBatch() {
        LocalDateTime roundTime = LocalDateTime.of(2026, 8, 1, 10, 0);
        List<TornStockPortfolioSlotDO> lockedSlots = buildFiveFormalSlots(new TornStockVirtualBatchDO());
        RoundSnapshot snapshot = alphaSnapshot(roundTime, List.of(), lockedSlots, List.of());
        when(marketRoundDao.selectByRoundTimeForUpdate(roundTime)).thenReturn(new TornStockMarketRoundDO());
        when(portfolioSlotDao.selectAllByPortfolioCodeForUpdate(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(lockedSlots);
        when(virtualBatchDao.selectActiveFormalBatchesForUpdate()).thenReturn(List.of());
        when(batchPathService.updatePathsAndEvaluateExits(any(), any(), any(), eq(roundTime))).thenReturn(List.of());
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE))
                .thenReturn(StockRuleModeEnum.PROVISIONAL.getCode());

        transactionService.executeRound(roundTime, snapshot, true, roundTime);

        verify(alphaEntryService, never()).createInitialEntry(any(), any(), any(), any(), anyInt(), any());
        verify(alphaDecisionService, never()).decide(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("已有Alpha持仓_按本轮决策时点读取决策且只在持久化执行桶换仓")
    void executeRound_existingAlphaPosition_consumesPersistedExecutionBar() {
        LocalDateTime roundTime = LocalDateTime.of(2026, 8, 1, 10, 0);
        LocalDate decisionDate = roundTime.toLocalDate().minusDays(1);
        TornStockVirtualBatchDO alphaBatch = alphaOpenBatch(61L, 5001, roundTime);
        List<TornStockPortfolioSlotDO> lockedSlots = buildFiveFormalSlots(new TornStockVirtualBatchDO());
        RoundSnapshot snapshot = alphaSnapshot(roundTime, List.of(), lockedSlots, List.of());
        stubAlphaRound(roundTime, lockedSlots);
        when(virtualBatchDao.selectActiveAlphaBatchesForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(alphaBatch));
        when(alphaDecisionService.decide(eq(TRACK), eq(decisionDate), eq(5001), eq(61L), eq(roundTime), anyMap()))
                .thenReturn(new StockAlphaDecisionService.DecisionResult(
                        decisionDate, true, 65, null, 5002,
                        StockAlphaTargetPolicy.TargetEvent.ALPHA_TARGET_CHANGED, 1, roundTime));

        transactionService.executeRound(roundTime, snapshot, true, roundTime);

        verify(alphaDecisionService).decide(eq(TRACK), eq(decisionDate), eq(5001), eq(61L), eq(roundTime), anyMap());
        verify(alphaDecisionService, never()).decide(any(), any(), any(), any(), eq(roundTime.minusMinutes(15)),
                anyMap());
        verify(alphaRebalanceService).rebalance(eq(TRACK), eq(decisionDate), eq(1), eq(roundTime), any());
        verify(alphaEntryService, never()).createInitialEntry(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("已有Alpha持仓且关闭新入场_仍继续管理已有Alpha持仓并换仓")
    void executeRound_existingAlphaPositionAndNewEntryDisabled_stillManagesAlphaBatch() {
        LocalDateTime roundTime = LocalDateTime.of(2026, 8, 1, 10, 0);
        LocalDate decisionDate = roundTime.toLocalDate().minusDays(1);
        TornStockVirtualBatchDO alphaBatch = alphaOpenBatch(61L, 5001, roundTime);
        List<TornStockPortfolioSlotDO> lockedSlots = buildFiveFormalSlots(new TornStockVirtualBatchDO());
        RoundSnapshot snapshot = alphaSnapshot(roundTime, List.of(), lockedSlots, List.of());
        when(marketRoundDao.selectByRoundTimeForUpdate(roundTime)).thenReturn(new TornStockMarketRoundDO());
        when(virtualBatchDao.selectActiveAlphaBatchesForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(alphaBatch));
        when(alphaDecisionService.decide(eq(TRACK), eq(decisionDate), eq(5001), eq(61L), eq(roundTime), anyMap()))
                .thenReturn(new StockAlphaDecisionService.DecisionResult(
                        decisionDate, true, 65, null, 5002,
                        StockAlphaTargetPolicy.TargetEvent.ALPHA_TARGET_CHANGED, 1, roundTime));

        transactionService.executeRound(roundTime, snapshot, false, roundTime);

        verify(alphaRebalanceService).rebalance(eq(TRACK), eq(decisionDate), eq(1), eq(roundTime), any());
        verify(alphaEntryService, never()).createInitialEntry(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("已有Alpha持仓且执行桶为下一根bar_本轮不换仓且不跨桶追补")
    void executeRound_decisionExecutionBarMismatch_skipsRebalance() {
        LocalDateTime roundTime = LocalDateTime.of(2026, 8, 1, 10, 0);
        LocalDate decisionDate = roundTime.toLocalDate().minusDays(1);
        TornStockVirtualBatchDO alphaBatch = alphaOpenBatch(61L, 5001, roundTime);
        List<TornStockPortfolioSlotDO> lockedSlots = buildFiveFormalSlots(new TornStockVirtualBatchDO());
        RoundSnapshot snapshot = alphaSnapshot(roundTime, List.of(), lockedSlots, List.of());
        stubAlphaRound(roundTime, lockedSlots);
        when(virtualBatchDao.selectActiveAlphaBatchesForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(alphaBatch));
        when(alphaDecisionService.decide(eq(TRACK), eq(decisionDate), eq(5001), eq(61L), eq(roundTime), anyMap()))
                .thenReturn(new StockAlphaDecisionService.DecisionResult(
                        decisionDate, true, 65, null, 5002,
                        StockAlphaTargetPolicy.TargetEvent.ALPHA_TARGET_CHANGED, 1,
                        roundTime.plusMinutes(15)));

        transactionService.executeRound(roundTime, snapshot, true, roundTime);

        verify(alphaRebalanceService, never()).rebalance(any(), any(), anyInt(), any(), any());
    }

    /**
     * 构造α编排测试使用的轮次快照,避免各场景重复构造空数据快照。
     *
     * @param roundTime     轮次时间
     * @param bars          本轮行情bar
     * @param slots         锁后槽位
     * @param activeBatches 事务外活跃批次
     * @return 轮次快照
     */
    private RoundSnapshot alphaSnapshot(LocalDateTime roundTime, List<TornStockMarketBar15mDO> bars,
                                        List<TornStockPortfolioSlotDO> slots,
                                        List<TornStockVirtualBatchDO> activeBatches) {
        return new RoundSnapshot(bars, List.of(), activeBatches, slots, roundTime);
    }

    /**
     * 桩化α编排测试所需的轮次协作者:本轮无正式与α轨道活跃批次,规则模式为FORMAL。
     *
     * @param roundTime   轮次时间
     * @param lockedSlots 事务内锁定的完整5槽正式槽位列表
     */
    private void stubAlphaRound(LocalDateTime roundTime, List<TornStockPortfolioSlotDO> lockedSlots) {
        when(marketRoundDao.selectByRoundTimeForUpdate(roundTime)).thenReturn(new TornStockMarketRoundDO());
        when(portfolioSlotDao.selectAllByPortfolioCodeForUpdate(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(lockedSlots);
        when(virtualBatchDao.selectActiveFormalBatchesForUpdate()).thenReturn(List.of());
        when(batchPathService.updatePathsAndEvaluateExits(any(), any(), any(), eq(roundTime))).thenReturn(List.of());
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE))
                .thenReturn(StockRuleModeEnum.FORMAL.getCode());
    }

    /**
     * 桩化本轮没有可消费的α决策,确保非α场景的存量编排不被初始入场链干扰。
     *
     * @param roundTime 轮次时间
     */
    private void stubNoAlphaDecision(LocalDateTime roundTime) {
        when(alphaDecisionService.decide(any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new StockAlphaDecisionService.DecisionResult(
                        roundTime.toLocalDate().minusDays(1), false, 0, null, null,
                        StockAlphaTargetPolicy.TargetEvent.DATA_INSUFFICIENT, null, roundTime.plusMinutes(15)));
    }

    /**
     * 创建待买入批次。
     *
     * @param id           批次ID
     * @param stocksId     股票ID
     * @param signalTime   信号时间
     * @param entryStaleAt 入场过期时间
     * @return 待买入批次
     */
    private TornStockVirtualBatchDO entryPendingBatch(Long id, int stocksId,
                                                      LocalDateTime signalTime,
                                                      LocalDateTime entryStaleAt) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(id);
        batch.setBatchNo("B" + id);
        batch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        batch.setStocksId(stocksId);
        batch.setStocksShortname("T" + stocksId);
        batch.setBatchStatus(StockBatchStatusEnum.ENTRY_PENDING.getCode());
        batch.setSignalReferencePrice(new BigDecimal("100.00"));
        batch.setSignalTime(signalTime);
        batch.setExpectedEntryBarTime(LocalDateTime.of(2026, 8, 1, 10, 0));
        batch.setEntryStaleAt(entryStaleAt);
        batch.setResetObserved(false);
        return batch;
    }

    /**
     * 创建Alpha开放持仓批次,用于验证已有持仓的换仓编排。
     *
     * @param id        批次ID
     * @param stocksId  股票ID
     * @param roundTime 本轮时间
     * @return Alpha开放批次
     */
    private TornStockVirtualBatchDO alphaOpenBatch(Long id, int stocksId, LocalDateTime roundTime) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(id);
        batch.setBatchNo("A" + id);
        batch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        batch.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        batch.setStocksId(stocksId);
        batch.setStocksShortname("T" + stocksId);
        batch.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        batch.setSlotId(101L);
        batch.setSlotNo(1);
        batch.setAlphaDecisionId(201L);
        batch.setEntryTime(roundTime.minusDays(1));
        batch.setEntryReferencePrice(new BigDecimal("100.00"));
        batch.setQuantity(1L);
        return batch;
    }

    /**
     * 创建DATA_STALE_EXIT正式批次,用于灾难关闭编排测试。
     *
     * @param id        批次ID
     * @param stocksId  股票ID
     * @param roundTime 本轮时间
     * @return 数据陈旧卖出批次
     */
    private TornStockVirtualBatchDO staleExitBatch(Long id, int stocksId, LocalDateTime roundTime) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(id);
        batch.setBatchNo("B" + id);
        batch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        batch.setStocksId(stocksId);
        batch.setStocksShortname("T" + stocksId);
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
        batch.setEntryReferencePrice(new BigDecimal("100.00"));
        batch.setEntryTime(roundTime.minusDays(1));
        batch.setQuantity(100L);
        batch.setExpectedExitBarTime(roundTime);
        batch.setExitSignalTime(roundTime.minusDays(1));
        batch.setExitReason(StockCloseTypeEnum.CLOSED_TARGET.getCode());
        batch.setOriginalExitReason(StockCloseTypeEnum.CLOSED_TARGET.getCode());
        batch.setSlotId(1L);
        batch.setSlotNo(1);
        batch.setRemainingCash(new BigDecimal("1999990000.00"));
        return batch;
    }

    /**
     * 构建生产形状的5槽正式组合: 1个OCCUPIED槽位关联正式批次, 其余4个AVAILABLE槽位。
     * <p>
     * 冻结设计正式组合固定为5槽, 每槽初始资金 {@link StockPortfolioService#INITIAL_CASH}。
     * 占用槽位携带建仓后真实余款, 用于验证平仓结算仅释放占用槽位而其余槽位不变。
     *
     * @param occupiedBatch 关联到1号占用槽位的正式批次
     * @return 5个字段合法的正式组合槽位
     */
    private List<TornStockPortfolioSlotDO> buildFiveFormalSlots(TornStockVirtualBatchDO occupiedBatch) {
        TornStockPortfolioSlotDO occupied = formalSlot(1L, StockSlotStatusEnum.OCCUPIED, occupiedBatch.getId());
        occupied.setAvailableCash(new BigDecimal("1999990000.00"));
        return List.of(
                occupied,
                formalSlot(2L, StockSlotStatusEnum.AVAILABLE, null),
                formalSlot(3L, StockSlotStatusEnum.AVAILABLE, null),
                formalSlot(4L, StockSlotStatusEnum.AVAILABLE, null),
                formalSlot(5L, StockSlotStatusEnum.AVAILABLE, null));
    }

    /**
     * 构建字段合法的正式组合槽位。
     *
     * @param id             槽位ID
     * @param status         槽位状态
     * @param currentBatchId 当前批次ID(空仓为null)
     * @return 槽位DO
     */
    private TornStockPortfolioSlotDO formalSlot(Long id, StockSlotStatusEnum status, Long currentBatchId) {
        TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
        slot.setId(id);
        slot.setPortfolioCode(StockPortfolioService.PORTFOLIO_CODE);
        slot.setSlotNo(id.intValue());
        slot.setInitialCash(StockPortfolioService.INITIAL_CASH);
        slot.setAvailableCash(StockPortfolioService.INITIAL_CASH);
        slot.setReservedCash(BigDecimal.ZERO);
        slot.setCurrentBatchId(currentBatchId);
        slot.setSlotStatus(status.getCode());
        slot.setLockVersion(1L);
        return slot;
    }

    private TornStockMarketBar15mDO usableBar(int stocksId, LocalDateTime roundTime) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(stocksId);
        bar.setBarStartTime(roundTime);
        bar.setBarEndTime(roundTime.plusMinutes(15));
        bar.setLastPrice(new BigDecimal("101.00"));
        bar.setSampleCount(15);
        bar.setLastSampleTime(roundTime.plusMinutes(14));
        bar.setUsable(true);
        bar.setBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
        return bar;
    }
}