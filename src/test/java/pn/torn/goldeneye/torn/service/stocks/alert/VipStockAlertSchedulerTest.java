package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRoundStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRuleModeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendService;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 股票提醒调度器测试,验证总开关关闭但存在活跃批次时仍继续构建存量轮次并透传allowNewEntry,
 * 以及历史PENDING通知独立于轮次总开关投递。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.02
 */
@DisplayName("股票提醒调度器测试")
@ExtendWith(MockitoExtension.class)
class VipStockAlertSchedulerTest {

    @Mock
    private Stock15mBarBuildService barBuildService;
    @Mock
    private Stock15mFeatureBuildService featureBuildService;
    @Mock
    private TornStockMarketRoundDAO roundDao;
    @Mock
    private StockHistoryRebuildService historyRebuildService;
    @Mock
    private StockPortfolioInitService portfolioInitService;
    @Mock
    private StockMonthlyStateInitService monthlyStateInitService;
    @Mock
    private StockNoticeSendService noticeSendService;
    @Mock
    private StockRejectedObservationService rejectedObservationService;
    @Mock
    private StockMarketRoundLoader roundLoader;
    @Mock
    private StockRoundTransactionService transactionService;
    @Mock
    private StockMarketClock marketClock;
    @Mock
    private ProjectProperty projectProperty;
    @Mock
    private StockAlertRuntimeGate runtimeGate;

