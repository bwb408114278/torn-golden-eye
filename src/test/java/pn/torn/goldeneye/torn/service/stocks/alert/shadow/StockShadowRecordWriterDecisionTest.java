package pn.torn.goldeneye.torn.service.stocks.alert.shadow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockEligibilityResultEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.NoticeRebalanceAssociation;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockEligibilityService.EligibilityResult;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 股票影子记录写入器决策测试,覆盖资格通过与实际正式接纳事实分离。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.07.28
 */
@DisplayName("股票影子记录写入器决策测试")
@ExtendWith(MockitoExtension.class)
class StockShadowRecordWriterDecisionTest {
    /**
     * α换仓执行桶。
     */
    private static final LocalDateTime EXECUTION_BAR = LocalDateTime.of(2026, 9, 5, 0, 15);

    @Mock
    private TornStockNoticeAuditDAO noticeAuditDao;
    @Mock
    private ProjectProperty projectProperty;
    @Mock
    private StockMarketClock marketClock;

    @Test
    @DisplayName("α换仓通知_双腿均为ALPHA_REBALANCE且SELL通知关联原BUY批次")
    void writeNoticeAudits_alphaRebalance_linksOriginalBuyAndFinalReason() {
        when(marketClock.now()).thenReturn(EXECUTION_BAR);
        TornStockVirtualBatchDO soldBatch = alphaBatch(7L, "A-OLD", StockBatchStatusEnum.CLOSED_ROTATION.getCode());
        soldBatch.setExitReason("ALPHA_REBALANCE");
        soldBatch.setExitReferencePrice(new BigDecimal("110.00"));
        soldBatch.setExitTime(EXECUTION_BAR);
        soldBatch.setSellProceeds(new BigDecimal("549.45"));
        soldBatch.setNetReturn(new BigDecimal("0.0980"));
        soldBatch.setSellRuleVersion("ALPHA_REBALANCE_ONLY");
        TornStockVirtualBatchDO boughtBatch = alphaBatch(99L, "AR-2026-09-05-0-11", StockBatchStatusEnum.OPEN.getCode());
        boughtBatch.setEntryReferencePrice(new BigDecimal("10.00"));
        boughtBatch.setQuantity(1L);
        boughtBatch.setInvestedCash(new BigDecimal("10.00"));
        boughtBatch.setBuyRuleVersion("ALPHA_0.04_V1");

        StockShadowRecordWriter writer =
                new StockShadowRecordWriter(noticeAuditDao, projectProperty, marketClock);
        writer.writeNoticeAudits(List.of(boughtBatch), List.of(soldBatch), EXECUTION_BAR,
                new NoticeRebalanceAssociation(11L, 7L, 99L));

        ArgumentCaptor<List<TornStockNoticeAuditDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(noticeAuditDao).saveBatch(captor.capture());
        List<TornStockNoticeAuditDO> notices = captor.getValue();
        assertEquals(2, notices.size(), "换仓两腿必须各产生一条通知审计");

        TornStockNoticeAuditDO buyNotice = noticeByBatchId(notices, 99L);
        TornStockNoticeAuditDO sellNotice = noticeByBatchId(notices, 7L);
        assertEquals("ALPHA_REBALANCE", buyNotice.getNoticeType());
        assertEquals("ALPHA_REBALANCE", sellNotice.getNoticeType(), "α退出最终原因固定为ALPHA_REBALANCE");
        assertTrue(sellNotice.getPayloadSnapshot().contains("\"exitReason\":\"ALPHA_REBALANCE\""),
                "SELL通知必须固化α换仓退出原因");
        assertTrue(sellNotice.getPayloadSnapshot().contains("VIP_ALPHA"), "SELL通知必须标记α组合来源");
        assertTrue(buyNotice.getPayloadSnapshot().contains("VIP_ALPHA"), "BUY通知必须标记α组合来源");
        assertEquals("ALPHA_V1", buyNotice.getMessageRuleVersion(),
                "α通知审计的消息规则版本必须与批次(ALPHA_V1)一致,不得回落旧版默认值");
        assertEquals("ALPHA_V1", sellNotice.getMessageRuleVersion(),
                "α卖出通知审计的消息规则版本必须与批次一致");
        assertEquals(StockNoticeStatusEnum.PENDING.getCode(), sellNotice.getSendStatus());
        assertEquals(EXECUTION_BAR, sellNotice.getScheduledRoundTime());
        // 原BUY关联: SELL通知绑定被换出的原持仓批次,BUY通知绑定新仓批次,两条审计同属一次换仓
        assertEquals(7L, sellNotice.getBatchId());
        assertEquals(99L, buyNotice.getBatchId());
        assertRebalanceAssociation(sellNotice, "SELL", 1);
        assertRebalanceAssociation(buyNotice, "BUY", 2);
    }

