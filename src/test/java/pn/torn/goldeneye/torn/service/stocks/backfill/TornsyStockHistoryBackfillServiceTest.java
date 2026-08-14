package pn.torn.goldeneye.torn.service.stocks.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.configuration.property.StockHistoryBackfillProperty;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockHistoryMinuteSlot;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksHistoryDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockHistoryRebuildService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tornsy 股票历史回填服务单元测试 - 覆盖已有分钟跳过、缺失分钟插入、来源与可空字段、实际插入槽驱动重建
 * <p>
 * 验证 {@link TornsyStockHistoryBackfillService} 只补缺不覆盖、投资人固定 NULL、市值未知为 NULL、
 * 来源为 TORNSY_BACKFILL；并仅以 SQL 实际插入槽（RETURNING）驱动派生数据修复：
 * 冲突跳过行不产生重建义务，受影响桶从最早到最晚桶后 30 天重算 feature。
 *
 * @author Bai
 * @version 1.2.18
 * @since 2026.08.14
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Tornsy 股票历史回填服务测试")
class TornsyStockHistoryBackfillServiceTest {

    @Mock
    private TornStocksDAO stocksDao;
    @Mock
    private TornStocksHistoryDAO stocksHistoryDao;
    @Mock
    private TornsyStockHistoryClient client;
    @Mock
    private TornsyMinuteQuoteParser parser;
    @Mock
    private StockHistoryRebuildService rebuildService;
    @Mock
    private StockHistoryBackfillProperty property;

