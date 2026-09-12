package pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDecisionDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDecisionDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision.StockAlphaDecisionService;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision.StockAlphaTargetPolicy;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.NoticeRebalanceAssociation;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;
import pn.torn.goldeneye.torn.service.stocks.alert.shadow.StockShadowRecordWriter;
import pn.torn.goldeneye.utils.image.render.html.PlaywrightBrowserManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * α换仓真实事务回滚集成测试。
 * <p>
 * 唯一业务测试方法在真实Spring事务中调用真实 {@link StockAlphaRebalanceService},通过
 * {@link StockShadowRecordWriter} 的测试替身注入换仓事务最后阶段的通知审计写入失败,证明
 * 换仓后段失败时原仓、槽位资金、决策、新仓和通知审计不会留下单侧事实。
 * <p>
 * 夹具由 {@code @BeforeTransaction} 在测试事务之前提交,使 {@code @AfterTransaction} 能在
 * 测试事务回滚之后用真实DAO读回最终状态;测试事务本身显式使用{@code @Transactional}与
 * {@code @Rollback}。清理只物理删除本测试隔离键的数据,与业务断言分离,不使用SQL模拟换仓成功。
 * <p>
 * 来源摘要可复核门禁只读取日线快照排名向量,与本次事务边界无关;为避免为一个回滚证据构造
 * 21个共同有效日×35支股票的排名夹具,该只读门禁在测试中以替身固定为可复核,换仓的结算、
 * 批次、槽位、决策、通知写入与事务回滚全部使用真实Bean与真实DAO。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.12
 */
@SpringBootTest
@Tag("shared-db")
@DisplayName("Alpha换仓真实事务回滚集成测试")
class StockAlphaRebalanceTransactionItTest {
    /**
     * 决策业务日(隔离的远期日期,远离生产数据与轮次调度)。
     */
    private static final LocalDate DECISION_DATE = LocalDate.of(2099, 9, 5);
    /**
     * 决策phase。
     */
    private static final int PHASE = 0;
    /**
     * 决策桶起点。
     */
    private static final LocalDateTime DECISION_BAR = LocalDateTime.of(2099, 9, 5, 9, 45);
    /**
     * 执行桶起点。
     */
    private static final LocalDateTime EXECUTION_BAR = LocalDateTime.of(2099, 9, 5, 10, 0);
    /**
     * 处理时点(晚于执行bar结束,满足执行bar已结束校验)。
     */
    private static final LocalDateTime PROCESSING_TIME = EXECUTION_BAR.plusMinutes(16);
    /**
     * 隔离的原仓股票ID。
     */
    private static final Integer ORIGINAL_STOCKS_ID = 2099711;
    /**
     * 隔离的新仓股票ID。
     */
    private static final Integer REPLACEMENT_STOCKS_ID = 2099712;
    /**
     * 隔离的原仓批次编号。
     */
    private static final String ORIGINAL_BATCH_NO = "IT-ALPHA-TX-20990905-0";
    /**
     * 换仓新仓批次编号前缀(批次编号含数据库生成的决策ID)。
     */
    private static final String REPLACEMENT_BATCH_NO_PREFIX = "AR-2099-09-05-0-";
    /**
     * 换仓前槽位可用现金,必须与批次余款一致。
     */
    private static final BigDecimal AVAILABLE_CASH = new BigDecimal("500.00");
    /**
     * 换仓前槽位预留现金。
     */
    private static final BigDecimal RESERVED_CASH = new BigDecimal("0.00");
    /**
     * 原仓出场参考价。
     */
    private static final BigDecimal SELL_PRICE = new BigDecimal("110.00");
    /**
     * 新仓入场参考价。
     */
    private static final BigDecimal BUY_PRICE = new BigDecimal("10.00");

    @Autowired
    private StockAlphaRebalanceService rebalanceService;
    @Autowired
    private TornStockAlphaDecisionDAO decisionDAO;
    @Autowired
    private TornStockVirtualBatchDAO batchDAO;
    @Autowired
    private TornStockPortfolioSlotDAO slotDAO;
    @Autowired
    private TornStockNoticeAuditDAO noticeAuditDAO;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean
    private StockShadowRecordWriter noticeWriter;
    @MockitoSpyBean
    private StockAlphaDecisionService decisionService;
    /**
     * 表格图片渲染浏览器与本回滚证据无关,且需要下载/启动外部Chromium;测试上下文以替身替换,
     * 避免与本事务断言无关的外部浏览器进程影响验证,不改变换仓真实Bean与真实DAO。
     */
    @MockitoBean
    private PlaywrightBrowserManager playwrightBrowserManager;