    @Test
    @DisplayName("旧版普通买卖通知_不得写入任何Alpha换仓关联字段")
    void writeNoticeAudits_legacyNotice_hasNoAlphaRebalanceFields() {
        when(marketClock.now()).thenReturn(EXECUTION_BAR);
        TornStockVirtualBatchDO boughtBatch = legacyBatch(51L, "B-LEGACY-BUY", StockBatchStatusEnum.OPEN.getCode());
        boughtBatch.setEntryReferencePrice(new BigDecimal("10.00"));
        boughtBatch.setQuantity(1L);
        boughtBatch.setInvestedCash(new BigDecimal("10.00"));
        TornStockVirtualBatchDO soldBatch = legacyBatch(52L, "B-LEGACY-SELL",
                StockBatchStatusEnum.CLOSED_TARGET.getCode());
        soldBatch.setExitReason("CLOSED_TARGET");
        soldBatch.setEntryReferencePrice(new BigDecimal("10.00"));
        soldBatch.setExitReferencePrice(new BigDecimal("11.00"));
        soldBatch.setExitTime(EXECUTION_BAR);
        soldBatch.setNetReturn(new BigDecimal("0.0980"));

        StockShadowRecordWriter writer =
                new StockShadowRecordWriter(noticeAuditDao, projectProperty, marketClock);
        writer.writeNoticeAudits(List.of(boughtBatch), List.of(soldBatch), EXECUTION_BAR);

        ArgumentCaptor<List<TornStockNoticeAuditDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(noticeAuditDao).saveBatch(captor.capture());
        for (TornStockNoticeAuditDO notice : captor.getValue()) {
            assertTrue("BUY".equals(notice.getNoticeType()) || "SELL".equals(notice.getNoticeType()),
                    "旧版通知类型不得被改写为ALPHA_REBALANCE");
            String payload = notice.getPayloadSnapshot();
            assertFalse(payload.contains("rebalanceAssociationId"), "旧版通知不得携带换仓关联标识");
            assertFalse(payload.contains("rebalanceDecisionId"), "旧版通知不得携带换仓决策ID");
            assertFalse(payload.contains("originalBatchId"), "旧版通知不得携带原仓批次ID");
            assertFalse(payload.contains("replacementBatchId"), "旧版通知不得携带新仓批次ID");
            assertFalse(payload.contains("rebalanceLeg"), "旧版通知不得携带换仓腿标识");
            assertFalse(payload.contains("legOrder"), "旧版通知不得携带换仓腿顺序");
        }
    }

    /**
     * 断言通知审计固化了一次换仓的统一关联事实与腿标识。
     *
     * @param notice 通知审计
     * @param leg    期望腿标识
     * @param order  期望腿顺序
     */
    private void assertRebalanceAssociation(TornStockNoticeAuditDO notice, String leg, int order) {
        String payload = notice.getPayloadSnapshot();
        assertTrue(payload.contains("\"rebalanceDecisionId\":11"), "必须固化为换仓决策ID");
        assertTrue(payload.contains("\"rebalanceAssociationId\":\"ALPHA_REBALANCE:11\""),
                "换仓关联标识必须为固定格式ALPHA_REBALANCE:{决策ID}");
        assertTrue(payload.contains("\"originalBatchId\":7"), "必须固化被换出的原仓批次ID");
        assertTrue(payload.contains("\"replacementBatchId\":99"), "必须固化换仓后的新仓批次ID");
        assertTrue(payload.contains("\"rebalanceLeg\":\"" + leg + "\""), "必须固化本通知的换仓腿标识");
        assertTrue(payload.contains("\"legOrder\":" + order), "必须固化本通知的换仓腿顺序");
    }

