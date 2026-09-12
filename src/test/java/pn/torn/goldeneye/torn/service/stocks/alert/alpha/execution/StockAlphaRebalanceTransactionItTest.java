package pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.transaction.TestTransaction;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

/**
 * α换仓真实事务回滚集成测试。
 * <p>
 * 唯一业务测试方法在真实Spring事务中调用真实 {@link StockAlphaRebalanceService},通过
 * {@link StockShadowRecordWriter} 的测试替身注入换仓事务最后阶段(通知审计写入)失败,
 * 证明换仓后段失败时原仓、槽位资金、决策、新仓和通知审计不会留下任何单侧事实。
 * <p>
 * 所有夹具写入与换仓写入都显式处于测试管理的 {@code @Transactional} + {@code @Rollback} 事务中,
 * 全部通过真实DAO完成,测试文件内不写SQL,也不使用测试事务之外已提交的夹具与手工清理:
 * <ul>
 *   <li>事务内阶段: 断言换仓已推进到最后一跳(原仓已按换仓关闭并写入出场事实、新仓已建立、
 *       槽位已改绑新仓、决策已推进为已执行),证明失败发生在整条换仓链的末端</li>
 *   <li>回滚阶段: 用 {@link TestTransaction} 结束并回滚该事务,再开启独立只读事务,
 *       用真实DAO读回提交态:夹具原仓不存在、换仓新仓不存在、换仓通知审计为0、换仓决策不存在、
 *       α槽位状态与资金恢复为测试前快照</li>
 * </ul>
 * 两个阶段共同证明:换仓的所有写入都发生在调用方事务内,最后一跳失败时不会留下原仓已卖出或
 * 新仓已建立之类的单侧事实。
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
@Transactional
@Rollback
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
    /**
     * 注入的通知审计写入失败原因。
     */
    private static final String AUDIT_FAILURE_MESSAGE = "模拟通知审计写入失败";

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

    /**
     * 在真实PostgreSQL上验证换仓事务最后阶段失败后整体回滚。
     */
    @Test
    @DisplayName("真实事务_换仓通知审计写入失败后整体回滚")
    void rebalance_noticeAuditFailure_rollsBackWholeTransaction() {
        assertTrue(TestTransaction.isActive(), "夹具与换仓写入必须处于测试管理的回滚事务中");
        TornStockPortfolioSlotDO alphaSlot = loadAlphaSlot();
        SlotSnapshot slotBefore = SlotSnapshot.of(alphaSlot);
        Long originalBatchId = persistOriginalBatch(alphaSlot);
        bindSlotToOriginalBatch(alphaSlot, originalBatchId);
        Long decisionId = persistRebalanceDecision(originalBatchId);
        AtomicReference<NoticeRebalanceAssociation> associationHolder = new AtomicReference<>();

        doReturn(true).when(decisionService).isSourceReproducible(any(TornStockAlphaDecisionDO.class));
        doAnswer(invocation -> {
            associationHolder.set(invocation.getArgument(3));
            throw new IllegalStateException(AUDIT_FAILURE_MESSAGE);
        }).when(noticeWriter)
                .writeNoticeAudits(anyList(), anyList(), eq(EXECUTION_BAR), any(NoticeRebalanceAssociation.class));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> rebalanceService.rebalance(DECISION_DATE, PHASE, PROCESSING_TIME, roundSnapshot()),
                "换仓事务最后阶段的通知审计写入失败必须使整个换仓事务失败");
        assertTrue(exception.getMessage().contains(AUDIT_FAILURE_MESSAGE),
                "失败必须来自注入的通知审计写入,实际: " + exception.getMessage());

        // 事务内阶段: 换仓已推进到末端,原仓结算、新仓建立、槽位改绑与决策推进都已写入同一事务
        assertRebalanceWritesVisible(associationHolder.get(), originalBatchId, decisionId, alphaSlot.getId());

        // 回滚阶段: 真实回滚测试事务后,在独立事务中用真实DAO读回提交态
        TestTransaction.flagForRollback();
        TestTransaction.end();
        assertFalse(TestTransaction.isActive(), "测试事务必须已真实结束并回滚");
        TestTransaction.start();
        assertNoResidualFacts(slotBefore, associationHolder.get());
    }

    /**
     * 读取α槽位并校验其可作为换仓前置状态。
     * <p>
     * 槽位必须已按生产规则初始化且没有在途批次,否则夹具原仓批次无法占用该槽位。
     *
     * @return α槽位
     */
    private TornStockPortfolioSlotDO loadAlphaSlot() {
        List<TornStockPortfolioSlotDO> slots =
                slotDAO.selectAllByPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        assertEquals(StockPortfolioService.VIP_ALPHA_SLOT_COUNT, slots.size(),
                "VIP_ALPHA槽位必须已按生产规则初始化");
        TornStockPortfolioSlotDO slot = slots.getFirst();
        assertNull(slot.getCurrentBatchId(), "α槽位必须没有在途批次,否则无法构造换仓前置状态");
        return slot;
    }

    /**
     * 落库隔离的α原仓批次并读回主键。
     *
     * @param slot α槽位
     * @return 已持久化原仓批次
     */
    private Long persistOriginalBatch(TornStockPortfolioSlotDO slot) {
        assertNull(batchDAO.selectByBatchNoForUpdate(ORIGINAL_BATCH_NO), "夹具批次不得与历史数据冲突");
        assertEquals(1, batchDAO.insertIgnoreConflict(
                        openAlphaBatch(ORIGINAL_BATCH_NO, ORIGINAL_STOCKS_ID, slot.getId(), slot.getSlotNo())),
                "α原仓批次必须真实落库");
        TornStockVirtualBatchDO persisted = batchDAO.selectByBatchNoForUpdate(ORIGINAL_BATCH_NO);
        assertNotNull(persisted, "α原仓批次必须可按批次号读回");
        return persisted.getId();
    }

    /**
     * 将α槽位改绑到夹具原仓批次并设置换仓前资金。
     *
     * @param slot            α槽位
     * @param originalBatchId 原仓批次ID
     */
    private void bindSlotToOriginalBatch(TornStockPortfolioSlotDO slot, Long originalBatchId) {
        slot.setSlotStatus(StockSlotStatusEnum.OCCUPIED.getCode());
        slot.setCurrentBatchId(originalBatchId);
        slot.setAvailableCash(AVAILABLE_CASH);
        slot.setReservedCash(RESERVED_CASH);
        assertTrue(slotDAO.updateById(slot), "α槽位必须真实改绑到夹具原仓批次");
    }

    /**
     * 落库待消费的α目标变化换仓决策并读回主键。
     *
     * @param originalBatchId 原仓批次ID
     * @return 决策ID
     */
    private Long persistRebalanceDecision(Long originalBatchId) {
        assertEquals(1, decisionDAO.insertIgnoreConflict(pendingRebalanceDecision(originalBatchId)),
                "α换仓决策必须真实落库");
        TornStockAlphaDecisionDO persisted =
                decisionDAO.selectByBusinessKeyForUpdate(DECISION_DATE, PHASE);
        assertNotNull(persisted, "α换仓决策必须可按业务键读回");
        return persisted.getId();
    }

    /**
     * 断言换仓在事务内已推进到通知审计写入之前,即原仓、新仓、槽位与决策的写入均已发生。
     *
     * @param association     换仓关联事实(由失败的审计写入调用捕获)
     * @param originalBatchId 夹具原仓批次ID
     * @param decisionId      换仓决策ID
     */
    private void assertRebalanceWritesVisible(NoticeRebalanceAssociation association, Long originalBatchId,
                                              Long decisionId, Long alphaSlotId) {
        assertNotNull(association, "换仓必须推进到通知审计写入阶段");
        assertEquals(originalBatchId, association.originalBatchId(), "关联事实必须指向夹具原仓批次");
        assertEquals(decisionId, association.rebalanceDecisionId(), "关联事实必须指向夹具换仓决策");
        Long replacementBatchId = association.replacementBatchId();

        TornStockVirtualBatchDO original = batchDAO.getById(originalBatchId);
        assertNotNull(original, "事务内原仓批次必须存在");
        assertEquals(StockBatchStatusEnum.CLOSED_ROTATION.getCode(), original.getBatchStatus(),
                "事务内原仓必须已按换仓关闭");
        assertEquals(EXECUTION_BAR, original.getExitTime(), "事务内原仓必须写入换仓出场时间");
        assertNotNull(original.getExitReferencePrice(), "事务内原仓必须写入换仓出场参考价");
        assertEquals(0, SELL_PRICE.compareTo(original.getExitReferencePrice()),
                "事务内原仓出场参考价必须等于换仓卖出bar价格");
        assertEquals(StockAlphaRuleDefinition.EXIT_REASON_REBALANCE, original.getExitReason(),
                "事务内原仓退出原因必须为换仓");
        assertNotNull(original.getSellProceeds(), "事务内原仓必须结算卖出所得");
        assertNotNull(original.getNetReturn(), "事务内原仓必须写入净收益");

        assertNotNull(batchDAO.selectByBatchNoForUpdate(REPLACEMENT_BATCH_NO_PREFIX + decisionId),
                "事务内换仓新仓必须已建立");
        TornStockPortfolioSlotDO slot = slotDAO.getById(alphaSlotId);
        assertNotNull(slot, "事务内α槽位必须存在");
        assertEquals(replacementBatchId, slot.getCurrentBatchId(), "事务内α槽位必须已改绑换仓新仓");

        TornStockAlphaDecisionDO decision = decisionDAO.getById(decisionId);
        assertNotNull(decision, "事务内换仓决策必须存在");
        assertEquals("EXECUTED", decision.getExecutionStatus(), "事务内决策必须已推进为已执行");
        assertEquals(replacementBatchId, decision.getRebalanceBatchId(), "事务内决策必须绑定换仓新仓");
    }

    /**
     * 断言回滚后提交态不残留任何换仓事实,且α槽位恢复为测试前快照。
     *
     * @param slotBefore  测试前α槽位快照
     * @param association 换仓关联事实
     */
    private void assertNoResidualFacts(SlotSnapshot slotBefore, NoticeRebalanceAssociation association) {
        assertNotNull(association, "换仓必须推进到通知审计写入阶段");
        assertNull(batchDAO.selectByBatchNoForUpdate(ORIGINAL_BATCH_NO), "回滚后不得残留夹具原仓批次");
        assertNull(batchDAO.selectByBatchNoForUpdate(
                        REPLACEMENT_BATCH_NO_PREFIX + association.rebalanceDecisionId()),
                "回滚后不得残留换仓新仓批次");
        assertTrue(noticeAuditDAO.selectByRebalanceAssociationId(association.associationId()).isEmpty(),
                "回滚后不得残留换仓通知审计");
        assertNull(decisionDAO.getById(association.rebalanceDecisionId()), "回滚后不得残留换仓决策");

        TornStockPortfolioSlotDO slot = slotDAO.getById(slotBefore.slotId());
        assertNotNull(slot, "α槽位必须仍然存在");
        assertEquals(slotBefore.slotStatus(), slot.getSlotStatus(), "回滚后α槽位状态必须恢复为测试前值");
        assertEquals(slotBefore.currentBatchId(), slot.getCurrentBatchId(),
                "回滚后α槽位当前批次必须恢复为测试前值");
        assertEquals(0, slotBefore.availableCash().compareTo(slot.getAvailableCash()),
                "回滚后α槽位可用现金必须恢复为测试前值");
        assertEquals(0, slotBefore.reservedCash().compareTo(slot.getReservedCash()),
                "回滚后α槽位预留现金必须恢复为测试前值");
    }

    /**
     * 构建满足批次表非空约束的α OPEN批次。
     *
     * @param batchNo  批次编号
     * @param stocksId 股票ID
     * @param slotId   槽位ID
     * @param slotNo   槽位序号
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
     * @param stocksId        股票ID
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

    /**
     * 测试前α槽位快照,用于校验回滚后槽位恢复到测试前值。
     *
     * @param slotId         槽位ID
     * @param slotStatus     槽位状态
     * @param currentBatchId 当前绑定批次ID
     * @param availableCash  可用资金
     * @param reservedCash   预留资金
     */
    private record SlotSnapshot(
            Long slotId,
            String slotStatus,
            Long currentBatchId,
            BigDecimal availableCash,
            BigDecimal reservedCash) {
        /**
         * 从槽位DO构建快照。
         *
         * @param slot α槽位
         * @return 槽位快照
         */
        private static SlotSnapshot of(TornStockPortfolioSlotDO slot) {
            return new SlotSnapshot(slot.getId(), slot.getSlotStatus(), slot.getCurrentBatchId(),
                    slot.getAvailableCash(), slot.getReservedCash());
        }
    }
}