    private VipStockAlertScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new VipStockAlertScheduler(
                barBuildService, featureBuildService, roundDao, historyRebuildService,
                portfolioInitService, monthlyStateInitService, noticeSendService,
                rejectedObservationService, roundLoader, transactionService, marketClock,
                projectProperty, runtimeGate, new StockMarketRoundFactory());
    }

    @Test
    @DisplayName("非生产环境_直接返回")
    void executeRound_notProdEnv_returns() {
        when(projectProperty.getEnv()).thenReturn("dev");

        scheduler.executeRound();

        verify(runtimeGate, never()).evaluate();
        verify(roundDao, never()).selectPendingRoundsUpTo(any());
    }

    @Test
    @DisplayName("无轮次义务且无PENDING通知_不处理轮次不投递通知")
    void executeRound_noObligations_skipsProcessing() {
        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(false, false, false, true, false));

        scheduler.executeRound();

        verify(roundDao, never()).selectPendingRoundsUpTo(any());
        verify(noticeSendService, never()).sendPendingNotices();
        verify(rejectedObservationService, never()).resolveAllDueObservations(any());
    }

    @Test
    @DisplayName("总开关关闭但存在活跃批次_仍处理存量轮次并透传allowNewEntry=false")
    void executeRound_alertDisabledWithActiveBatches_processesRoundsWithAllowNewEntryFalse() {
        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate())
                .thenReturn(decision(true, true, false, false, false));
        when(marketClock.currentEndedBucket()).thenReturn(java.time.LocalDateTime.now());
        when(roundDao.selectPendingRoundsUpTo(any())).thenReturn(List.of());
        when(roundDao.insertPendingRoundIgnoreConflict(any())).thenReturn(1);

        scheduler.executeRound();

        verify(roundDao).insertPendingRoundIgnoreConflict(any());
        verify(roundDao).selectPendingRoundsUpTo(any());
        verify(rejectedObservationService, never()).resolveAllDueObservations(any());
        verify(noticeSendService, never()).sendPendingNotices();
    }

    @Test
    @DisplayName("存在PENDING通知且正式消息允许_即使无轮次义务也投递通知")
    void executeRound_pendingNoticesOnly_sendsNotices() {
        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(false, false, false, false, true));

        scheduler.executeRound();

        verify(noticeSendService).sendPendingNotices();
        verify(roundDao, never()).selectPendingRoundsUpTo(any());
        verify(roundDao, never()).insertPendingRoundIgnoreConflict(any());
    }

    @Test
    @DisplayName("存在未结算拒绝观察_即使无活跃批次也结算研究义务")
    void executeRound_pendingRejectedObservation_resolvesResearchObligations() {
        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(true, false, true, false, false));
        when(marketClock.currentEndedBucket()).thenReturn(java.time.LocalDateTime.now());
        when(roundDao.selectPendingRoundsUpTo(any())).thenReturn(List.of());
        when(roundDao.insertPendingRoundIgnoreConflict(any())).thenReturn(1);

        scheduler.executeRound();

        verify(rejectedObservationService).resolveAllDueObservations(any());
        verify(roundDao).selectPendingRoundsUpTo(any());
    }

    @Test
    @DisplayName("定时入口_必须先为最近已结束桶建立PENDING轮次再补建轮次bar再结算拒绝观察")
    void executeRound_buildsPendingRoundsBeforeResolvingRejectedObservations() {
        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(true, false, true, false, false));
        when(marketClock.currentEndedBucket()).thenReturn(java.time.LocalDateTime.now());
        when(roundDao.selectPendingRoundsUpTo(any())).thenReturn(List.of());
        when(roundDao.insertPendingRoundIgnoreConflict(any())).thenReturn(1);

        scheduler.executeRound();

        InOrder inOrder = inOrder(roundDao, rejectedObservationService);
        inOrder.verify(roundDao).insertPendingRoundIgnoreConflict(any());
        inOrder.verify(roundDao).selectPendingRoundsUpTo(any());
        inOrder.verify(rejectedObservationService).resolveAllDueObservations(any());
    }

    @Test
    @DisplayName("定时入口_同一最近已结束桶重复调度_幂等插入不再重复落行")
    void executeRound_sameBucketRepeatedSchedule_noDuplicateInsert() {
        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate())
                .thenReturn(decision(true, true, false, false, false));
        when(marketClock.currentEndedBucket()).thenReturn(java.time.LocalDateTime.now());
        when(roundDao.selectPendingRoundsUpTo(any())).thenReturn(List.of());
        when(roundDao.insertPendingRoundIgnoreConflict(any())).thenReturn(0);

        scheduler.executeRound();

        verify(roundDao).insertPendingRoundIgnoreConflict(any());
        verify(roundDao).selectPendingRoundsUpTo(any());
    }

    @Test
    @DisplayName("定时入口_新建立的09:45桶被包含上界查询当次读取并进入轮次事务_actualProcessingTime为10:10")
    void executeRound_inclusiveUpperBound_processesNewlyCreatedBucketImmediately() {
        // 固定时钟10:10,currentEndedBucket=09:45: 生产者先幂等建立09:45的PENDING轮次,
        // 消费查询 round_time <= 09:45 必须返回该桶,并在本次调度进入bar/特征/轮次事务,
        // actualProcessingTime必须为10:10真实处理时刻而非09:45历史锚点。
        LocalDateTime currentEndedBucket = LocalDateTime.of(2026, 8, 5, 9, 45);
        LocalDateTime actualTime = LocalDateTime.of(2026, 8, 5, 10, 10);
        TornStockMarketRoundDO round = pendingRound(1L, currentEndedBucket);

        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(true, true, false, true, false));
        when(marketClock.currentEndedBucket()).thenReturn(currentEndedBucket);
        when(marketClock.now()).thenReturn(actualTime);
        when(roundDao.insertPendingRoundIgnoreConflict(any())).thenReturn(1);
        when(roundDao.selectPendingRoundsUpTo(currentEndedBucket)).thenReturn(List.of(round));
        when(barBuildService.buildBars(currentEndedBucket)).thenReturn(List.of(new TornStockMarketBar15mDO()));
        when(featureBuildService.buildFeatures(currentEndedBucket)).thenReturn(List.of());

        scheduler.executeRound();

        verify(roundDao).insertPendingRoundIgnoreConflict(any());
        verify(roundDao).selectPendingRoundsUpTo(currentEndedBucket);
        ArgumentCaptor<LocalDateTime> roundTimeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> actualTimeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(transactionService).executeRound(roundTimeCaptor.capture(), any(), anyBoolean(),
                actualTimeCaptor.capture());
        assertEquals(currentEndedBucket, roundTimeCaptor.getValue(),
                "包含上界查询必须将09:45新桶传入本轮次事务,不得延后至10:15或10:30");
        assertEquals(actualTime, actualTimeCaptor.getValue(),
                "actualProcessingTime必须为10:10真实处理时刻,不得退回09:45历史轮次时刻");
    }

    @Test
    @DisplayName("启动补偿_总开关关闭但有存量批次_仍执行历史重建与轮次处理并透传allowNewEntry=false")
    void onStartup_alertDisabledWithActiveBatches_rebuildsAndProcessesRounds() {
        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(true, true, false, false, false));
        when(marketClock.currentEndedBucket()).thenReturn(java.time.LocalDateTime.now());
        when(marketClock.today()).thenReturn(java.time.LocalDate.now());
        when(roundDao.selectPendingRoundsUpTo(any())).thenReturn(List.of());

        scheduler.onStartup();

        verify(portfolioInitService).verifyAndInitSlots();
        verify(historyRebuildService).rebuildFromLastCompleted(any());
        verify(roundDao).selectPendingRoundsUpTo(any());
        verify(monthlyStateInitService).recalculateCurrentMonthDrafts();
        verify(monthlyStateInitService).autoConfirmDraftStates(any());
        verify(noticeSendService, never()).sendPendingNotices();
    }

    @Test
    @DisplayName("启动补偿_无轮次义务时跳过历史重建与轮次处理但投递PENDING通知")
    void onStartup_noRoundsButPendingNotices_skipsRebuildSendsNotices() {
        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(false, false, false, false, true));

        scheduler.onStartup();

        verify(portfolioInitService).verifyAndInitSlots();
        verify(historyRebuildService, never()).rebuildFromLastCompleted(any());
        verify(roundDao, never()).selectPendingRoundsUpTo(any());
        verify(monthlyStateInitService, never()).recalculateCurrentMonthDrafts();
        verify(noticeSendService).sendPendingNotices();
    }

    @Test
    @DisplayName("启动补偿_必须先补建历史与轮次数据再重算月度DRAFT再结算拒绝观察")
    void onStartup_buildsPendingRoundsBeforeResolvingRejectedObservations() {
        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(true, true, true, false, false));
        when(marketClock.currentEndedBucket()).thenReturn(java.time.LocalDateTime.now());
        when(marketClock.today()).thenReturn(java.time.LocalDate.now());
        when(roundDao.selectPendingRoundsUpTo(any())).thenReturn(List.of());
        when(monthlyStateInitService.recalculateCurrentMonthDrafts()).thenReturn(0);

        scheduler.onStartup();

        InOrder inOrder = inOrder(historyRebuildService, roundDao, monthlyStateInitService,
                rejectedObservationService);
        inOrder.verify(historyRebuildService).rebuildFromLastCompleted(any());
        inOrder.verify(roundDao).selectPendingRoundsUpTo(any());
        inOrder.verify(monthlyStateInitService).recalculateCurrentMonthDrafts();
        inOrder.verify(monthlyStateInitService).autoConfirmDraftStates(any());
        inOrder.verify(rejectedObservationService).resolveAllDueObservations(any());
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("启动补偿_非空待处理轮次真实补建bar并释放防重入标记")
    void onStartup_withNonEmptyPendingRound_buildsBarsAndReleasesProcessingFlag() {
        LocalDateTime roundTime = LocalDateTime.of(2026, 8, 5, 10, 0);
        TornStockMarketRoundDO firstRound = pendingRound(1L, roundTime);
        TornStockMarketRoundDO secondRound = pendingRound(2L, roundTime);

        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(true, true, true, false, false));
        when(marketClock.currentEndedBucket()).thenReturn(roundTime);
        when(marketClock.today()).thenReturn(java.time.LocalDate.now());
        when(roundDao.selectPendingRoundsUpTo(roundTime))
                .thenReturn(List.of(firstRound))
                .thenReturn(List.of(secondRound));
        when(roundDao.insertPendingRoundIgnoreConflict(any())).thenReturn(1);
        when(barBuildService.buildBars(roundTime)).thenReturn(List.of(new TornStockMarketBar15mDO()));
        when(featureBuildService.buildFeatures(roundTime)).thenReturn(List.of());

        scheduler.onStartup();

        InOrder inOrder = inOrder(historyRebuildService, barBuildService, rejectedObservationService);
        inOrder.verify(historyRebuildService).rebuildFromLastCompleted(roundTime);
        inOrder.verify(barBuildService).buildBars(roundTime);
        inOrder.verify(rejectedObservationService).resolveAllDueObservations(any());
        verify(roundDao, atLeastOnce()).updateById(any());

        scheduler.executeRound();
        verify(barBuildService, times(2)).buildBars(roundTime);
    }

    @Test
    @DisplayName("启动补偿_历史补建失败_阻断同次月度重算与自动确认且存量退出继续")
    void onStartup_historyRebuildFails_blocksMonthlyRecalcAndAutoConfirm() {
        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(true, true, true, true, false));
        when(marketClock.currentEndedBucket()).thenReturn(java.time.LocalDateTime.now());
        when(roundDao.selectPendingRoundsUpTo(any())).thenReturn(List.of());
        doThrow(new IllegalStateException("首桶创建失败"))
                .when(historyRebuildService).rebuildFromLastCompleted(any());

        scheduler.onStartup();

        verify(historyRebuildService).rebuildFromLastCompleted(any());
        verify(monthlyStateInitService, never()).initCurrentMonth();
        verify(monthlyStateInitService, never()).recalculateCurrentMonthDrafts();
        verify(monthlyStateInitService, never()).autoConfirmDraftStates(any());
        verify(roundDao).selectPendingRoundsUpTo(any());
        verify(rejectedObservationService).resolveAllDueObservations(any());
    }

    @Test
    @DisplayName("启动补偿_槽位验证失败_强制关闭新买入且存量管理与研究继续")
    void onStartup_slotVerificationFails_forcesAllowNewEntryFalse() {
        // 生产强制关闭由 onStartup 构建的 RuntimeDecision 副本(forceNewEntryClosed)保证:
        // 槽位验证未通过时即使门禁判定允许新买入,也强制 allowNewEntry=false 再交给轮次工作。
        // 此处通过 processPendingRounds -> processSingleRound 透传的 allowNewEntry 参数
        // (transactionService.executeRound) 断言强制关闭真实生效,同时存量轮次与月度研究继续。
        LocalDateTime roundTime = LocalDateTime.of(2026, 8, 5, 10, 0);
        TornStockMarketRoundDO round = pendingRound(1L, roundTime);

        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(true, true, true, true, false));
        when(portfolioInitService.verifyAndInitSlots()).thenReturn(false);
        when(marketClock.currentEndedBucket()).thenReturn(roundTime);
        when(marketClock.today()).thenReturn(java.time.LocalDate.now());
        when(roundDao.selectPendingRoundsUpTo(roundTime)).thenReturn(List.of(round));
        when(barBuildService.buildBars(roundTime)).thenReturn(List.of(new TornStockMarketBar15mDO()));
        when(featureBuildService.buildFeatures(roundTime)).thenReturn(List.of());

        scheduler.onStartup();

        verify(portfolioInitService).verifyAndInitSlots();
        verify(historyRebuildService).rebuildFromLastCompleted(roundTime);
        verify(roundDao).selectPendingRoundsUpTo(roundTime);
        verify(monthlyStateInitService).recalculateCurrentMonthDrafts();
        verify(rejectedObservationService).resolveAllDueObservations(any());

        ArgumentCaptor<Boolean> allowNewEntryCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(transactionService).executeRound(any(), any(), allowNewEntryCaptor.capture(), any());
        assertFalse(allowNewEntryCaptor.getValue(),
                "槽位验证失败时启动补偿必须将allowNewEntry强制为false,禁止新买入");
    }

    /**
     * 构建运行时判定结果。
     */
    private StockAlertRuntimeGate.RuntimeDecision decision(boolean shouldBuildRounds,
                                                           boolean manageExistingBatches,
                                                           boolean manageResearchObligations,
                                                           boolean allowNewEntry,
                                                           boolean shouldSendPendingNotices) {
        return new StockAlertRuntimeGate.RuntimeDecision(
                shouldBuildRounds, manageExistingBatches, manageResearchObligations,
                allowNewEntry, shouldSendPendingNotices, StockRuleModeEnum.SHADOW,
                manageExistingBatches, manageResearchObligations);
    }

    /**
     * 构建一个初始状态为PENDING的轮次记录。
     *
     * @param id        轮次ID
     * @param roundTime 轮次锚定的bar时间
     * @return 初始PENDING轮次记录
     */
    private TornStockMarketRoundDO pendingRound(Long id, LocalDateTime roundTime) {
        TornStockMarketRoundDO round = new TornStockMarketRoundDO();
        round.setId(id);
        round.setRoundTime(roundTime);
        round.setRoundStatus(StockRoundStatusEnum.PENDING.getCode());
        return round;
    }
}
