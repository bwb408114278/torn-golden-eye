package pn.torn.goldeneye.torn.service.stocks.alert.summary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeBotSender;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendRecorder;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendService;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.PortfolioEquityCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 股票日报测试，覆盖三段组合数据组装、数值格式与新报文结构。
 * <p>
 * 验证要点:
 * <ul>
 *   <li>权益缺失时仍展示现金与排序后的缺失行情明细,并降级为"数据不足";</li>
 *   <li>权益估值只使用新鲜行情,priceAsOf取实际参与估值行情的最早结束时间;</li>
 *   <li>α正式组合/存量正式组合/α影子组合按各自组合编码独立读取与统计,互不合计;</li>
 *   <li>报文固定格式:金钱千分位整数、股价2位小数、收益率带符号百分数、影子区块位于报文尾部;</li>
 *   <li>已退场的信号事件/无限资金影子/候选影子组合/动态SELL研究不再出现在报文中。</li>
 * </ul>
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.07.17
 */
@DisplayName("股票日报测试")
class StockDailySummaryServiceTest {

    /**
     * 摘要日期(发送日前一自然日)
     */
    private static final LocalDate SUMMARY_DATE = LocalDate.of(2026, 7, 30);
    /**
     * α正式组合一级区块标题前缀。
     */
    private static final String ALPHA_SECTION_PREFIX = "α 正式组合（新策略主仓 · ";
    /**
     * 存量正式组合一级区块标题前缀。
     */
    private static final String LEGACY_SECTION_PREFIX = "存量正式组合（只出不进 · ";
    /**
     * α影子组合一级区块标题前缀。
     */
    private static final String ALPHA_SHADOW_SECTION_PREFIX =
            "α 影子组合（仅研究，不触真钱，不代表任何操作建议 · ";

    @Test
    @DisplayName("开放仓位缺少行情_权益不可用但展示排序后的缺失股票与现金")
    void buildSummaryData_missingOpenPositionPrice_returnsDataInsufficientEquity() {
        TornStockPortfolioSlotDAO slotDao = mock(TornStockPortfolioSlotDAO.class);
        TornStockVirtualBatchDAO batchDao = mock(TornStockVirtualBatchDAO.class);
        TornStockMarketBar15mDAO barDao = mock(TornStockMarketBar15mDAO.class);
        stubEmptyQueries(slotDao, batchDao);
        StockDailySummaryService service = service(slotDao, batchDao, barDao, fixedMarketClock());

        when(slotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE)).thenReturn(List.of(
                slot(1L, new BigDecimal("100.00"), new BigDecimal("20.00"))));
        when(batchDao.selectActiveFormalBatches()).thenReturn(List.of(
                openBatch(2, "MUN", 1L), openBatch(1, "TCC", 1L)));
        when(barDao.selectLatestUsableByStocks(anyList(), any(), any(),
                eq(Stock15mBarBuildService.BUILD_VERSION)))
                .thenReturn(List.of(usableBar(2, new BigDecimal("10.00"))));

        StockDailySummaryService.DailySummaryData data = service.buildSummaryData(SUMMARY_DATE);

