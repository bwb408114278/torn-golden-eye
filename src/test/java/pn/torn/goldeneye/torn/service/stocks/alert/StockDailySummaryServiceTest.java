package pn.torn.goldeneye.torn.service.stocks.alert;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.*;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 股票日报数据质量测试，验证权益缺失时仍展示现金和缺失行情明细。
 *
 * @author Bai
 * @version 1.2.14
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
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
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

    @Test
    @DisplayName("历史可用行情超过三十分钟_权益应降级为数据不足")
    void buildSummaryData_staleUsableBar_returnsDataInsufficientEquityResult() {
        TornStockPortfolioSlotDAO slotDao = mock(TornStockPortfolioSlotDAO.class);
        TornStockVirtualBatchDAO batchDao = mock(TornStockVirtualBatchDAO.class);
        TornStockMarketBar15mDAO barDao = mock(TornStockMarketBar15mDAO.class);
        TornStockSignalEventDAO signalEventDao = mock(TornStockSignalEventDAO.class);
        StockMarketClock marketClock = mock(StockMarketClock.class);
        StockDailySummaryService service = service(slotDao, batchDao, barDao, signalEventDao, marketClock);
        stubSummaryQueries(batchDao, signalEventDao);

        when(slotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE)).thenReturn(List.of(
                slot(1L, new BigDecimal("100.00"), BigDecimal.ZERO)));
        when(batchDao.selectActiveFormalBatches()).thenReturn(List.of(openBatch(1, "TCC", 1L)));
        when(marketClock.now()).thenReturn(LocalDateTime.of(2026, 7, 31, 10, 30));
        when(marketClock.currentEndedBucket()).thenReturn(LocalDateTime.of(2026, 7, 31, 10, 15));
        TornStockMarketBar15mDO staleBar = usableBar(1, new BigDecimal("10.00"));
        staleBar.setBarEndTime(LocalDateTime.of(2026, 7, 31, 9, 45));
        when(barDao.selectLatestUsableByStocks(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(Stock15mBarBuildService.BUILD_VERSION)))
                .thenReturn(List.of(staleBar));

        StockDailySummaryService.DailySummaryData data = service.buildSummaryData(LocalDate.of(2026, 7, 30));

        assertNull(data.formal().equity());
        assertEquals(List.of("TCC"), data.formal().missingPriceStocks());
        assertNull(data.formal().priceAsOf());
    }

    @Test
    @DisplayName("多个开放仓位均有新鲜行情_估值时点应取实际参与估值行情的最早结束时间")
    void buildSummaryData_freshBars_usesEarliestActualBarEndTimeAsPriceAsOf() {
        TornStockPortfolioSlotDAO slotDao = mock(TornStockPortfolioSlotDAO.class);
        TornStockVirtualBatchDAO batchDao = mock(TornStockVirtualBatchDAO.class);
        TornStockMarketBar15mDAO barDao = mock(TornStockMarketBar15mDAO.class);
        TornStockSignalEventDAO signalEventDao = mock(TornStockSignalEventDAO.class);
        StockDailySummaryService service = service(slotDao, batchDao, barDao, signalEventDao);
        stubSummaryQueries(batchDao, signalEventDao);

        when(slotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE)).thenReturn(List.of(
                slot(1L, new BigDecimal("100.00"), BigDecimal.ZERO),
                slot(2L, new BigDecimal("100.00"), BigDecimal.ZERO)));
        when(batchDao.selectActiveFormalBatches()).thenReturn(List.of(
                openBatch(1, "TCC", 1L), openBatch(2, "MUN", 2L)));
        TornStockMarketBar15mDO earlierBar = usableBar(1, new BigDecimal("10.00"));
        earlierBar.setBarEndTime(LocalDateTime.of(2026, 7, 31, 10, 0));
        TornStockMarketBar15mDO laterBar = usableBar(2, new BigDecimal("10.00"));
        laterBar.setBarEndTime(LocalDateTime.of(2026, 7, 31, 10, 15));
        when(barDao.selectLatestUsableByStocks(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(Stock15mBarBuildService.BUILD_VERSION)))
                .thenReturn(List.of(earlierBar, laterBar));

        StockDailySummaryService.DailySummaryData data = service.buildSummaryData(LocalDate.of(2026, 7, 30));

        assertEquals(LocalDateTime.of(2026, 7, 31, 10, 0), data.formal().priceAsOf());
    }

    @Test
    @DisplayName("行情不足文本_应以真实换行展示且不包含字面量百分号n")
    void buildSummaryText_dataInsufficient_usesActualLineBreaks() {
        StockDailySummaryService service = service(mock(TornStockPortfolioSlotDAO.class),
                mock(TornStockVirtualBatchDAO.class), mock(TornStockMarketBar15mDAO.class),
                mock(TornStockSignalEventDAO.class));
        StockDailySummaryService.FormalSummary formal = new StockDailySummaryService.FormalSummary(
                1, null, new BigDecimal("120.00"), List.of("TCC"), null,
                0, 0, BigDecimal.ZERO, List.of("TCC"), 0, LocalDate.of(2026, 7, 30));
        StockDailySummaryService.CandidateShadowSummary candidateShadow =
                new StockDailySummaryService.CandidateShadowSummary(
                        0, null, new BigDecimal("0.00"), List.of(), 0, 0, BigDecimal.ZERO, List.of());
        StockDailySummaryService.ShadowSummary shadow = new StockDailySummaryService.ShadowSummary(0, 0, 0, 0, 0, 0, 0);

        String summaryText = service.buildSummaryText(new StockDailySummaryService.DailySummaryData(formal, candidateShadow, shadow));

        assertTrue(summaryText.contains("- 缺失行情：TCC" + System.lineSeparator()
                + "- 可用现金及预留资金：120.00"));
        assertFalse(summaryText.contains("%n"));
        assertTrue(summaryText.contains("动态SELL研究：无研究输入"),
                "无研究输入时应展示无研究输入而非伪造0%");
        assertFalse(summaryText.contains("动态卖出影子建议"), "不得出现动态卖出影子建议字样");
    }

    @Test
    @DisplayName("历史批次exitReason含DYNAMIC_不得计为建议且日报以研究mark表达")
    void buildSummaryData_historicalDynamicExitReason_notCountedAsSuggestion() {
        TornStockPortfolioSlotDAO slotDao = mock(TornStockPortfolioSlotDAO.class);
        TornStockVirtualBatchDAO batchDao = mock(TornStockVirtualBatchDAO.class);
        TornStockMarketBar15mDAO barDao = mock(TornStockMarketBar15mDAO.class);
        TornStockSignalEventDAO signalEventDao = mock(TornStockSignalEventDAO.class);
        TornStockBatchMarkDAO markDao = mock(TornStockBatchMarkDAO.class);
        StockDailySummaryService service = new StockDailySummaryService(slotDao, batchDao, signalEventDao,
                markDao, mock(TornStockNoticeAuditDAO.class), barDao, new StockPortfolioService(),
                mock(pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendService.class),
                fixedMarketClock(), mock(pn.torn.goldeneye.configuration.property.ProjectProperty.class),
                mock(pn.torn.goldeneye.torn.manager.setting.SysSettingManager.class));
        stubSummaryQueries(batchDao, signalEventDao);
        when(batchDao.selectShadowActionBatches(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(shadowBatchWithDynamicExitReason()));
        when(markDao.selectDynamicShadowResearchMarks(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(slotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE)).thenReturn(List.of());
        when(batchDao.selectActiveFormalBatches()).thenReturn(List.of());

        StockDailySummaryService.DailySummaryData data = service.buildSummaryData(LocalDate.of(2026, 7, 30));

        assertEquals(0, data.shadow().researchMarkCount(), "历史DYNAMIC编码不得计为研究mark");
        assertEquals(0, data.shadow().completeResearchMarkCount(), "无研究mark时完整数应为0");
        assertFalse(service.buildSummaryText(data).contains("动态卖出影子建议"),
                "历史exitReason含DYNAMIC不得输出动态卖出影子建议");
        assertTrue(service.buildSummaryText(data).contains("动态SELL研究：无研究输入"));
    }

    @Test
    @DisplayName("有完整与缺失研究mark_覆盖率与缺失数量正确且不影响其他统计")
    void buildSummaryData_researchMarks_coverageAndMissingCorrect() {
        TornStockPortfolioSlotDAO slotDao = mock(TornStockPortfolioSlotDAO.class);
        TornStockVirtualBatchDAO batchDao = mock(TornStockVirtualBatchDAO.class);
        TornStockMarketBar15mDAO barDao = mock(TornStockMarketBar15mDAO.class);
        TornStockSignalEventDAO signalEventDao = mock(TornStockSignalEventDAO.class);
        TornStockBatchMarkDAO markDao = mock(TornStockBatchMarkDAO.class);
        StockDailySummaryService service = new StockDailySummaryService(slotDao, batchDao, signalEventDao,
                markDao, mock(TornStockNoticeAuditDAO.class), barDao, new StockPortfolioService(),
                mock(pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendService.class),
                fixedMarketClock(), mock(pn.torn.goldeneye.configuration.property.ProjectProperty.class),
                mock(pn.torn.goldeneye.torn.manager.setting.SysSettingManager.class));
        stubSummaryQueries(batchDao, signalEventDao);
        when(markDao.selectDynamicShadowResearchMarks(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        researchMark("NOT_EVALUATED", "DYNAMIC_RULE_NOT_FROZEN"),
                        researchMark("NOT_EVALUATED", "DYNAMIC_RULE_NOT_FROZEN"),
                        researchMark("NOT_EVALUATED", "DYNAMIC_RULE_NOT_FROZEN"),
                        researchMark(null, null)));
        when(slotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE)).thenReturn(List.of());
        when(batchDao.selectActiveFormalBatches()).thenReturn(List.of());

        StockDailySummaryService.DailySummaryData data = service.buildSummaryData(LocalDate.of(2026, 7, 30));
        String text = service.buildSummaryText(data);

        assertEquals(4, data.shadow().researchMarkCount(), "分母应为4条研究mark");
        assertEquals(3, data.shadow().completeResearchMarkCount(), "完整数应为3");
        assertTrue(text.contains("输入覆盖率：75%"), "覆盖率应为75%");
        assertTrue(text.contains("缺失输入批次数：1"), "缺失输入批次数应为1");
        assertTrue(text.contains("动态SELL研究：规则未冻结，建议未启用"));
        assertFalse(text.contains("动态卖出影子建议"));
    }

    @Test
    @DisplayName("摘要开关独立_正式通知开关不影响日报独立发送路径")
    void dailySummarySwitch_independentFromFormalNotice() {
        pn.torn.goldeneye.torn.manager.setting.SysSettingManager settings =
                mock(pn.torn.goldeneye.torn.manager.setting.SysSettingManager.class);
        pn.torn.goldeneye.configuration.property.ProjectProperty property =
                mock(pn.torn.goldeneye.configuration.property.ProjectProperty.class);
        pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendService sendService =
                mock(pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendService.class);
        TornStockNoticeAuditDAO noticeDao = mock(TornStockNoticeAuditDAO.class);
        TornStockVirtualBatchDAO batchDao = mock(TornStockVirtualBatchDAO.class);
        TornStockSignalEventDAO signalEventDao = mock(TornStockSignalEventDAO.class);
        TornStockPortfolioSlotDAO slotDao = mock(TornStockPortfolioSlotDAO.class);
        StockDailySummaryService service = new StockDailySummaryService(
                slotDao, batchDao, signalEventDao, mock(TornStockBatchMarkDAO.class), noticeDao,
                mock(TornStockMarketBar15mDAO.class), new StockPortfolioService(), sendService,
                fixedMarketClock(), property, settings);

        stubSummaryQueries(batchDao, signalEventDao);
        when(slotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE)).thenReturn(List.of());
        when(batchDao.selectActiveFormalBatches()).thenReturn(List.of());
        when(property.getEnv()).thenReturn(pn.torn.goldeneye.constants.bot.BotConstants.ENV_PROD);
        when(settings.getSettingValue(pn.torn.goldeneye.constants.torn.SettingConstants.KEY_VIP_STOCK_DAILY_SUMMARY_ENABLED))
                .thenReturn("true");
        when(noticeDao.save(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        service.executeDailySummary();

        verify(sendService).sendSingleMessage(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("摘要开关false_日报不发消息")
    void dailySummarySwitch_disabled_noMessage() {
        pn.torn.goldeneye.torn.manager.setting.SysSettingManager settings =
                mock(pn.torn.goldeneye.torn.manager.setting.SysSettingManager.class);
        pn.torn.goldeneye.configuration.property.ProjectProperty property =
                mock(pn.torn.goldeneye.configuration.property.ProjectProperty.class);
        pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendService sendService =
                mock(pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendService.class);
        StockDailySummaryService service = new StockDailySummaryService(
                mock(TornStockPortfolioSlotDAO.class), mock(TornStockVirtualBatchDAO.class),
                mock(TornStockSignalEventDAO.class), mock(TornStockBatchMarkDAO.class),
                mock(TornStockNoticeAuditDAO.class), mock(TornStockMarketBar15mDAO.class),
                new StockPortfolioService(), sendService, fixedMarketClock(), property, settings);

        when(property.getEnv()).thenReturn(pn.torn.goldeneye.constants.bot.BotConstants.ENV_PROD);
        when(settings.getSettingValue(pn.torn.goldeneye.constants.torn.SettingConstants.KEY_VIP_STOCK_DAILY_SUMMARY_ENABLED))
                .thenReturn("false");

        service.executeDailySummary();

        verify(sendService, never()).sendSingleMessage(org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * 构建含DYNAMIC退出原因的历史影子批次。
     *
     * @return 历史影子批次
     */
    private TornStockVirtualBatchDO shadowBatchWithDynamicExitReason() {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(1L);
        batch.setLedgerType(pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode());
        batch.setExitReason("CLOSED_DYNAMIC");
        return batch;
    }

    /**
     * 构建动态SELL研究mark。
     *
     * @param decision 动态影子决定
     * @param reason   动态影子原因
     * @return 研究mark
     */
    private TornStockBatchMarkDO researchMark(String decision, String reason) {
        TornStockBatchMarkDO mark = new TornStockBatchMarkDO();
        mark.setId(System.nanoTime());
        mark.setDynamicShadowDecision(decision);
        mark.setDynamicShadowReason(reason);
        return mark;
    }

    /**
     * 创建日报服务。
     *
     * @param slotDao  槽位DAO
     * @param batchDao 批次DAO
     * @param barDao   行情DAO
     * @return 日报服务
     */
    private StockDailySummaryService service(TornStockPortfolioSlotDAO slotDao,
                                             TornStockVirtualBatchDAO batchDao,
                                             TornStockMarketBar15mDAO barDao,
                                             TornStockSignalEventDAO signalEventDao) {
        return service(slotDao, batchDao, barDao, signalEventDao, fixedMarketClock());
    }

    /**
     * 创建固定在日报时点的市场时钟。
     *
     * @return 固定市场时钟
     */
    private StockMarketClock fixedMarketClock() {
        StockMarketClock marketClock = mock(StockMarketClock.class);
        when(marketClock.now()).thenReturn(LocalDateTime.of(2026, 7, 31, 10, 30));
        when(marketClock.currentEndedBucket()).thenReturn(LocalDateTime.of(2026, 7, 31, 10, 15));
        return marketClock;
    }

    /**
     * 创建使用指定市场时钟的日报服务。
     *
     * @param slotDao        槽位DAO
     * @param batchDao       批次DAO
     * @param barDao         行情DAO
     * @param signalEventDao 信号事件DAO
     * @param marketClock    市场时钟
     * @return 日报服务
     */
    private StockDailySummaryService service(TornStockPortfolioSlotDAO slotDao,
                                             TornStockVirtualBatchDAO batchDao,
                                             TornStockMarketBar15mDAO barDao,
                                             TornStockSignalEventDAO signalEventDao,
                                             StockMarketClock marketClock) {
        return new StockDailySummaryService(slotDao, batchDao, signalEventDao,
                mock(TornStockBatchMarkDAO.class), mock(TornStockNoticeAuditDAO.class), barDao,
                new StockPortfolioService(),
                mock(pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendService.class),
                marketClock, mock(pn.torn.goldeneye.configuration.property.ProjectProperty.class),
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

        LambdaQueryChainWrapper<TornStockVirtualBatchDO> batchQuery = mock(LambdaQueryChainWrapper.class);
        when(batchDao.lambdaQuery()).thenReturn(batchQuery);
        when(batchQuery.eq(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(batchQuery);
        when(batchQuery.in(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(java.util.Collection.class)))
                .thenReturn(batchQuery);
        when(batchQuery.and(org.mockito.ArgumentMatchers.any())).thenReturn(batchQuery);
        when(batchQuery.ge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(batchQuery);
        when(batchQuery.lt(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(batchQuery);
        when(batchQuery.or()).thenReturn(batchQuery);
        when(batchQuery.list()).thenReturn(List.of());
    }

    /**
     * 构建槽位。
     *
     * @param id            槽位ID
     * @param availableCash 可用现金
     * @param reservedCash  预留资金
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
     * @param stocksId  股票ID
     * @param shortname 股票简称
     * @param slotId    槽位ID
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
     * @param stocksId  股票ID
     * @param lastPrice 最新价格
     * @return 可用行情bar
     */
    private TornStockMarketBar15mDO usableBar(int stocksId, BigDecimal lastPrice) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(stocksId);
        bar.setUsable(true);
        bar.setLastPrice(lastPrice);
        bar.setBarEndTime(LocalDateTime.of(2026, 7, 31, 10, 15));
        return bar;
    }
}
