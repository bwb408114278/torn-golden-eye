package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRuleModeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 股票提醒调度器测试,验证总开关关闭但存在活跃批次时仍继续构建存量轮次并透传allowNewEntry,
 * 以及历史PENDING通知独立于轮次总开关投递。
 *
 * @author Bai
 * @version 1.2.12
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
                projectProperty, runtimeGate);
    }

    @Test
    @DisplayName("非生产环境_直接返回")
    void executeRound_notProdEnv_returns() {
        when(projectProperty.getEnv()).thenReturn("dev");

        scheduler.executeRound();

        verify(runtimeGate, never()).evaluate();
        verify(roundDao, never()).selectPendingRoundsBefore(any());
    }

    @Test
    @DisplayName("无轮次义务且无PENDING通知_不处理轮次不投递通知")
    void executeRound_noObligations_skipsProcessing() {
        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(false, false, false, true, false));

        scheduler.executeRound();

        verify(roundDao, never()).selectPendingRoundsBefore(any());
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
        when(roundDao.selectPendingRoundsBefore(any())).thenReturn(List.of());

        scheduler.executeRound();

        verify(roundDao).selectPendingRoundsBefore(any());
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
        verify(roundDao, never()).selectPendingRoundsBefore(any());
    }

    @Test
    @DisplayName("存在未结算拒绝观察_即使无活跃批次也结算研究义务")
    void executeRound_pendingRejectedObservation_resolvesResearchObligations() {
        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(true, false, true, false, false));
        when(marketClock.currentEndedBucket()).thenReturn(java.time.LocalDateTime.now());
        when(roundDao.selectPendingRoundsBefore(any())).thenReturn(List.of());

        scheduler.executeRound();

        verify(rejectedObservationService).resolveAllDueObservations(any());
        verify(roundDao).selectPendingRoundsBefore(any());
    }

    @Test
    @DisplayName("启动补偿_总开关关闭但有存量批次_仍执行历史重建与轮次处理并透传allowNewEntry=false")
    void onStartup_alertDisabledWithActiveBatches_rebuildsAndProcessesRounds() {
        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(true, true, false, false, false));
        when(marketClock.currentEndedBucket()).thenReturn(java.time.LocalDateTime.now());
        when(roundDao.selectPendingRoundsBefore(any())).thenReturn(List.of());

        scheduler.onStartup();

        verify(portfolioInitService).verifyAndInitSlots();
        verify(monthlyStateInitService).initCurrentMonth();
        verify(historyRebuildService).rebuildFromLastCompleted(any());
        verify(roundDao).selectPendingRoundsBefore(any());
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
        verify(roundDao, never()).selectPendingRoundsBefore(any());
        verify(noticeSendService).sendPendingNotices();
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
}
