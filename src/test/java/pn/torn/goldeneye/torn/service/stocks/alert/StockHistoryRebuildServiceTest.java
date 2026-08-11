package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRoundStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 历史重建完整数据义务纯单元测试(Mock/fake DAO,无数据库)。
 * <p>
 * 覆盖四种最小恢复分支与FAILED_FINAL终态保留:
 * <ul>
 *   <li>feature缺失恢复: bar存在、feature缺失、round缺失 - 不重建bar,构建feature并置READY;</li>
 *   <li>round缺失恢复: bar与feature存在、round缺失 - 不重建bar/feature,创建round置READY;</li>
 *   <li>可重试round恢复: bar与feature存在、round=FAILED_RETRYABLE - 不重复写bar/feature,置READY;</li>
 *   <li>完整桶跳过: bar+feature完整、round=COMPLETED且版本一致 - 无任何写入;</li>
 *   <li>FAILED_FINAL: 保留终态与失败事实,不自动重开。</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.11
 */
@DisplayName("历史重建完整数据义务测试")
@ExtendWith(MockitoExtension.class)
class StockHistoryRebuildServiceTest {

    /**
     * 隔离重建桶起点(远离生产数据)。
     */
    private static final LocalDateTime BUCKET = LocalDateTime.of(2099, 6, 1, 10, 0);
    /**
     * 本次重建审计时间。
     */
    private static final LocalDateTime NOW = LocalDateTime.of(2099, 6, 1, 12, 0);

    @Mock
    private Stock15mBarBuildService barBuildService;
    @Mock
    private Stock15mFeatureBuildService featureBuildService;
    @Mock
    private TornStockMarketRoundDAO roundDao;
    @Mock
    private TornStocksHistoryDAO stocksHistoryDao;
    @Mock
    private TornStockMarketBar15mDAO bar15mDao;
    @Mock
    private TornStockStrategyFeature15mDAO feature15mDao;
    @Mock
    private StockMarketClock marketClock;

    private StockHistoryRebuildService service;

    @BeforeEach
    void setUp() {
        service = new StockHistoryRebuildService(
                barBuildService, featureBuildService, roundDao, stocksHistoryDao,
                bar15mDao, feature15mDao, new StockMarketRoundFactory(), marketClock);
        lenient().when(marketClock.now()).thenReturn(NOW);
    }

    @Test
    @DisplayName("feature缺失恢复_bar存在feature缺失round缺失_不重建bar构建feature置READY")
    void rebuild_featureMissing_repairsFeatureToReady() {
        when(bar15mDao.selectByBarStartTime(BUCKET, Stock15mBarBuildService.BUILD_VERSION))
                .thenReturn(List.of(bar(1), bar(2)));
        when(feature15mDao.selectByBarStartTime(BUCKET, Stock15mFeatureBuildService.FEATURE_VERSION))
                .thenReturn(List.of());
        when(roundDao.selectByRoundTime(BUCKET)).thenReturn(null, persistedRound(BUCKET));
        when(roundDao.insertPendingRoundIgnoreConflict(any())).thenReturn(1);
        when(featureBuildService.buildFeatures(BUCKET)).thenReturn(List.of(feature(1)));

        int rebuilt = service.rebuildHistory(BUCKET, BUCKET.plusMinutes(15));

        assertEquals(1, rebuilt, "feature缺失桶必须计入实际重建");
        verify(barBuildService, never()).buildBars(any());
        verify(featureBuildService).buildFeatures(BUCKET);
        verify(roundDao).insertPendingRoundIgnoreConflict(any());
        assertLastRoundStatus(StockRoundStatusEnum.READY.getCode());
    }

    @Test
    @DisplayName("round缺失恢复_bar与feature存在round缺失_不重建数据创建round置READY")
    void rebuild_roundMissing_createsReadyRound() {
        when(bar15mDao.selectByBarStartTime(BUCKET, Stock15mBarBuildService.BUILD_VERSION))
                .thenReturn(List.of(bar(1)));
        when(feature15mDao.selectByBarStartTime(BUCKET, Stock15mFeatureBuildService.FEATURE_VERSION))
                .thenReturn(List.of(feature(1)));
        when(roundDao.selectByRoundTime(BUCKET)).thenReturn(null, persistedRound(BUCKET));
        when(roundDao.insertPendingRoundIgnoreConflict(any())).thenReturn(1);

        int rebuilt = service.rebuildHistory(BUCKET, BUCKET.plusMinutes(15));

        assertEquals(1, rebuilt, "round缺失桶必须计入实际重建");
        verify(barBuildService, never()).buildBars(any());
        verify(featureBuildService, never()).buildFeatures(any());
        verify(roundDao).insertPendingRoundIgnoreConflict(any());
        assertLastRoundStatus(StockRoundStatusEnum.READY.getCode());
    }

