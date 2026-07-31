package pn.torn.goldeneye.torn.service.stocks.alert;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalEventDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 股票日报数据质量测试，验证权益缺失时仍展示现金和缺失行情明细。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.17
 */
@DisplayName("股票日报数据质量测试")
class StockDailySummaryServiceTest {

    @Test
    @DisplayName("开放仓位缺少行情_权益不可用但应展示排序后的缺失股票和现金预留资金")
    void buildSummaryData_missingOpenPositionPrice_returnsDataInsufficientEquityResult() {
        TornStockPortfolioSlotDAO slotDao = mock(TornStockPortfolioSlotDAO.class);
        TornStockVirtualBatchDAO batchDao = mock(TornStockVirtualBatchDAO.class);
        TornStockMarketBar15mDAO barDao = mock(TornStockMarketBar15mDAO.class);
        TornStockSignalEventDAO signalEventDao = mock(TornStockSignalEventDAO.class);
        StockDailySummaryService service = service(slotDao, batchDao, barDao, signalEventDao);
        stubSummaryQueries(batchDao, signalEventDao);

        when(slotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE)).thenReturn(List.of(
                slot(1L, new BigDecimal("100.00"), new BigDecimal("20.00"))));
        when(batchDao.selectActiveFormalBatches()).thenReturn(List.of(
                openBatch(2, "MUN", 1L), openBatch(1, "TCC", 1L)));
        when(barDao.selectLatestUsableByStocks(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(Stock15mBarBuildService.BUILD_VERSION)))
                .thenReturn(List.of(usableBar(2, new BigDecimal("10.00"))));

        StockDailySummaryService.DailySummaryData data = service.buildSummaryData(LocalDate.of(2026, 7, 30));

        assertNull(data.formal().equity());
        assertEquals(new BigDecimal("120.00"), data.formal().cashAndReserved());
        assertEquals(List.of("TCC"), data.formal().missingPriceStocks());
    }

    @Test
    @DisplayName("无开放仓位_权益应等于可用现金与预留资金")
    void buildSummaryData_noOpenPositions_calculatesCompleteCashEquity() {
        TornStockPortfolioSlotDAO slotDao = mock(TornStockPortfolioSlotDAO.class);
        TornStockVirtualBatchDAO batchDao = mock(TornStockVirtualBatchDAO.class);
        TornStockSignalEventDAO signalEventDao = mock(TornStockSignalEventDAO.class);
        StockDailySummaryService service = service(slotDao, batchDao, mock(TornStockMarketBar15mDAO.class), signalEventDao);
        stubSummaryQueries(batchDao, signalEventDao);

        when(slotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE)).thenReturn(List.of(
                slot(1L, new BigDecimal("100.00"), new BigDecimal("20.00"))));
        when(batchDao.selectActiveFormalBatches()).thenReturn(List.of());

        StockDailySummaryService.DailySummaryData data = service.buildSummaryData(LocalDate.of(2026, 7, 30));

        assertEquals(new BigDecimal("120.00"), data.formal().equity());
        assertEquals(new BigDecimal("120.00"), data.formal().cashAndReserved());
        assertEquals(List.of(), data.formal().missingPriceStocks());
    }

    /**
     * 创建日报服务。
     *
     * @param slotDao 槽位DAO
     * @param batchDao 批次DAO
     * @param barDao 行情DAO
     * @return 日报服务
     */
    private StockDailySummaryService service(TornStockPortfolioSlotDAO slotDao,
                                             TornStockVirtualBatchDAO batchDao,
                                             TornStockMarketBar15mDAO barDao,
                                             TornStockSignalEventDAO signalEventDao) {
        return new StockDailySummaryService(slotDao, batchDao, signalEventDao,
                mock(TornStockNoticeAuditDAO.class), barDao, new StockPortfolioService(),
                mock(pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendService.class),
                mock(StockMarketClock.class), mock(pn.torn.goldeneye.configuration.property.ProjectProperty.class),
                mock(pn.torn.goldeneye.torn.manager.setting.SysSettingManager.class));
    }

    /**
     * 配置日报中与权益无关的查询返回空结果。
     *
     * @param batchDao 批次DAO
     */
    private void stubSummaryQueries(TornStockVirtualBatchDAO batchDao, TornStockSignalEventDAO signalEventDao) {
        when(batchDao.selectFormalActionBatches(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(batchDao.selectShadowActionBatches(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        LambdaQueryChainWrapper<TornStockSignalEventDO> query = mock(LambdaQueryChainWrapper.class);
        when(signalEventDao.lambdaQuery()).thenReturn(query);
        when(query.ge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.lt(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.list()).thenReturn(List.of());
    }

    /**
     * 构建槽位。
     *
     * @param id 槽位ID
     * @param availableCash 可用现金
     * @param reservedCash 预留资金
     * @return 槽位
     */
    private TornStockPortfolioSlotDO slot(long id, BigDecimal availableCash, BigDecimal reservedCash) {
        TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
        slot.setId(id);
        slot.setAvailableCash(availableCash);
        slot.setReservedCash(reservedCash);
        return slot;
    }

    /**
     * 构建开放正式批次。
     *
     * @param stocksId 股票ID
     * @param shortname 股票简称
     * @param slotId 槽位ID
     * @return 正式批次
     */
    private TornStockVirtualBatchDO openBatch(int stocksId, String shortname, long slotId) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setStocksId(stocksId);
        batch.setStocksShortname(shortname);
        batch.setSlotId(slotId);
        batch.setQuantity(1L);
        batch.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        return batch;
    }

    /**
     * 构建可用行情bar。
     *
     * @param stocksId 股票ID
     * @param lastPrice 最新价格
     * @return 可用行情bar
     */
    private TornStockMarketBar15mDO usableBar(int stocksId, BigDecimal lastPrice) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(stocksId);
        bar.setUsable(true);
        bar.setLastPrice(lastPrice);
        return bar;
    }
}
