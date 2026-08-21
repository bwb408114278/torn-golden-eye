package pn.torn.goldeneye.torn.manager.torn.stocks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksHistoryDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.torn.model.torn.stocks.*;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketClock;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 股票实时采集管理器单元测试 - 覆盖计划分钟键、冲突安全写入下游控制、防重入与异常释放。
 * <p>
 * 验证 {@link TornStocksManager#spiderStockData()}:
 * <ul>
 *   <li>开始时间 {@code 10:15:27} 时写入 {@code 10:15:00} 计划自然分钟键;</li>
 *   <li>全量插入才异步派发大额交易消息与旧分钟特征处理;</li>
 *   <li>全冲突跳过时不发消息、不推进旧特征游标;</li>
 *   <li>部分冲突 fail-closed 抛异常且不发消息;</li>
 *   <li>同 JVM 重入不发 API;</li>
 *   <li>API 异常后释放防重入标记。</li>
 * </ul>
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.08.14
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("股票实时采集管理器测试")
class TornStocksManagerTest {

    /**
     * 采集方法实际开始时间（秒与纳秒非零）
     */
    private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 8, 14, 10, 15, 27);
    /**
     * 计划自然分钟采样键（秒与纳秒清零）
     */
    private static final LocalDateTime PLANNED_MINUTE = LocalDateTime.of(2026, 8, 14, 10, 15, 0);

    @Mock
    private ThreadPoolTaskExecutor virtualThreadExecutor;
    @Mock
    private Bot bot;
    @Mock
    private TornApi tornApi;
    @Mock
    private StockFeatureBuildService featureBuildService;
    @Mock
    private SysSettingManager settingManager;
    @Mock
    private TornItemsManager itemsManager;
    @Mock
    private TornStocksDAO stocksDao;
    @Mock
    private TornStocksHistoryDAO stocksHistoryDao;
    @Mock
    private ProjectProperty projectProperty;
    @Mock
    private StockMarketClock marketClock;
    @Mock
    private StockCollectionLogSummary collectionLogSummary;

    @InjectMocks
    private TornStocksManager manager;

    /**
     * 构造真实股票详情 VO（避免 mock convert 方法）。
     *
     * @return 股票详情 VO
     */
    private TornStocksDetailVO stockDetail() {
        TornStocksMarketVO market = new TornStocksMarketVO();
        market.setPrice(new BigDecimal("100"));
        market.setCap(1_000L);
        market.setShares(10_000L);
        market.setInvestors(100);
        TornStocksBonusVO bonus = new TornStocksBonusVO();
        bonus.setDescription("test bonus");
        bonus.setFrequency(1);
        bonus.setRequirement(1);
        TornStocksDetailVO detail = new TornStocksDetailVO();
        detail.setId(32);
        detail.setName("Test Stock");
        detail.setAcronym("TST");
        detail.setMarket(market);
        detail.setBonus(bonus);
        return detail;
    }

    /**
     * 装配通用采集桩：生产环境、固定开始时间、指定数量的股票详情与历史插入结果。
     *
     * @param stockCount 股票详情数量
     * @param inserted   历史实际插入行数
     */
    private void stubCommon(int stockCount, int inserted) {
        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(marketClock.now()).thenReturn(STARTED_AT);
        List<TornStocksDetailVO> details = IntStream.range(0, stockCount)
                .mapToObj(i -> stockDetail()).toList();
        TornStocksVO resp = new TornStocksVO();
        resp.setStocks(details);
        when(tornApi.sendRequest(any(TornStocksDTO.class), eq(TornStocksVO.class))).thenReturn(resp);
        when(stocksDao.list()).thenReturn(List.of());
        when(stocksHistoryDao.insertRealtimeIgnoreConflict(anyList())).thenReturn(inserted);
    }

    /**
     * 装配日志汇总组件空结果桩（仅全量插入成功路径需要）。
     */
    private void stubCollectionLogSummary() {
        when(collectionLogSummary.recordSuccess(any()))
                .thenReturn(StockCollectionLogSummary.WindowRecordResult.empty());
    }

    @Test
    @DisplayName("实时采集_开始时间10:15:27写入计划自然分钟键10:15:00")
    void spiderStockData_writesPlannedMinuteToHistory() {
        stubCommon(1, 1);
        stubCollectionLogSummary();

        manager.spiderStockData();

        ArgumentCaptor<List<TornStocksHistoryDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(stocksHistoryDao).insertRealtimeIgnoreConflict(captor.capture());
        assertEquals(PLANNED_MINUTE, captor.getValue().getFirst().getRegDateTime(),
                "历史事实键必须为计划自然分钟(秒与纳秒清零)");
    }

    @Test
    @DisplayName("实时采集_全量插入成功_异步派发大额交易与旧分钟特征")
    void spiderStockData_fullInsert_dispatchesDownstreamAsync() {
        stubCommon(1, 1);
        stubCollectionLogSummary();

        manager.spiderStockData();

        verify(virtualThreadExecutor, times(2)).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("实时采集_本分钟已写入(全冲突)_不发消息不推进旧特征游标")
    void spiderStockData_fullConflict_skipsDownstream() {
        stubCommon(1, 0);

        manager.spiderStockData();

        verify(stocksHistoryDao).insertRealtimeIgnoreConflict(anyList());
        verify(virtualThreadExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("实时采集_部分分钟冲突_fail-closed抛异常且不发消息")
    void spiderStockData_partialConflict_failClosedThrows() {
        stubCommon(2, 1);

        assertThrows(IllegalStateException.class, manager::spiderStockData,
                "部分冲突必须fail-closed抛出");
        verify(virtualThreadExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("实时采集_全量插入成功_向日志汇总组件提交成功窗口指标")
    void spiderStockData_fullInsert_submitsCollectionLogSummary() {
        stubCommon(1, 1);
        stubCollectionLogSummary();

        manager.spiderStockData();

        verify(collectionLogSummary).recordSuccess(any(StockCollectionLogSummary.MinuteMetric.class));
    }

    @Test
    @DisplayName("实时采集_本分钟已写入(全冲突)_不向日志汇总组件提交成功窗口指标")
    void spiderStockData_fullConflict_doesNotSubmitCollectionLogSummary() {
        stubCommon(1, 0);

        manager.spiderStockData();

        verify(collectionLogSummary, never()).recordSuccess(any(StockCollectionLogSummary.MinuteMetric.class));
    }

    @Test
    @DisplayName("实时采集_部分分钟冲突_不向日志汇总组件提交成功窗口指标")
    void spiderStockData_partialConflict_doesNotSubmitCollectionLogSummary() {
        stubCommon(2, 1);

        assertThrows(IllegalStateException.class, manager::spiderStockData,
                "部分冲突必须fail-closed抛出");
        verify(collectionLogSummary, never()).recordSuccess(any(StockCollectionLogSummary.MinuteMetric.class));
    }

    @Test
    @DisplayName("实时采集_同JVM重入_跳过本次不发API")
    void spiderStockData_reentrant_skipsApiCall() {
        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        ReflectionTestUtils.setField(manager, "realtimeProcessing", new AtomicBoolean(true));

        manager.spiderStockData();

        verify(tornApi, never()).sendRequest(any(TornStocksDTO.class), eq(TornStocksVO.class));
        verify(stocksHistoryDao, never()).insertRealtimeIgnoreConflict(anyList());
    }

    @Test
    @DisplayName("实时采集_API异常_向上抛出并释放防重入标记")
    void spiderStockData_exception_releasesReentrancyFlag() {
        when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(marketClock.now()).thenReturn(STARTED_AT);
        when(tornApi.sendRequest(any(TornStocksDTO.class), eq(TornStocksVO.class)))
                .thenThrow(new RuntimeException("api fail"));

        assertThrows(RuntimeException.class, manager::spiderStockData);

        AtomicBoolean flag = (AtomicBoolean) ReflectionTestUtils.getField(manager, "realtimeProcessing");
        assertFalse(flag.get(), "异常后必须释放防重入标记");
    }
}
