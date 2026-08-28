package pn.torn.goldeneye.torn.service.stocks.alert.market.round;

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
import pn.torn.goldeneye.torn.service.stocks.alert.market.*;
import pn.torn.goldeneye.torn.service.stocks.alert.monthly.StockMonthlyStateInitService;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendService;
import pn.torn.goldeneye.torn.service.stocks.alert.observation.StockRejectedObservationService;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioInitService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 股票提醒调度器测试,验证总开关关闭但存在活跃批次时仍继续构建存量轮次并透传allowNewEntry,
 * 以及历史PENDING通知独立于轮次总开关投递。
 *
 * @author Bai
 * @version 1.4.9
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
    @DisplayName("启动补偿_最近已结束桶幂等创建PENDING后当次消费_事务收到真实恢复时刻")
    void onStartup_createsCurrentEndedBucketPendingRound_processesItInStartup() {
        // 固定时间线: recoverAt=10:19:30, currentEndedBucket=10:00, entryStaleAt=10:20。
        // 修复前启动补偿只做历史重建(上界不含当前桶)不创建10:00桶, selectPendingRoundsUpTo查不到该桶,
        // 合法ENTRY须等到下一次cron 10:20:10才处理,可能超过entryStaleAt被误取消。
        // 修复后启动入口必须在本次启动中幂等创建并当次消费10:00桶, 事务收到actualProcessingTime=10:19:30。
        LocalDateTime recoverAt = LocalDateTime.of(2026, 8, 5, 10, 19, 30);
        LocalDateTime currentEndedBucket = LocalDateTime.of(2026, 8, 5, 10, 0);
        TornStockMarketRoundDO round = pendingRound(1L, currentEndedBucket);

        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(true, true, true, true, false));
        when(portfolioInitService.verifyAndInitSlots()).thenReturn(true);
        when(marketClock.currentEndedBucket()).thenReturn(currentEndedBucket);
        when(marketClock.now()).thenReturn(recoverAt);
        when(marketClock.today()).thenReturn(LocalDate.of(2026, 8, 5));
        when(roundDao.insertPendingRoundIgnoreConflict(any())).thenReturn(1);
        when(roundDao.selectPendingRoundsUpTo(currentEndedBucket)).thenReturn(List.of(round));
        when(barBuildService.buildBars(currentEndedBucket)).thenReturn(List.of(new TornStockMarketBar15mDO()));
        when(featureBuildService.buildFeatures(currentEndedBucket)).thenReturn(List.of());

        scheduler.onStartup();

        InOrder inOrder = inOrder(historyRebuildService, roundDao, barBuildService,
                featureBuildService, transactionService);
        inOrder.verify(historyRebuildService).rebuildFromLastCompleted(currentEndedBucket);
        ArgumentCaptor<TornStockMarketRoundDO> createdRoundCaptor = ArgumentCaptor.forClass(TornStockMarketRoundDO.class);
        inOrder.verify(roundDao).insertPendingRoundIgnoreConflict(createdRoundCaptor.capture());
        assertEquals(currentEndedBucket, createdRoundCaptor.getValue().getRoundTime(),
                "启动补偿必须为最近已结束桶幂等建立PENDING轮次, roundTime=10:00");
        inOrder.verify(roundDao).selectPendingRoundsUpTo(currentEndedBucket);
        inOrder.verify(barBuildService).buildBars(currentEndedBucket);
        inOrder.verify(featureBuildService).buildFeatures(currentEndedBucket);
        ArgumentCaptor<LocalDateTime> roundTimeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> actualTimeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        inOrder.verify(transactionService).executeRound(roundTimeCaptor.capture(), any(), anyBoolean(),
                actualTimeCaptor.capture());
        assertEquals(currentEndedBucket, roundTimeCaptor.getValue(),
                "事务必须收到10:00轮次, 不得延后到下一次cron才处理");
        assertEquals(recoverAt, actualTimeCaptor.getValue(),
                "actualProcessingTime必须为10:19:30真实恢复时刻, 不得退回10:00历史轮次时刻");
    }

    @Test
    @DisplayName("启动补偿_跨15分钟边界仍复用首次结束桶快照_只读取一次时钟")
    void onStartup_crossBucketBoundary_reusesFirstEndedBucketSnapshot() {
        // 固定反例: 首次读取 currentEndedBucket() 返回09:45, 若实现发生第二次读取则返回10:00。
        // 修复前 rebuildStartupHistorySafely 内部再次调用时钟, 跨15分钟边界时历史重建(上界不含10:00)
        // 与创建/消费(09:45)使用不同桶, 新结束桶10:00既不在重建区间也不被本次创建/消费, 只能等待下一cron。
        // 修复后单次启动补偿必须恰好一次读取 currentEndedBucket, 历史重建、幂等创建与
        // selectPendingRoundsUpTo(09:45) 均复用首次快照, 该PENDING轮次本次进入bar/feature/事务。
        LocalDateTime firstEndedBucket = LocalDateTime.of(2026, 8, 5, 9, 45);
        LocalDateTime secondPotentialRead = LocalDateTime.of(2026, 8, 5, 10, 0);
        LocalDateTime actualTime = LocalDateTime.of(2026, 8, 5, 10, 14, 59);
        TornStockMarketRoundDO round = pendingRound(1L, firstEndedBucket);

        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(true, true, false, true, false));
        when(portfolioInitService.verifyAndInitSlots()).thenReturn(true);
        when(marketClock.currentEndedBucket())
                .thenReturn(firstEndedBucket)
                .thenReturn(secondPotentialRead);
        when(marketClock.now()).thenReturn(actualTime);
        when(marketClock.today()).thenReturn(LocalDate.of(2026, 8, 5));
        when(roundDao.insertPendingRoundIgnoreConflict(any())).thenReturn(1);
        when(roundDao.selectPendingRoundsUpTo(firstEndedBucket)).thenReturn(List.of(round));
        when(barBuildService.buildBars(firstEndedBucket)).thenReturn(List.of(new TornStockMarketBar15mDO()));
        when(featureBuildService.buildFeatures(firstEndedBucket)).thenReturn(List.of());

        scheduler.onStartup();

        // 1. 单次启动补偿只读取一次结束桶快照(第二次潜在读取10:00不得参与本次启动范围)
        verify(marketClock, times(1)).currentEndedBucket();
        // 2. 历史重建、当前桶幂等创建与包含上界消费均使用首次快照09:45
        verify(historyRebuildService).rebuildFromLastCompleted(firstEndedBucket);
        ArgumentCaptor<TornStockMarketRoundDO> createdRoundCaptor = ArgumentCaptor.forClass(TornStockMarketRoundDO.class);
        verify(roundDao).insertPendingRoundIgnoreConflict(createdRoundCaptor.capture());
        assertEquals(firstEndedBucket, createdRoundCaptor.getValue().getRoundTime(),
                "启动补偿创建轮次必须使用首次快照09:45, 不得使用二次读取的10:00");
        verify(roundDao).selectPendingRoundsUpTo(firstEndedBucket);
        // 3. 该PENDING轮次本次进入bar、feature与轮次事务, 事务收到09:45轮次
        verify(barBuildService).buildBars(firstEndedBucket);
        verify(featureBuildService).buildFeatures(firstEndedBucket);
        ArgumentCaptor<LocalDateTime> roundTimeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(transactionService).executeRound(roundTimeCaptor.capture(), any(), anyBoolean(), any());
        assertEquals(firstEndedBucket, roundTimeCaptor.getValue(),
                "事务必须收到09:45轮次, 不得使用10:00二次快照或延后至下一cron");
    }

    @Test
    @DisplayName("启动补偿_历史补建失败_存量轮次仍进入事务且allowNewEntry=false")
    void onStartup_historyRebuildFails_processesExistingRoundsWithNewEntryClosed() {
        // 历史重建失败必须关闭新入场并阻断月度下游, 但已存在PENDING轮次仍真实进入bar/特征/轮次事务,
        // 事务收到 allowNewEntry=false, 拒绝观察继续结算。空待处理轮次不能证明"存量事务继续",
        // 故本测试必须以非空PENDING轮次捕获 executeRound 参数。
        LocalDateTime currentEndedBucket = LocalDateTime.of(2026, 8, 5, 10, 0);
        TornStockMarketRoundDO round = pendingRound(1L, currentEndedBucket);

        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(true, true, true, true, false));
        when(portfolioInitService.verifyAndInitSlots()).thenReturn(true);
        when(marketClock.currentEndedBucket()).thenReturn(currentEndedBucket);
        when(roundDao.insertPendingRoundIgnoreConflict(any())).thenReturn(1);
        when(roundDao.selectPendingRoundsUpTo(currentEndedBucket)).thenReturn(List.of(round));
        when(barBuildService.buildBars(currentEndedBucket)).thenReturn(List.of(new TornStockMarketBar15mDO()));
        when(featureBuildService.buildFeatures(currentEndedBucket)).thenReturn(List.of());
        doThrow(new IllegalStateException("首桶创建失败"))
                .when(historyRebuildService).rebuildFromLastCompleted(any());

        scheduler.onStartup();

        verify(historyRebuildService).rebuildFromLastCompleted(any());
        verify(roundDao).selectPendingRoundsUpTo(currentEndedBucket);
        verify(barBuildService).buildBars(currentEndedBucket);
        verify(featureBuildService).buildFeatures(currentEndedBucket);
        ArgumentCaptor<Boolean> allowNewEntryCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(transactionService).executeRound(any(), any(), allowNewEntryCaptor.capture(), any());
        assertFalse(allowNewEntryCaptor.getValue(),
                "历史重建失败时存量轮次事务必须收到allowNewEntry=false,关闭本次新入场");
        verify(monthlyStateInitService, never()).initCurrentMonth();
        verify(monthlyStateInitService, never()).recalculateCurrentMonthDrafts();
        verify(monthlyStateInitService, never()).autoConfirmDraftStates(any());
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

    @Test
    @DisplayName("启动补偿_最新桶插入异常_不向外抛出且存量轮次继续处理并关闭新入场")
    void onStartup_pendingRoundInsertFails_continuesExistingRoundsWithNewEntryClosed() {
        // 修复前 ensurePendingRound 在DAO插入异常时记录后重新抛出, onStartup 会向 ApplicationReadyEvent
        // 逃逸并跳过已有未完成轮次处理、拒绝观察结算与独立PENDING通知。
        // 修复后启动专用安全包装收敛异常: 本次关闭新入场并阻断月度下游, 但存量轮次/拒绝观察/独立通知继续,
        // finally释放防重入标记, 启动结束后定时入口可再次进入轮次查询/处理路径。
        LocalDateTime currentEndedBucket = LocalDateTime.of(2026, 8, 5, 10, 0);
        LocalDateTime recoverAt = LocalDateTime.of(2026, 8, 5, 10, 19, 30);
        TornStockMarketRoundDO round = pendingRound(1L, currentEndedBucket);
        TornStockMarketRoundDO roundForCron = pendingRound(2L, currentEndedBucket);

        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(true, true, true, true, true));
        when(portfolioInitService.verifyAndInitSlots()).thenReturn(true);
        when(marketClock.currentEndedBucket()).thenReturn(currentEndedBucket);
        when(marketClock.now()).thenReturn(recoverAt);
        when(roundDao.insertPendingRoundIgnoreConflict(any()))
                .thenThrow(new IllegalStateException("最新桶插入瞬时故障"))
                .thenReturn(1);
        when(roundDao.selectPendingRoundsUpTo(currentEndedBucket))
                .thenReturn(List.of(round))
                .thenReturn(List.of(roundForCron));
        when(barBuildService.buildBars(currentEndedBucket)).thenReturn(List.of(new TornStockMarketBar15mDO()));
        when(featureBuildService.buildFeatures(currentEndedBucket)).thenReturn(List.of());

        scheduler.onStartup();

        // P0: 插入异常不向启动事件逃逸; 存量PENDING轮次真实进入bar/特征/轮次事务且allowNewEntry=false
        verify(roundDao).insertPendingRoundIgnoreConflict(any());
        verify(roundDao).selectPendingRoundsUpTo(currentEndedBucket);
        verify(barBuildService).buildBars(currentEndedBucket);
        verify(featureBuildService).buildFeatures(currentEndedBucket);
        ArgumentCaptor<Boolean> allowNewEntryCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(transactionService).executeRound(any(), any(), allowNewEntryCaptor.capture(), any());
        assertFalse(allowNewEntryCaptor.getValue(),
                "最新桶插入异常时启动补偿必须将allowNewEntry强制为false,关闭本次新入场");

        // 月度状态下游全部阻断, 拒绝观察与独立PENDING通知继续
        verify(monthlyStateInitService, never()).initCurrentMonth();
        verify(monthlyStateInitService, never()).recalculateCurrentMonthDrafts();
        verify(monthlyStateInitService, never()).autoConfirmDraftStates(any());
        verify(rejectedObservationService).resolveAllDueObservations(any());
        verify(noticeSendService).sendPendingNotices();

        // 防重入: 启动处理结束后, 定时入口能重新进入轮次查询/处理路径, 证明processing已释放
        scheduler.executeRound();
        verify(roundDao, times(2)).insertPendingRoundIgnoreConflict(any());
        verify(roundDao, times(2)).selectPendingRoundsUpTo(currentEndedBucket);
        verify(barBuildService, times(2)).buildBars(currentEndedBucket);
    }

    @Test
    @DisplayName("防御式隔离_DAO返回REPAIRED_DATA_ONLY轮次_不构建数据不加载快照不进事务")
    void processPendingRounds_dataRepairOnlyRound_defensivelySkipped() {
        // 查询层白名单是第一道隔离;本测试模拟白名单失效/旁路写入的防御场景:
        // 调度器即使拿到REPAIRED_DATA_ONLY轮次,也不得构建bar/feature、加载快照或调用交易事务。
        LocalDateTime currentEndedBucket = LocalDateTime.of(2026, 8, 5, 10, 0);
        TornStockMarketRoundDO dataOnlyRound =
                roundWithStatus(1L, currentEndedBucket, StockRoundStatusEnum.REPAIRED_DATA_ONLY.getCode());

        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(true, true, false, true, false));
        when(marketClock.currentEndedBucket()).thenReturn(currentEndedBucket);
        when(roundDao.insertPendingRoundIgnoreConflict(any())).thenReturn(1);
        when(roundDao.selectPendingRoundsUpTo(currentEndedBucket)).thenReturn(List.of(dataOnlyRound));

        scheduler.executeRound();

        verify(barBuildService, never()).buildBars(any());
        verify(featureBuildService, never()).buildFeatures(any());
        verify(roundLoader, never()).loadRoundSnapshot(any());
        verify(transactionService, never()).executeRound(any(), any(), anyBoolean(), any());
        verify(roundDao, never()).updateById(any());
    }

    @Test
    @DisplayName("正常READY轮次_跳过数据构建直接加载快照进入事务_主链不受影响")
    void processPendingRounds_readyRound_skipsBuildAndEntersTransaction() {
        LocalDateTime currentEndedBucket = LocalDateTime.of(2026, 8, 5, 10, 0);
        LocalDateTime actualTime = LocalDateTime.of(2026, 8, 5, 10, 20);
        TornStockMarketRoundDO readyRound =
                roundWithStatus(1L, currentEndedBucket, StockRoundStatusEnum.READY.getCode());

        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(true, true, false, true, false));
        when(marketClock.currentEndedBucket()).thenReturn(currentEndedBucket);
        when(marketClock.now()).thenReturn(actualTime);
        when(roundDao.insertPendingRoundIgnoreConflict(any())).thenReturn(1);
        when(roundDao.selectPendingRoundsUpTo(currentEndedBucket)).thenReturn(List.of(readyRound));

        scheduler.executeRound();

        // READY轮次不重复构建bar/feature,但必须加载快照并进入轮次事务(事务失败重试场景)
        verify(barBuildService, never()).buildBars(any());
        verify(featureBuildService, never()).buildFeatures(any());
        verify(roundLoader).loadRoundSnapshot(currentEndedBucket);
        ArgumentCaptor<LocalDateTime> roundTimeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(transactionService).executeRound(roundTimeCaptor.capture(), any(), anyBoolean(), any());
        assertEquals(currentEndedBucket, roundTimeCaptor.getValue(),
                "正常READY轮次必须继续进入交易事务,防止隔离修复误伤生产主链");
    }

    @Test
    @DisplayName("轮次事务抛出超长嵌套异常_持久化FAILED_RETRYABLE根因摘要且不超过1000字符")
    void executeRound_transactionFailsWithLongNestedException_persistsBoundedRootCauseSummary() {
        LocalDateTime roundTime = LocalDateTime.of(2026, 8, 5, 10, 0);
        LocalDateTime actualTime = LocalDateTime.of(2026, 8, 5, 10, 20);
        TornStockMarketRoundDO round = roundWithStatus(1L, roundTime, StockRoundStatusEnum.READY.getCode());
        String rootMessage = "duplicate key root cause "
                + IntStream.range(0, 1200).mapToObj(index -> "x").collect(Collectors.joining());
        Exception nestedFailure = new IllegalStateException(
                "MyBatis SQL wrapper INSERT INTO torn_stock_virtual_batch ...",
                new RuntimeException("Spring persistence wrapper with nested diagnostics",
                        new IllegalStateException(rootMessage)));

        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(runtimeGate.evaluate()).thenReturn(decision(true, true, false, true, false));
        when(marketClock.currentEndedBucket()).thenReturn(roundTime);
        when(marketClock.now()).thenReturn(actualTime);
        when(roundDao.insertPendingRoundIgnoreConflict(any())).thenReturn(0);
        when(roundDao.selectPendingRoundsUpTo(roundTime)).thenReturn(List.of(round));
        when(roundLoader.loadRoundSnapshot(roundTime)).thenReturn(null);
        doThrow(nestedFailure).when(transactionService)
                .executeRound(eq(roundTime), any(), eq(true), eq(actualTime));

        scheduler.executeRound();

        assertEquals(StockRoundStatusEnum.FAILED_RETRYABLE.getCode(), round.getRoundStatus());
        assertEquals(actualTime, round.getCompletedAt());
        assertEquals(1000, round.getErrorMessage().length());
        assertTrue(round.getErrorMessage().startsWith("duplicate key root cause"));
        assertFalse(round.getErrorMessage().contains("MyBatis SQL wrapper"));
        assertFalse(round.getErrorMessage().contains("Spring persistence wrapper"));
        verify(roundDao).updateById(round);
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
        return roundWithStatus(id, roundTime, StockRoundStatusEnum.PENDING.getCode());
    }

    /**
     * 构建指定状态的轮次记录。
     *
     * @param id        轮次ID
     * @param roundTime 轮次锚定的bar时间
     * @param status    轮次状态编码
     * @return 指定状态轮次记录
     */
    private TornStockMarketRoundDO roundWithStatus(Long id, LocalDateTime roundTime, String status) {
        TornStockMarketRoundDO round = new TornStockMarketRoundDO();
        round.setId(id);
        round.setRoundTime(roundTime);
        round.setRoundStatus(status);
        return round;
    }
}