    private Long originalBatchId;
    private Long decisionId;
    private Long alphaSlotId;
    private TornStockPortfolioSlotDO originalSlotSnapshot;

    /**
     * 在测试事务之前提交隔离夹具: OPEN的原仓批次、绑定原仓的VIP_ALPHA槽位与待消费换仓决策。
     */
    @BeforeTransaction
    void prepareCommittedFixtures() {
        cleanupIsolatedRows();
        List<TornStockPortfolioSlotDO> slots =
                slotDAO.selectAllByPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        assertEquals(StockPortfolioService.VIP_ALPHA_SLOT_COUNT, slots.size(),
                "VIP_ALPHA槽位必须已按生产规则初始化");
        originalSlotSnapshot = slots.getFirst();
        alphaSlotId = originalSlotSnapshot.getId();

        assertNull(batchDAO.selectByBatchNoForUpdate(ORIGINAL_BATCH_NO), "夹具批次不得与历史数据冲突");
        assertEquals(1, batchDAO.insertIgnoreConflict(
                        openAlphaBatch(ORIGINAL_BATCH_NO, ORIGINAL_STOCKS_ID, alphaSlotId, originalSlotSnapshot.getSlotNo())),
                "α原仓批次必须真实落库");
        TornStockVirtualBatchDO persistedOriginal = batchDAO.selectByBatchNoForUpdate(ORIGINAL_BATCH_NO);
        assertNotNull(persistedOriginal, "α原仓批次必须可按批次号读回");
        originalBatchId = persistedOriginal.getId();

        jdbcTemplate.update("UPDATE torn_stock_portfolio_slot SET slot_status = ?, current_batch_id = ?, "
                        + "available_cash = ?, reserved_cash = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?",
                StockSlotStatusEnum.OCCUPIED.getCode(), originalBatchId, AVAILABLE_CASH, RESERVED_CASH, alphaSlotId);

        assertEquals(1, decisionDAO.insertIgnoreConflict(pendingRebalanceDecision(originalBatchId)),
                "α换仓决策必须真实落库");
        TornStockAlphaDecisionDO persistedDecision =
                decisionDAO.selectByBusinessKeyForUpdate(DECISION_DATE, PHASE);
        assertNotNull(persistedDecision, "α换仓决策必须可按业务键读回");
        decisionId = persistedDecision.getId();
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("真实事务_换仓通知审计写入失败后整体回滚")
    void rebalance_noticeAuditFailure_rollsBackWholeTransaction() {
        doReturn(true).when(decisionService).isSourceReproducible(any(TornStockAlphaDecisionDO.class));
        doThrow(new IllegalStateException("模拟通知审计写入失败"))
                .when(noticeWriter)
                .writeNoticeAudits(anyList(), anyList(), eq(EXECUTION_BAR), any(NoticeRebalanceAssociation.class));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> rebalanceService.rebalance(DECISION_DATE, PHASE, PROCESSING_TIME, roundSnapshot()),
                "换仓事务最后阶段的通知审计写入失败必须使整个换仓事务失败");

        assertTrue(exception.getMessage().contains("模拟通知审计写入失败"),
                "失败必须来自注入的通知审计写入,实际: " + exception.getMessage());
    }

    /**
     * 测试事务回滚后用真实DAO读回最终状态,并清理已提交夹具。
     */
    @AfterTransaction
    void verifyRolledBackFactsThenCleanup() {
        try {
            assertOriginalBatchNotClosed();
            assertAlphaSlotUnchanged();
            assertReplacementBatchAbsent();
            assertNoticeAuditsAbsent();
            assertDecisionStillPending();
        } finally {
            cleanupIsolatedRows();
            restoreAlphaSlot();
        }
    }

    /**
     * 断言原仓未产生任何单边关闭事实。
     */
    private void assertOriginalBatchNotClosed() {
        TornStockVirtualBatchDO original = batchDAO.selectByBatchNoForUpdate(ORIGINAL_BATCH_NO);
        assertNotNull(original, "原仓批次必须仍然存在");
        assertEquals(StockBatchStatusEnum.OPEN.getCode(), original.getBatchStatus(), "原仓必须保持OPEN");
        assertNull(original.getExitTime(), "原仓不得产生exitTime");
        assertNull(original.getExitReferencePrice(), "原仓不得产生exitReferencePrice");
        assertNull(original.getExitReason(), "原仓不得产生exitReason");
        assertNull(original.getSellProceeds(), "原仓不得产生sellProceeds");
        assertNull(original.getNetReturn(), "原仓不得产生netReturn");
        assertNull(original.getExitSignalTime(), "原仓不得产生exitSignalTime");
    }

