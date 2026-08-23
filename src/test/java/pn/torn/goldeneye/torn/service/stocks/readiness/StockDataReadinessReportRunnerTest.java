package pn.torn.goldeneye.torn.service.stocks.readiness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import pn.torn.goldeneye.repository.dao.torn.stocks.readiness.StockDataReadinessQueryDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.*;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.replay.StockReplayReadOnlyGuard;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 数据就绪报告运行器单元测试。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("数据就绪报告运行器测试")
class StockDataReadinessReportRunnerTest {

    @Mock
    private StockDataReadinessReportWriter writer;
    @Mock
    private StockDataReadinessQueryDAO queryDao;
    @Mock
    private StockReplayReadOnlyGuard readOnlyGuard;
    @Mock
    private StockMarketClock marketClock;
    @InjectMocks
    private StockDataReadinessReportRunner runner;

    @Test
    @DisplayName("在单一只读回调中加载非零统计并交给Writer同一报告")
    void run_loadsRealSnapshotAndWritesReport() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 1, 2, 0, 0);
        when(marketClock.now()).thenReturn(LocalDateTime.of(2026, 1, 2, 1, 0));
        when(queryDao.countStocks()).thenReturn(35);
        when(queryDao.selectMinuteCoverageSummary(start, end)).thenReturn(new StockMinuteCoverageSummary(
                35, 0L, 1L, 2L, 3L, 0L, 0L,
                List.of(new StockMinuteCoverage(1, "TST", start.plusMinutes(1), end.minusMinutes(1), 1000L,
                        1L, 0L, 0L, 0L, 1L, 0L, 0L))));
        when(queryDao.selectMinuteSourceDistribution(start, end)).thenReturn(List.of(
                new SourceCount("TORN_API", 600L), new SourceCount("TORNSY_BACKFILL", 400L)));
        when(queryDao.selectValidMinuteCount(start, end)).thenReturn(1000L);
        when(queryDao.selectInvalidMinuteCount(start, end)).thenReturn(0L);
        when(queryDao.selectBarCount(start, end, "1.0.0")).thenReturn(900L);
        when(queryDao.selectUsableBarCount(start, end, "1.0.0")).thenReturn(800L);
        when(queryDao.selectUnusableBarReasonCounts(start, end, "1.0.0")).thenReturn(List.of(
                new NameCount("SAMPLE_TOO_FEW", 5L)));
        when(queryDao.selectFeatureCount(start, end, "1.0.0")).thenReturn(750L);
        when(queryDao.selectUsableBarMissingFeatureCount(start, end, "1.0.0", "1.0.0")).thenReturn(0L);
        when(queryDao.selectFeatureOrphanCount(start, end, "1.0.0", "1.0.0")).thenReturn(0L);
        when(queryDao.selectStrategyReadyFeatureCount(start, end, "1.0.0")).thenReturn(700L);
        when(queryDao.selectNotReadyFeatureReasonCounts(start, end, "1.0.0")).thenReturn(List.of(
                new NameCount("INSUFFICIENT_HISTORY", 10L)));
        when(queryDao.selectMonthlyStateCounts(start, end)).thenReturn(List.of());
        when(queryDao.selectMonthlyIncompleteReasonCounts(start, end)).thenReturn(List.of(
                new NameCount("MONTHLY_EVIDENCE_INCOMPLETE", 2L)));
        when(queryDao.selectRoundStatusCounts(start, end)).thenReturn(List.of(
                new RoundStatusCount("REPAIRED_DATA_ONLY", 100L),
                new RoundStatusCount("COMPLETED", 50L)));
        when(queryDao.selectRoundVersionMismatchCount(start, end, "1.0.0", "1.0.0")).thenReturn(1L);
        when(queryDao.selectVipStockSettings()).thenReturn(List.of(
                new SettingValue("VIP_STOCK_ALERT_ENABLED", "true"),
                new SettingValue("VIP_STOCK_RULE_MODE", "SHADOW")));
        when(readOnlyGuard.inReadOnlyTransaction(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(writer.write(any(), any())).thenAnswer(invocation -> ((Path) invocation.getArgument(0))
                .resolve("run-1-summary.json"));

        StockDataReadinessReportRunner.ReportRunResult result = runner.run(start, end);

        assertNotNull(result.path());
        assertNotNull(result.report());
        verify(readOnlyGuard, times(1)).inReadOnlyTransaction(any());
        ArgumentCaptor<StockDataReadinessReport> reportCaptor = ArgumentCaptor.forClass(StockDataReadinessReport.class);
        verify(writer).write(any(), reportCaptor.capture());
        StockDataReadinessReport report = reportCaptor.getValue();
        assertEquals(35, report.snapshot().stockCount());
        assertEquals(1000L, report.snapshot().validMinuteCount());
        assertEquals(900L, report.snapshot().barCount());
        assertEquals(750L, report.snapshot().featureCount());
        assertEquals(100L, report.snapshot().roundStatusCounts().get("REPAIRED_DATA_ONLY"));
        assertEquals(1, report.snapshot().stockMinuteCoverages().size());
        assertEquals(3L, report.snapshot().totalMissingStockMinutes());
        assertFalse(report.manifestHash().isBlank(), "manifestHash不得为空");
        assertEquals("1.0.0", report.barBuildVersion());
        assertEquals("1.0.0", report.featureVersion());
    }
}