    @InjectMocks
    private TornsyStockHistoryBackfillService service;

    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 1, 10, 0);
    private static final LocalDateTime END = START.plusHours(1);

    private TornStocksDO stock() {
        TornStocksDO stock = new TornStocksDO();
        stock.setId(32);
        stock.setStocksName("Alcoholics Synonymous");
        stock.setStocksShortname("ASS");
        return stock;
    }

    private void stubSlice(List<TornsyMinuteQuote> quotes) {
        when(property.getPageLimit()).thenReturn(1000);
        List<JsonNode> rows = new ArrayList<>();
        for (int i = 0; i < quotes.size(); i++) {
            rows.add(JsonNodeFactory.instance.arrayNode().add(0L).add("1").add(1L));
        }
        when(client.fetchMinuteData(eq("ASS"), anyLong(), anyLong(), eq(1000))).thenReturn(rows);
        when(parser.parse(anyList(), eq(START), eq(END), eq(END))).thenReturn(quotes);
    }

    private void stubExistingSlots(List<StockHistoryMinuteSlot> existing) {
        when(stocksHistoryDao.selectExistingMinuteSlots(anyList(), eq(START), eq(END))).thenReturn(existing);
    }

    /**
     * 装配派生数据修复返回桩(仅数据层统计:强制bar桶/数据修复终态轮次/重算feature桶/无bar跳过桶),
     * 避免服务对空结果执行统计。
     */
    private void stubRepairResult() {
        when(rebuildService.repairBackfilledHistory(anyCollection(), any(), anyString()))
                .thenReturn(new StockHistoryRebuildService.BackfillRepairResult(1, 1, 2, 0));
    }

    @Test
    @DisplayName("回填_已有分钟 -> 跳过写入并计入existedSkippedRows")
    void backfillStocks_existingMinutes_skipsInsert() {
        stubSlice(List.of(
                new TornsyMinuteQuote(START.plusMinutes(5), new BigDecimal("10.00"), 100L, null),
                new TornsyMinuteQuote(START.plusMinutes(6), new BigDecimal("11.00"), 100L, null)));
        stubExistingSlots(List.of(
                new StockHistoryMinuteSlot(32, START.plusMinutes(5)),
                new StockHistoryMinuteSlot(32, START.plusMinutes(6))));

        TornsyStockHistoryBackfillService.BackfillSummary summary =
                service.backfillStocks(List.of(stock()), START, END);

        assertEquals(0, summary.insertedRows());
        assertEquals(2, summary.existedSkippedRows());
        verify(stocksHistoryDao, never()).insertBackfillReturningSlots(anyList());
        verify(rebuildService, never()).repairBackfilledHistory(anyCollection(), any(), anyString());
    }

    @Test
    @DisplayName("回填_缺失分钟 -> 插入且来源TORNSY_BACKFILL/投资人null/市值未知null")
    void backfillStocks_missingMinutes_insertsWithBackfillSourceAndNullFields() {
        stubSlice(List.of(
                new TornsyMinuteQuote(START.plusMinutes(5), new BigDecimal("10.00"), 100L, null),
                new TornsyMinuteQuote(START.plusMinutes(6), new BigDecimal("11.00"), 100L, 5000L)));
        stubExistingSlots(List.of());
        stubRepairResult();
        when(stocksHistoryDao.insertBackfillReturningSlots(anyList())).thenReturn(List.of(
                new StockHistoryMinuteSlot(32, START.plusMinutes(5)),
                new StockHistoryMinuteSlot(32, START.plusMinutes(6))));

        TornsyStockHistoryBackfillService.BackfillSummary summary =
                service.backfillStocks(List.of(stock()), START, END);

        assertEquals(2, summary.insertedRows());
        assertEquals(0, summary.existedSkippedRows());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TornStocksHistoryDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(stocksHistoryDao).insertBackfillReturningSlots(captor.capture());
        List<TornStocksHistoryDO> inserted = captor.getValue();
        assertEquals(2, inserted.size());

        TornStocksHistoryDO first = inserted.getFirst();
        assertEquals(32, first.getStocksId());
        assertEquals("ASS", first.getStocksShortname());
        assertEquals(StockHistoryDataSourceEnum.TORNSY_BACKFILL.getCode(), first.getDataSource());
        assertNull(first.getInvestors());
        assertNull(first.getMarketCap());

        TornStocksHistoryDO second = inserted.get(1);
        assertEquals(5000L, second.getMarketCap());
    }

    @Test
    @DisplayName("回填_实际插入分钟映射到正确15分钟桶 -> 强制bar重建并按最晚桶后30天重算feature")
    void backfillStocks_insertedMinutes_repairsAffectedBucketsAnd30DayRange() {
        stubSlice(List.of(
                new TornsyMinuteQuote(LocalDateTime.of(2026, 8, 1, 10, 5), new BigDecimal("10.00"), 100L, null),
                new TornsyMinuteQuote(LocalDateTime.of(2026, 8, 1, 10, 20), new BigDecimal("11.00"), 100L, null)));
        stubExistingSlots(List.of());
        stubRepairResult();
        when(stocksHistoryDao.insertBackfillReturningSlots(anyList())).thenReturn(List.of(
                new StockHistoryMinuteSlot(32, LocalDateTime.of(2026, 8, 1, 10, 5)),
                new StockHistoryMinuteSlot(32, LocalDateTime.of(2026, 8, 1, 10, 20))));

        service.backfillStocks(List.of(stock()), START, END);

        // 10:05 -> 10:00 桶, 10:20 -> 10:15 桶; 最晚10:15后30天+15分钟为结束边界
        verify(rebuildService, times(1)).repairBackfilledHistory(
                eq(Set.of(LocalDateTime.of(2026, 8, 1, 10, 0), LocalDateTime.of(2026, 8, 1, 10, 15))),
                eq(LocalDateTime.of(2026, 8, 31, 10, 30)),
                anyString());
        verify(rebuildService, times(1)).repairBackfilledHistory(anyCollection(), any(), anyString());
    }

    @Test
    @DisplayName("回填_冲突插入0 -> 不产生派生数据修复")
    void backfillStocks_conflictInsertZero_noRebuild() {
        stubSlice(List.of(
                new TornsyMinuteQuote(START.plusMinutes(5), new BigDecimal("10.00"), 100L, null)));
        stubExistingSlots(List.of());
        when(stocksHistoryDao.insertBackfillReturningSlots(anyList())).thenReturn(List.of());

        TornsyStockHistoryBackfillService.BackfillSummary summary =
                service.backfillStocks(List.of(stock()), START, END);

        assertEquals(0, summary.insertedRows());
        assertEquals(1, summary.existedSkippedRows(), "冲突跳过行计入existedSkippedRows");
        verify(rebuildService, never()).repairBackfilledHistory(anyCollection(), any(), anyString());
    }

    @Test
    @DisplayName("回填_部分冲突 -> 仅实际插入槽驱动重建")
    void backfillStocks_partialConflict_onlyActualSlotsDriveRebuild() {
        stubSlice(List.of(
                new TornsyMinuteQuote(LocalDateTime.of(2026, 8, 1, 10, 5), new BigDecimal("10.00"), 100L, null),
                new TornsyMinuteQuote(LocalDateTime.of(2026, 8, 1, 10, 20), new BigDecimal("11.00"), 100L, null),
                new TornsyMinuteQuote(LocalDateTime.of(2026, 8, 1, 10, 35), new BigDecimal("12.00"), 100L, null)));
        stubExistingSlots(List.of());
        stubRepairResult();
        // 3个候选,实际仅插入2个(10:05与10:20),10:35冲突跳过
        when(stocksHistoryDao.insertBackfillReturningSlots(anyList())).thenReturn(List.of(
                new StockHistoryMinuteSlot(32, LocalDateTime.of(2026, 8, 1, 10, 5)),
                new StockHistoryMinuteSlot(32, LocalDateTime.of(2026, 8, 1, 10, 20))));

        TornsyStockHistoryBackfillService.BackfillSummary summary =
                service.backfillStocks(List.of(stock()), START, END);

        assertEquals(2, summary.insertedRows());
        assertEquals(1, summary.existedSkippedRows());
        // 10:35 冲突跳过,不得进入受影响桶
        verify(rebuildService).repairBackfilledHistory(
                eq(Set.of(LocalDateTime.of(2026, 8, 1, 10, 0), LocalDateTime.of(2026, 8, 1, 10, 15))),
                any(), anyString());
    }

    @Test
    @DisplayName("回填_单桶分钟 -> 从最早受影响桶后30天+15分钟作为feature重算结束边界")
    void backfillStocks_featureRange_latestAffectedPlus30DaysPlus15Minutes() {
        stubSlice(List.of(
                new TornsyMinuteQuote(LocalDateTime.of(2026, 8, 1, 10, 5), new BigDecimal("10.00"), 100L, null)));
        stubExistingSlots(List.of());
        stubRepairResult();
        when(stocksHistoryDao.insertBackfillReturningSlots(anyList())).thenReturn(List.of(
                new StockHistoryMinuteSlot(32, LocalDateTime.of(2026, 8, 1, 10, 5))));

        service.backfillStocks(List.of(stock()), START, END);

        // 最晚受影响桶 10:00 + 30天 + 15分钟 = 2026-08-31 10:15
        verify(rebuildService).repairBackfilledHistory(
                eq(Set.of(LocalDateTime.of(2026, 8, 1, 10, 0))),
                eq(LocalDateTime.of(2026, 8, 31, 10, 15)),
                anyString());
    }

    @Test
    @DisplayName("回填_无合法报价 -> 不写入不重建")
    void backfillStocks_noValidQuotes_doesNothing() {
        stubSlice(List.of());
        stubExistingSlots(List.of());

        TornsyStockHistoryBackfillService.BackfillSummary summary =
                service.backfillStocks(List.of(stock()), START, END);

        assertEquals(0, summary.insertedRows());
        verify(stocksHistoryDao, never()).insertBackfillReturningSlots(anyList());
        verify(rebuildService, never()).repairBackfilledHistory(anyCollection(), any(), anyString());
    }
}
