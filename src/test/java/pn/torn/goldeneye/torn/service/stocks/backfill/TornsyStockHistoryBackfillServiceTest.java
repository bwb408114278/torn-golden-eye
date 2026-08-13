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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tornsy 股票历史回填服务单元测试 - 覆盖已有分钟跳过、缺失分钟插入、来源与可空字段、定向重建编排
 * <p>
 * 验证 {@link TornsyStockHistoryBackfillService} 只补缺不覆盖、投资人固定 NULL、市值未知为 NULL、
 * 来源为 TORNSY_BACKFILL，并仅对实际插入分钟影响的 15 分钟桶调用定向重建。
 *
 * @author Bai
 * @version 1.2.15
 * @since 2026.08.13
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

    @Test
    @DisplayName("回填_已有分钟 -> 跳过写入并计入existedSkippedRows")
    void backfillStocks_existingMinutes_skipsInsert() {
        stubSlice(List.of(
                new TornsyMinuteQuote(START.plusMinutes(5), new BigDecimal("10.00"), 100L, null),
                new TornsyMinuteQuote(START.plusMinutes(6), new BigDecimal("11.00"), 100L, null)));
        when(stocksHistoryDao.selectExistingMinuteSlots(anyList(), eq(START), eq(END))).thenReturn(List.of(
                new StockHistoryMinuteSlot(32, START.plusMinutes(5)),
                new StockHistoryMinuteSlot(32, START.plusMinutes(6))));

        TornsyStockHistoryBackfillService.BackfillSummary summary =
                service.backfillStocks(List.of(stock()), START, END);

        assertEquals(0, summary.insertedRows());
        assertEquals(2, summary.existedSkippedRows());
        verify(stocksHistoryDao, never()).insertBackfillIgnoreConflict(anyList());
    }

    @Test
    @DisplayName("回填_缺失分钟 -> 插入且来源TORNSY_BACKFILL/投资人null/市值未知null")
    void backfillStocks_missingMinutes_insertsWithBackfillSourceAndNullFields() {
        stubSlice(List.of(
                new TornsyMinuteQuote(START.plusMinutes(5), new BigDecimal("10.00"), 100L, null),
                new TornsyMinuteQuote(START.plusMinutes(6), new BigDecimal("11.00"), 100L, 5000L)));
        when(stocksHistoryDao.selectExistingMinuteSlots(anyList(), eq(START), eq(END))).thenReturn(List.of());
        when(stocksHistoryDao.insertBackfillIgnoreConflict(anyList())).thenReturn(2);

        TornsyStockHistoryBackfillService.BackfillSummary summary =
                service.backfillStocks(List.of(stock()), START, END);

        assertEquals(2, summary.insertedRows());
        assertEquals(0, summary.existedSkippedRows());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TornStocksHistoryDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(stocksHistoryDao).insertBackfillIgnoreConflict(captor.capture());
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
    @DisplayName("回填_插入分钟映射到正确15分钟桶 -> 仅重建受影响区间")
    void backfillStocks_insertedMinutes_rebuildsAffectedBucketsOnly() {
        stubSlice(List.of(
                new TornsyMinuteQuote(LocalDateTime.of(2026, 8, 1, 10, 5), new BigDecimal("10.00"), 100L, null),
                new TornsyMinuteQuote(LocalDateTime.of(2026, 8, 1, 10, 20), new BigDecimal("11.00"), 100L, null)));
        when(stocksHistoryDao.selectExistingMinuteSlots(anyList(), eq(START), eq(END))).thenReturn(List.of());
        when(stocksHistoryDao.insertBackfillIgnoreConflict(anyList())).thenReturn(2);

        service.backfillStocks(List.of(stock()), START, END);

        // 10:05 -> 10:00 桶, 10:20 -> 10:15 桶, 相邻合并为 [10:00, 10:30)
        verify(rebuildService, times(1)).rebuildHistory(
                LocalDateTime.of(2026, 8, 1, 10, 0),
                LocalDateTime.of(2026, 8, 1, 10, 30));
        verify(rebuildService, times(1)).rebuildHistory(any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("回填_无合法报价 -> 不写入不重建")
    void backfillStocks_noValidQuotes_doesNothing() {
        stubSlice(List.of());
        when(stocksHistoryDao.selectExistingMinuteSlots(anyList(), eq(START), eq(END))).thenReturn(List.of());

        TornsyStockHistoryBackfillService.BackfillSummary summary =
                service.backfillStocks(List.of(stock()), START, END);

        assertEquals(0, summary.insertedRows());
        verify(stocksHistoryDao, never()).insertBackfillIgnoreConflict(anyList());
        verify(rebuildService, never()).rebuildHistory(any(LocalDateTime.class), any(LocalDateTime.class));
    }
}