    /**
     * 断言α槽位状态、绑定与资金保持换仓前值。
     */
    private void assertAlphaSlotUnchanged() {
        TornStockPortfolioSlotDO slot = slotDAO.getById(alphaSlotId);
        assertNotNull(slot, "α槽位必须仍然存在");
        assertEquals(StockSlotStatusEnum.OCCUPIED.getCode(), slot.getSlotStatus(), "α槽位必须保持OCCUPIED");
        assertEquals(originalBatchId, slot.getCurrentBatchId(), "α槽位必须仍绑定原仓批次");
        assertEquals(0, AVAILABLE_CASH.compareTo(slot.getAvailableCash()), "α槽位可用现金必须保持换仓前值");
        assertEquals(0, RESERVED_CASH.compareTo(slot.getReservedCash()), "α槽位预留现金必须保持换仓前值");
    }

    /**
     * 断言换仓新仓批次不存在。
     */
    private void assertReplacementBatchAbsent() {
        assertNull(batchDAO.selectByBatchNoForUpdate(REPLACEMENT_BATCH_NO_PREFIX + decisionId),
                "换仓新仓批次不得残留");
    }

    /**
     * 断言换仓通知审计不存在。
     */
    private void assertNoticeAuditsAbsent() {
        Long noticeCount = noticeAuditDAO.lambdaQuery()
                .apply("payload_snapshot ->> 'rebalanceDecisionId' = {0}", String.valueOf(decisionId))
                .count();
        assertEquals(0L, noticeCount, "换仓通知审计不得残留");
    }

    /**
     * 断言决策仍为可解释的待消费状态。
     */
    private void assertDecisionStillPending() {
        TornStockAlphaDecisionDO decision = decisionDAO.getById(decisionId);
        assertNotNull(decision, "换仓决策必须仍然存在");
        assertEquals("PENDING", decision.getExecutionStatus(), "决策必须保持PENDING");
        assertNull(decision.getRebalanceBatchId(), "决策不得绑定换仓新仓批次");
        assertNull(decision.getFailureReason(), "决策不得被写入失败原因");
        assertEquals(EXECUTION_BAR, decision.getExecutionBarStartTime(), "决策执行桶必须保持换仓前已冻结事实");
    }

    /**
     * 物理清理本测试隔离键的数据,避免逻辑删除残留。
     * <p>
     * 清理与业务断言分离,只按隔离股票ID、隔离批次编号和隔离决策业务日删除,
     * 不触碰生产数据。
     */
    private void cleanupIsolatedRows() {
        jdbcTemplate.update("DELETE FROM torn_stock_notice_audit WHERE payload_snapshot ->> 'stocksId' IN (?, ?)",
                String.valueOf(ORIGINAL_STOCKS_ID), String.valueOf(REPLACEMENT_STOCKS_ID));
        if (decisionId != null) {
            jdbcTemplate.update("DELETE FROM torn_stock_notice_audit "
                            + "WHERE payload_snapshot ->> 'rebalanceDecisionId' = ?", String.valueOf(decisionId));
        }
        jdbcTemplate.update("DELETE FROM torn_stock_alpha_decision WHERE decision_business_date = ?", DECISION_DATE);
        jdbcTemplate.update("DELETE FROM torn_stock_virtual_batch WHERE batch_no = ?", ORIGINAL_BATCH_NO);
        jdbcTemplate.update("DELETE FROM torn_stock_virtual_batch WHERE batch_no LIKE ?",
                REPLACEMENT_BATCH_NO_PREFIX + "%");
    }

    /**
     * 恢复α槽位到夹具前的真实状态。
     */
    private void restoreAlphaSlot() {
        if (originalSlotSnapshot == null) {
            return;
        }
        jdbcTemplate.update("UPDATE torn_stock_portfolio_slot SET slot_status = ?, current_batch_id = ?, "
                        + "available_cash = ?, reserved_cash = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?",
                originalSlotSnapshot.getSlotStatus(), originalSlotSnapshot.getCurrentBatchId(),
                originalSlotSnapshot.getAvailableCash(), originalSlotSnapshot.getReservedCash(), alphaSlotId);
    }