        assertNull(data.legacy().equity());
        assertEquals(new BigDecimal("120.00"), data.legacy().cashAndReserved());
        assertEquals(List.of("TCC"), data.legacy().missingPriceStocks());
        assertEquals(StockPortfolioService.PORTFOLIO_CODE, data.legacy().portfolioCode());
        assertEquals(StockPortfolioService.SLOT_COUNT, data.legacy().slotCount());
        assertEquals(1, data.legacy().occupiedSlots());
    }

    @Test
    @DisplayName("无开放仓位_权益应等于可用现金与预留资金")
    void buildSummaryData_noOpenPositions_equityEqualsCashAndReserved() {
        TornStockPortfolioSlotDAO slotDao = mock(TornStockPortfolioSlotDAO.class);
        TornStockVirtualBatchDAO batchDao = mock(TornStockVirtualBatchDAO.class);
        stubEmptyQueries(slotDao, batchDao);
        StockDailySummaryService service = service(slotDao, batchDao,
                mock(TornStockMarketBar15mDAO.class), fixedMarketClock());

        when(slotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE)).thenReturn(List.of(
                slot(1L, new BigDecimal("100.00"), new BigDecimal("20.00"))));

        StockDailySummaryService.DailySummaryData data = service.buildSummaryData(SUMMARY_DATE);

        assertEquals(new BigDecimal("120.00"), data.legacy().equity());
        assertEquals(new BigDecimal("120.00"), data.legacy().cashAndReserved());
        assertEquals(List.of(), data.legacy().missingPriceStocks());
        assertEquals(List.of(), data.legacy().openPositions());
    }

    @Test
    @DisplayName("历史可用行情超过三十分钟_权益应降级为数据不足")
    void buildSummaryData_staleUsableBar_returnsDataInsufficientEquity() {
        TornStockPortfolioSlotDAO slotDao = mock(TornStockPortfolioSlotDAO.class);
        TornStockVirtualBatchDAO batchDao = mock(TornStockVirtualBatchDAO.class);
        TornStockMarketBar15mDAO barDao = mock(TornStockMarketBar15mDAO.class);
        stubEmptyQueries(slotDao, batchDao);
        StockDailySummaryService service = service(slotDao, batchDao, barDao, fixedMarketClock());

        when(slotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE)).thenReturn(List.of(
                slot(1L, new BigDecimal("100.00"), BigDecimal.ZERO)));
        when(batchDao.selectActiveFormalBatches()).thenReturn(List.of(openBatch(1, "TCC", 1L)));
        TornStockMarketBar15mDO staleBar = usableBar(1, new BigDecimal("10.00"));
        staleBar.setBarEndTime(LocalDateTime.of(2026, 7, 31, 9, 45));
        when(barDao.selectLatestUsableByStocks(anyList(), any(), any(),
                eq(Stock15mBarBuildService.BUILD_VERSION)))
                .thenReturn(List.of(staleBar));

        StockDailySummaryService.DailySummaryData data = service.buildSummaryData(SUMMARY_DATE);

        assertNull(data.legacy().equity());
        assertEquals(List.of("TCC"), data.legacy().missingPriceStocks());
        assertNull(data.legacy().priceAsOf());
    }

    @Test
    @DisplayName("多个开放仓位均有新鲜行情_估值时点取实际参与估值行情的最早结束时间")
    void buildSummaryData_freshBars_usesEarliestActualBarEndTimeAsPriceAsOf() {
        TornStockPortfolioSlotDAO slotDao = mock(TornStockPortfolioSlotDAO.class);
        TornStockVirtualBatchDAO batchDao = mock(TornStockVirtualBatchDAO.class);
        TornStockMarketBar15mDAO barDao = mock(TornStockMarketBar15mDAO.class);
        stubEmptyQueries(slotDao, batchDao);
        StockDailySummaryService service = service(slotDao, batchDao, barDao, fixedMarketClock());

        when(slotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE)).thenReturn(List.of(
                slot(1L, new BigDecimal("100.00"), BigDecimal.ZERO),
                slot(2L, new BigDecimal("100.00"), BigDecimal.ZERO)));
        when(batchDao.selectActiveFormalBatches()).thenReturn(List.of(
                openBatch(1, "TCC", 1L), openBatch(2, "MUN", 2L)));
        TornStockMarketBar15mDO earlierBar = usableBar(1, new BigDecimal("10.00"));
        earlierBar.setBarEndTime(LocalDateTime.of(2026, 7, 31, 10, 0));
        TornStockMarketBar15mDO laterBar = usableBar(2, new BigDecimal("10.00"));
        laterBar.setBarEndTime(LocalDateTime.of(2026, 7, 31, 10, 15));
        when(barDao.selectLatestUsableByStocks(anyList(), any(), any(),
                eq(Stock15mBarBuildService.BUILD_VERSION)))
                .thenReturn(List.of(earlierBar, laterBar));

        StockDailySummaryService.DailySummaryData data = service.buildSummaryData(SUMMARY_DATE);

        assertEquals(LocalDateTime.of(2026, 7, 31, 10, 0), data.legacy().priceAsOf());
        assertEquals(List.of(), data.legacy().missingPriceStocks());
    }

    @Test
    @DisplayName("三段组合_按各自组合编码独立读取且昨日买卖与持仓互不合计")
    void buildSummaryData_threePortfolios_areIsolatedByPortfolioCode() {
        TornStockPortfolioSlotDAO slotDao = mock(TornStockPortfolioSlotDAO.class);
        TornStockVirtualBatchDAO batchDao = mock(TornStockVirtualBatchDAO.class);
        TornStockMarketBar15mDAO barDao = mock(TornStockMarketBar15mDAO.class);
        stubEmptyQueries(slotDao, batchDao);
        StockDailySummaryService service = service(slotDao, batchDao, barDao, fixedMarketClock());

        when(slotDao.selectAllByPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(alphaSlot()));
        when(batchDao.selectActiveAlphaBatches(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(openBatch(7, "CNC", 11L)));
        when(batchDao.selectAlphaActionBatches(eq(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE), any(), any()))
                .thenReturn(List.of(closedBatch(7, "CNC", new BigDecimal("79246.00"),
                        new BigDecimal("10000000.00"))));
        when(barDao.selectLatestUsableByStocks(anyList(), any(), any(),
                eq(Stock15mBarBuildService.BUILD_VERSION)))
                .thenReturn(List.of(usableBar(7, new BigDecimal("830.00"))));

        StockDailySummaryService.DailySummaryData data = service.buildSummaryData(SUMMARY_DATE);

        assertEquals(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE, data.alpha().portfolioCode());
        assertEquals(StockPortfolioService.VIP_ALPHA_SLOT_COUNT, data.alpha().slotCount());
        assertEquals(1, data.alpha().occupiedSlots());
        assertEquals(1, data.alpha().yesterdaySellCount());
        assertEquals(0, data.alpha().yesterdayBuyCount());
        assertEquals(0, new BigDecimal("79246.00").compareTo(data.alpha().yesterdayProfit()));
        assertEquals(0, new BigDecimal("10000000.00").compareTo(data.alpha().yesterdayInvested()));
        assertEquals(1, data.alpha().openPositions().size());
        assertEquals("CNC", data.alpha().openPositions().getFirst().stocksShortname());

        assertEquals(0, data.legacy().yesterdaySellCount(), "存量正式组合昨日无动作不得继承α统计");
        assertEquals(0, data.legacy().openPositions().size());
        assertEquals(0, new BigDecimal("0.00").compareTo(data.legacy().yesterdayProfit()));

        assertEquals(StockPortfolioService.VIP_ALPHA_SHADOW_PORTFOLIO_CODE, data.alphaShadow().portfolioCode());
        assertEquals(StockPortfolioService.VIP_ALPHA_SHADOW_SLOT_COUNT, data.alphaShadow().slotCount());
        assertEquals(0, data.alphaShadow().occupiedSlots());
    }

    @Test
    @DisplayName("报文格式_三段区块顺序固定且金额千分位整数_股价2位小数_收益率带符号")
    void buildSummaryText_rendersThreeSectionsWithFixedNumberFormat() {
        StockDailySummaryService service = service(mock(TornStockPortfolioSlotDAO.class),
                mock(TornStockVirtualBatchDAO.class), mock(TornStockMarketBar15mDAO.class), fixedMarketClock());

        StockDailySummaryService.PortfolioSummary alpha = summary(
                StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE, StockPortfolioService.VIP_ALPHA_SLOT_COUNT,
                1, new BigDecimal("10045264199"), new BigDecimal("789"), List.of(),
                1, 1, new BigDecimal("79246"), new BigDecimal("10000000"),
                List.of(new StockDailySummaryService.OpenPosition("CNC", new BigDecimal("826.26"))));
        StockDailySummaryService.PortfolioSummary legacy = summary(
                StockPortfolioService.PORTFOLIO_CODE, StockPortfolioService.SLOT_COUNT,
                3, null, new BigDecimal("4079993937"), List.of("TSB", "IOU"),
                0, 1, new BigDecimal("66800"), new BigDecimal("10000000"), List.of());
        StockDailySummaryService.PortfolioSummary shadow = summary(
                StockPortfolioService.VIP_ALPHA_SHADOW_PORTFOLIO_CODE, StockPortfolioService.VIP_ALPHA_SHADOW_SLOT_COUNT,
                0, BigDecimal.ZERO, BigDecimal.ZERO, List.of(),
                0, 0, BigDecimal.ZERO, BigDecimal.ZERO, List.of());

        String summaryText = service.buildSummaryText(
                new StockDailySummaryService.DailySummaryData(SUMMARY_DATE, alpha, legacy, shadow));

        assertEquals(3, sectionTitles(summaryText).size(), "日报固定只渲染α正式/存量正式/α影子三个一级区块");
        assertTrue(summaryText.contains("- 组合净值：10,045,264,199"), "权益必须为千分位整数");
        assertTrue(summaryText.contains("- 可用现金：4,079,993,937"), "现金必须为千分位整数");
        assertTrue(summaryText.contains("- 当前虚拟持仓：CNC @ 826.26"), "股价必须保留两位小数");
        assertTrue(summaryText.contains("- 昨日已实现净变化：79,246（变动率 +0.79%）"),
                "已实现净变化金额为千分位整数、变动率为带符号百分数");
    }

    @Test
    @DisplayName("报文内容_已退场研究区块全部消失且影子区块位于报文尾部")
    void buildSummaryText_retiredSectionsAreAbsent() {
        StockDailySummaryService service = service(mock(TornStockPortfolioSlotDAO.class),
                mock(TornStockVirtualBatchDAO.class), mock(TornStockMarketBar15mDAO.class), fixedMarketClock());
        StockDailySummaryService.PortfolioSummary empty = summary(
                StockPortfolioService.PORTFOLIO_CODE, StockPortfolioService.SLOT_COUNT,
                0, new BigDecimal("120"), new BigDecimal("120"), List.of(),
                0, 0, BigDecimal.ZERO, BigDecimal.ZERO, List.of());

        String summaryText = service.buildSummaryText(
                new StockDailySummaryService.DailySummaryData(SUMMARY_DATE, empty, empty, empty));

        // 正向结构断言: 一级区块标题集合固定为α正式/存量正式/α影子,已退场研究区块因此不可能出现,
        // 不再以"字符串不存在"断言证明删除
        assertEquals(List.of(
                        "α 正式组合（新策略主仓 · 5槽）",
                        "存量正式组合（只出不进 · 5槽）",
                        "α 影子组合（仅研究，不触真钱，不代表任何操作建议 · 5槽）"),
                sectionTitles(summaryText), "日报一级区块标题集合必须固定为α正式/存量正式/α影子");
        assertFalse(summaryText.contains("%n"), "不得出现字面量换行占位符");
        assertTrue(summaryText.endsWith("- 数据陈旧批次：0"), "影子区块必须位于报文尾部");
        assertTrue(summaryText.contains("α 影子组合（仅研究，不触真钱，不代表任何操作建议 · "),
                "影子区块必须带有仅研究免责标题");
        assertTrue(summaryText.contains("提示：α 策略已接管新建仓位；存量正式组合按原规则退出，不再新增买入。"));
    }

    @Test
    @DisplayName("摘要开关独立_正式通知开关不影响日报独立发送路径")
    void dailySummarySwitch_independentFromFormalNotice() {
        SysSettingManager settings = mock(SysSettingManager.class);
        ProjectProperty property = mock(ProjectProperty.class);
        StockNoticeSendService sendService = mock(StockNoticeSendService.class);
        TornStockNoticeAuditDAO noticeDao = mock(TornStockNoticeAuditDAO.class);
        TornStockVirtualBatchDAO batchDao = mock(TornStockVirtualBatchDAO.class);
        TornStockPortfolioSlotDAO slotDao = mock(TornStockPortfolioSlotDAO.class);
        stubEmptyQueries(slotDao, batchDao);
        StockDailySummaryService service = service(slotDao, batchDao, mock(TornStockMarketBar15mDAO.class),
                noticeDao, sendService, fixedMarketClock(), property, settings);

        when(property.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(settings.getSettingValue(SettingConstants.KEY_VIP_STOCK_DAILY_SUMMARY_ENABLED)).thenReturn("true");
        when(noticeDao.save(any())).thenAnswer(invocation -> {
            TornStockNoticeAuditDO saved = invocation.getArgument(0);
            saved.setId(9001L);
            return true;
        });
        when(noticeDao.claimByIds(anyList(), anyString(), any(LocalDateTime.class))).thenReturn(1);
        when(noticeDao.finalizePayload(any(), any(LocalDateTime.class))).thenReturn(1);
        when(sendService.sendSingleMessageResult(anyString()))
                .thenReturn(StockNoticeBotSender.SendResult.successful());

        service.executeDailySummary();

        verify(sendService).sendSingleMessageResult(anyString());
        verify(noticeDao).markSentByIds(anyList(), anyString(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("摘要开关false_日报不发消息")
    void dailySummarySwitch_disabled_noMessage() {
        SysSettingManager settings = mock(SysSettingManager.class);
        ProjectProperty property = mock(ProjectProperty.class);
        StockNoticeSendService sendService = mock(StockNoticeSendService.class);
        StockDailySummaryService service = service(mock(TornStockPortfolioSlotDAO.class),
                mock(TornStockVirtualBatchDAO.class), mock(TornStockMarketBar15mDAO.class),
                mock(TornStockNoticeAuditDAO.class), sendService, fixedMarketClock(), property, settings);

        when(property.getEnv()).thenReturn(BotConstants.ENV_PROD);
        when(settings.getSettingValue(SettingConstants.KEY_VIP_STOCK_DAILY_SUMMARY_ENABLED)).thenReturn("false");

        service.executeDailySummary();

        verify(sendService, never()).sendSingleMessageResult(anyString());
    }

    /**
     * 提取日报中一级组合区块的标题行。
     * <p>
     * 一级区块标题为"组合名（... · N槽）"形式,正文行一律以"- "开头,因此可按标题前缀精确识别。
     * 以标题数量与集合做正向结构断言,替代"已退场字符串不存在"与"整篇文本等值"两类反模式断言。
     *
     * @param summaryText 日报文本
     * @return 按报文顺序排列的一级区块标题
     */
    private List<String> sectionTitles(String summaryText) {
        return summaryText.lines()
                .filter(line -> line.startsWith(ALPHA_SECTION_PREFIX)
                        || line.startsWith(LEGACY_SECTION_PREFIX)
                        || line.startsWith(ALPHA_SHADOW_SECTION_PREFIX))
                .toList();
    }

    /**
     * 构建组合摘要。
     *
     * @param portfolioCode      组合编码
     * @param slotCount          槽位总数
     * @param occupiedSlots      占用槽位
     * @param equity             权益;null表示数据不足
     * @param cashAndReserved    可用现金与预留资金
     * @param missingPriceStocks 缺失行情股票
     * @param buyCount           昨日买入批数
     * @param sellCount          昨日卖出批数
     * @param profit             昨日已实现净收益金额
     * @param invested           昨日投入成本
     * @param openPositions      开放仓位
     * @return 组合摘要
     */
    private StockDailySummaryService.PortfolioSummary summary(String portfolioCode, int slotCount,
                                                              int occupiedSlots, BigDecimal equity,
                                                              BigDecimal cashAndReserved,
                                                              List<String> missingPriceStocks,
                                                              int buyCount, int sellCount, BigDecimal profit,
                                                              BigDecimal invested, List<StockDailySummaryService.OpenPosition> openPositions) {
        return new StockDailySummaryService.PortfolioSummary(portfolioCode, slotCount, occupiedSlots, equity,
                cashAndReserved, missingPriceStocks, null, buyCount, sellCount, profit, invested,
                openPositions, 0);
    }

    /**
     * 创建日报服务。
     *
     * @param slotDao     槽位DAO
     * @param batchDao    批次DAO
     * @param barDao      行情DAO
     * @param marketClock 市场时钟
     * @return 日报服务
     */
    private StockDailySummaryService service(TornStockPortfolioSlotDAO slotDao,
                                             TornStockVirtualBatchDAO batchDao,
                                             TornStockMarketBar15mDAO barDao,
                                             StockMarketClock marketClock) {
        return service(slotDao, batchDao, barDao, mock(TornStockNoticeAuditDAO.class),
                mock(StockNoticeSendService.class), marketClock, mock(ProjectProperty.class),
                mock(SysSettingManager.class));
    }

    /**
     * 使用指定通知依赖组装日报服务(查询服务 + 渲染器 + 通知服务)。
     *
     * @param slotDao           槽位DAO
     * @param batchDao          批次DAO
     * @param barDao            行情DAO
     * @param noticeAuditDAO    通知审计DAO
     * @param sendService       通知发送服务
     * @param marketClock       市场时钟
     * @param projectProperty   项目配置
     * @param sysSettingManager 系统设置管理
     * @return 日报服务
     */
    private StockDailySummaryService service(TornStockPortfolioSlotDAO slotDao,
                                             TornStockVirtualBatchDAO batchDao,
                                             TornStockMarketBar15mDAO barDao,
                                             TornStockNoticeAuditDAO noticeAuditDAO,
                                             StockNoticeSendService sendService,
                                             StockMarketClock marketClock,
                                             ProjectProperty projectProperty,
                                             SysSettingManager sysSettingManager) {
        StockDailySummaryQueryService queryService = new StockDailySummaryQueryService(slotDao, batchDao, barDao,
                marketClock, new PortfolioEquityCalculator(new StockPortfolioService()),
                new DailySummaryMetricsCalculator());
        StockDailySummaryNoticeService noticeService = new StockDailySummaryNoticeService(
                noticeAuditDAO, new StockNoticeSendRecorder(noticeAuditDAO), sendService, marketClock,
                projectProperty);
        return new StockDailySummaryService(queryService, new StockDailySummaryRenderer(), noticeService,
                marketClock, projectProperty, sysSettingManager);
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
        when(marketClock.summaryDate()).thenReturn(SUMMARY_DATE);
        return marketClock;
    }

    /**
     * 配置与权益无关的查询返回空结果。
     *
     * @param slotDao  槽位DAO
     * @param batchDao 批次DAO
     */
    private void stubEmptyQueries(TornStockPortfolioSlotDAO slotDao, TornStockVirtualBatchDAO batchDao) {
        when(slotDao.selectAllByPortfolioCode(anyString())).thenReturn(List.of());
        when(batchDao.selectActiveAlphaBatches(anyString())).thenReturn(List.of());
        when(batchDao.selectActiveFormalBatches()).thenReturn(List.of());
        when(batchDao.selectAlphaActionBatches(anyString(), any(), any())).thenReturn(List.of());
        when(batchDao.selectFormalActionBatches(any(), any())).thenReturn(List.of());
    }

    /**
     * 构建α正式组合槽位。
     *
     * @return 已占用槽位
     */
    private TornStockPortfolioSlotDO alphaSlot() {
        TornStockPortfolioSlotDO slot = slot(11L, new BigDecimal("788.98"), BigDecimal.ZERO);
        slot.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        slot.setSlotStatus("OCCUPIED");
        return slot;
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
     * 构建开放批次。
     *
     * @param stocksId  股票ID
     * @param shortname 股票简称
     * @param slotId    槽位ID
     * @return 开放批次
     */
    private TornStockVirtualBatchDO openBatch(int stocksId, String shortname, long slotId) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setStocksId(stocksId);
        batch.setStocksShortname(shortname);
        batch.setSlotId(slotId);
        batch.setQuantity(1L);
        batch.setEntryReferencePrice(new BigDecimal("826.26"));
        batch.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        return batch;
    }

    /**
     * 构建昨日已卖出的批次。
     *
     * @param stocksId  股票ID
     * @param shortname 股票简称
     * @param profit    已实现净收益金额
     * @param invested  投入成本
     * @return 已平仓批次
     */
    private TornStockVirtualBatchDO closedBatch(int stocksId, String shortname, BigDecimal profit,
                                                BigDecimal invested) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setStocksId(stocksId);
        batch.setStocksShortname(shortname);
        batch.setExitTime(LocalDateTime.of(2026, 7, 30, 10, 15));
        batch.setInvestedCash(invested);
        batch.setSellProceeds(invested.add(profit));
        batch.setBatchStatus(StockBatchStatusEnum.CLOSED_TARGET.getCode());
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