    @Test
    @DisplayName("可重试round恢复_bar与feature存在round=FAILED_RETRYABLE_不重复写数据置READY")
    void rebuild_retryableRound_restoresReadyWithoutRewritingData() {
        when(bar15mDao.selectByBarStartTime(BUCKET, Stock15mBarBuildService.BUILD_VERSION))
                .thenReturn(List.of(bar(1)));
        when(feature15mDao.selectByBarStartTime(BUCKET, Stock15mFeatureBuildService.FEATURE_VERSION))
                .thenReturn(List.of(feature(1)));
        TornStockMarketRoundDO retryable = existingRound(5L, StockRoundStatusEnum.FAILED_RETRYABLE.getCode());
        when(roundDao.selectByRoundTime(BUCKET)).thenReturn(retryable);

        int rebuilt = service.rebuildHistory(BUCKET, BUCKET.plusMinutes(15));

        assertEquals(1, rebuilt, "可重试round桶必须计入实际重建");
        verify(roundDao, never()).insertPendingRoundIgnoreConflict(any());
        verify(barBuildService, never()).buildBars(any());
        verify(featureBuildService, never()).buildFeatures(any());
        assertLastRoundStatus(StockRoundStatusEnum.READY.getCode());
    }

    @Test
    @DisplayName("完整桶跳过_bar+feature完整round=COMPLETED版本一致_无任何写入")
    void rebuild_completeBucket_skipsWithoutWrites() {
        when(bar15mDao.selectByBarStartTime(BUCKET, Stock15mBarBuildService.BUILD_VERSION))
                .thenReturn(List.of(bar(1)));
        when(feature15mDao.selectByBarStartTime(BUCKET, Stock15mFeatureBuildService.FEATURE_VERSION))
                .thenReturn(List.of(feature(1)));
        TornStockMarketRoundDO completed = existingRound(6L, StockRoundStatusEnum.COMPLETED.getCode());
        when(roundDao.selectByRoundTime(BUCKET)).thenReturn(completed);

        int rebuilt = service.rebuildHistory(BUCKET, BUCKET.plusMinutes(15));

        assertEquals(0, rebuilt, "完整桶不得计入实际重建");
        verify(roundDao, never()).updateById(any());
        verify(roundDao, never()).insertPendingRoundIgnoreConflict(any());
        verify(barBuildService, never()).buildBars(any());
        verify(featureBuildService, never()).buildFeatures(any());
    }

    @Test
    @DisplayName("FAILED_FINAL终态保留_bar未查询且无任何写入")
    void rebuild_failedFinal_keepsTerminalStateAndSkips() {
        TornStockMarketRoundDO failedFinal = existingRound(7L, StockRoundStatusEnum.FAILED_FINAL.getCode());
        failedFinal.setErrorMessage("历史最终失败");
        when(roundDao.selectByRoundTime(BUCKET)).thenReturn(failedFinal);

        int rebuilt = service.rebuildHistory(BUCKET, BUCKET.plusMinutes(15));

        assertEquals(0, rebuilt, "FAILED_FINAL不得自动重开");
        verify(roundDao, never()).updateById(any());
        verify(roundDao, never()).insertPendingRoundIgnoreConflict(any());
        verify(bar15mDao, never()).selectByBarStartTime(any(), any());
        verify(feature15mDao, never()).selectByBarStartTime(any(), any());
    }

    private void assertLastRoundStatus(String expectedStatus) {
        ArgumentCaptor<TornStockMarketRoundDO> captor = ArgumentCaptor.forClass(TornStockMarketRoundDO.class);
        verify(roundDao, atLeastOnce()).updateById(captor.capture());
        String lastStatus = captor.getAllValues().getLast().getRoundStatus();
        assertEquals(expectedStatus, lastStatus, "轮次最终状态必须为READY");
    }

    private TornStockMarketBar15mDO bar(int stock) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(stock);
        bar.setBarStartTime(BUCKET);
        bar.setBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
        return bar;
    }

    private TornStockStrategyFeature15mDO feature(int stock) {
        TornStockStrategyFeature15mDO feature = new TornStockStrategyFeature15mDO();
        feature.setStocksId(stock);
        feature.setBarStartTime(BUCKET);
        feature.setFeatureVersion(Stock15mFeatureBuildService.FEATURE_VERSION);
        return feature;
    }

    private TornStockMarketRoundDO persistedRound(LocalDateTime bucket) {
        TornStockMarketRoundDO round = existingRound(1L, StockRoundStatusEnum.PENDING.getCode());
        round.setRoundTime(bucket);
        return round;
    }

    private TornStockMarketRoundDO existingRound(Long id, String status) {
        TornStockMarketRoundDO round = new StockMarketRoundFactory()
                .createRound(BUCKET, status);
        round.setId(id);
        round.setStartedAt(NOW);
        return round;
    }
}