    /**
     * 构建满足批次表非空约束的α OPEN批次。
     *
     * @param batchNo 批次编号
     * @param stocksId 股票ID
     * @param slotId 槽位ID
     * @param slotNo 槽位序号
     * @return α OPEN批次
     */
    private TornStockVirtualBatchDO openAlphaBatch(String batchNo, Integer stocksId, Long slotId, Integer slotNo) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setBatchNo(batchNo);
        batch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        StockAlphaBatchIdentity.applyAlphaIdentity(batch);
        batch.setStocksId(stocksId);
        batch.setStocksShortname("ITTX");
        batch.setMatchedStrategies("[\"ALPHA\"]");
        batch.setQualityScore(BigDecimal.ZERO);
        batch.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        batch.setSlotId(slotId);
        batch.setSlotNo(slotNo);
        batch.setSignalTime(DECISION_BAR);
        batch.setSignalReferencePrice(new BigDecimal("100.00"));
        batch.setExpectedEntryBarTime(EXECUTION_BAR);
        batch.setEntryStaleAt(EXECUTION_BAR.plusMinutes(35));
        batch.setEntryTime(EXECUTION_BAR);
        batch.setEntryReferencePrice(new BigDecimal("100.00"));
        batch.setQuantity(5L);
        batch.setInvestedCash(AVAILABLE_CASH);
        batch.setRemainingCash(AVAILABLE_CASH);
        batch.setPeakPrice(new BigDecimal("100.00"));
        batch.setTroughPrice(new BigDecimal("100.00"));
        batch.setCurrentNetReturn(BigDecimal.ZERO);
        batch.setMfe(BigDecimal.ZERO);
        batch.setMae(BigDecimal.ZERO);
        batch.setPeakDrawdown(BigDecimal.ZERO);
        batch.setFollowUntil(EXECUTION_BAR.plusMinutes(60));
        batch.setFollowMaxPrice(new BigDecimal("100.15"));
        batch.setStylePrior(StockStrategyFitEnum.ALPHA_NOT_EVALUATED.getCode());
        batch.setStyleMaturity(StockMaturityEnum.ALPHA_NOT_EVALUATED.getCode());
        batch.setRiskLevel(StockRiskLevelEnum.ALPHA_NOT_EVALUATED.getCode());
        batch.setStyleEffectiveMonth(DECISION_DATE.withDayOfMonth(1));
        batch.setStyleRuleVersion(StockAlphaRuleDefinition.STYLE_RULE_VERSION);
        batch.setRiskRuleVersion(StockAlphaRuleDefinition.RISK_RULE_VERSION);
        batch.setResetObserved(false);
        return batch;
    }

    /**
     * 构建待消费的α目标变化换仓决策。
     *
     * @param originalBatchId 原仓批次ID
     * @return 待消费换仓决策
     */
    private TornStockAlphaDecisionDO pendingRebalanceDecision(Long originalBatchId) {
        TornStockAlphaDecisionDO decision = new TornStockAlphaDecisionDO();
        decision.setDecisionBusinessDate(DECISION_DATE);
        decision.setCommonDayIndex(60);
        decision.setPhase(PHASE);
        decision.setDecisionType(StockAlphaTargetPolicy.TargetEvent.ALPHA_TARGET_CHANGED.name());
        decision.setCurrentBatchId(originalBatchId);
        decision.setSelectedStocksId(REPLACEMENT_STOCKS_ID);
        decision.setSourceSnapshotDigest("IT-ALPHA-REBALANCE-SOURCE-DIGEST");
        decision.setSignalReferencePrice(new BigDecimal("99.00"));
        decision.setExecutionBarStartTime(EXECUTION_BAR);
        decision.setDecisionBarStartTime(DECISION_BAR);
        decision.setExecutionStatus("PENDING");
        return decision;
    }

    /**
     * 构建仅包含换仓两腿执行bar的轮次快照。
     *
     * @return 轮次快照
     */
    private RoundSnapshot roundSnapshot() {
        return new RoundSnapshot(
                List.of(marketBar(ORIGINAL_STOCKS_ID, "ITSELL", SELL_PRICE),
                        marketBar(REPLACEMENT_STOCKS_ID, "ITBUY", BUY_PRICE)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), EXECUTION_BAR);
    }

    /**
     * 构建执行桶内已结束且可用的15分钟bar。
     *
     * @param stocksId       股票ID
     * @param stocksShortname 股票简称(批次表简称上限8字符)
     * @param lastPrice       最后价
     * @return 行情bar
     */
    private TornStockMarketBar15mDO marketBar(Integer stocksId, String stocksShortname, BigDecimal lastPrice) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(stocksId);
        bar.setStocksShortname(stocksShortname);
        bar.setBarStartTime(EXECUTION_BAR);
        bar.setBarEndTime(EXECUTION_BAR.plusMinutes(15));
        bar.setLastPrice(lastPrice);
        bar.setUsable(true);
        return bar;
    }
}