    /**
     * 构建旧版正式组合批次。
     *
     * @param id          批次ID
     * @param batchNo     批次编号
     * @param batchStatus 批次状态
     * @return 旧版批次
     */
    private TornStockVirtualBatchDO legacyBatch(Long id, String batchNo, String batchStatus) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(id);
        batch.setBatchNo(batchNo);
        batch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        batch.setPortfolioCode(StockPortfolioService.PORTFOLIO_CODE);
        batch.setStocksId(1001);
        batch.setStocksShortname("LEGACY");
        batch.setPrimaryStrategy("RANGE_LOWER_BUY");
        batch.setSlotId(1L);
        batch.setSlotNo(1);
        batch.setBatchStatus(batchStatus);
        batch.setMessageRuleVersion("1.2.14");
        return batch;
    }

    @Test
    @DisplayName("资格通过但正式批次为空_组合决策为SHADOW")
    void determinePortfolioDecision_allowedWithoutFormalBatch_returnsShadow() throws Exception {
        StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(null, null);
        Method method = StockShadowTrackRecorder.class.getDeclaredMethod(
                "determinePortfolioDecision",
                StockShadowTrackRecorder.SignalEvaluationView.class,
                EligibilityResult.class,
                TornStockVirtualBatchDO.class);
        method.setAccessible(true);

        StockShadowTrackRecorder.SignalEvaluationView evaluation = new EvaluationView(true);
        EligibilityResult eligibility = new EligibilityResult(
                StockEligibilityResultEnum.ALLOWED, List.of());

        String decision = (String) method.invoke(recorder, evaluation, eligibility, null);

        assertEquals("SHADOW", decision);
    }

    @Test
    @DisplayName("资格通过且正式批次已保存_组合决策为FORMAL")
    void determinePortfolioDecision_allowedWithSavedFormalBatch_returnsFormal() throws Exception {
        StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(null, null);
        Method method = StockShadowTrackRecorder.class.getDeclaredMethod(
                "determinePortfolioDecision",
                StockShadowTrackRecorder.SignalEvaluationView.class,
                EligibilityResult.class,
                TornStockVirtualBatchDO.class);
        method.setAccessible(true);

        StockShadowTrackRecorder.SignalEvaluationView evaluation = new EvaluationView(true);
        EligibilityResult eligibility = new EligibilityResult(
                StockEligibilityResultEnum.ALLOWED, List.of());
        TornStockVirtualBatchDO formalBatch = new TornStockVirtualBatchDO();
        formalBatch.setId(100L);

        String decision = (String) method.invoke(recorder, evaluation, eligibility, formalBatch);

        assertEquals("FORMAL", decision);
    }

    /**
     * 构建指定ID、编号和状态的VIP_ALPHA正式批次。
     *
     * @param id          批次ID
     * @param batchNo     批次编号
     * @param batchStatus 批次状态
     * @return α批次
     */
    private TornStockVirtualBatchDO alphaBatch(Long id, String batchNo, String batchStatus) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(id);
        batch.setBatchNo(batchNo);
        batch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        batch.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        batch.setStocksId(1001);
        batch.setStocksShortname("ALPHA");
        batch.setPrimaryStrategy("ALPHA");
        batch.setSlotId(1L);
        batch.setSlotNo(1);
        batch.setBatchStatus(batchStatus);
        batch.setMessageRuleVersion("ALPHA_V1");
        return batch;
    }

    /**
     * 按关联批次ID查找通知审计。
     *
     * @param notices 通知审计列表
     * @param batchId 关联批次ID
     * @return 通知审计
     */
    private TornStockNoticeAuditDO noticeByBatchId(List<TornStockNoticeAuditDO> notices, Long batchId) {
        return notices.stream()
                .filter(notice -> batchId.equals(notice.getBatchId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少批次通知审计: batchId=" + batchId));
    }

    private record EvaluationView(boolean acceptedFormal)
            implements StockShadowTrackRecorder.SignalEvaluationView {
        @Override
        public Integer stocksId() {
            return 1001;
        }

        @Override
        public String stocksShortname() {
            return "TST";
        }

        @Override
        public pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy.StockBuyStrategy primaryStrategy() {
            return null;
        }

        @Override
        public java.util.List<pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy.StockBuyStrategy> matchedStrategies() {
            return List.of();
        }

        @Override
        public java.math.BigDecimal qualityScore() {
            return null;
        }

        @Override
        public boolean edgeTriggered() {
            return true;
        }

        @Override
        public pn.torn.goldeneye.torn.service.stocks.alert.signal.BuyContext context() {
            return null;
        }

        @Override
        public pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO monthlyState() {
            return null;
        }

        @Override
        public EligibilityResult eligibilityResult() {
            return null;
        }

        @Override
        public boolean acceptedFormal() {
            return acceptedFormal;
        }
    }
}
