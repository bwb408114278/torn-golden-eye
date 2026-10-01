package pn.torn.goldeneye.torn.service.stocks.alert.alpha.notice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeTypeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.track.StockAlphaPhaseTrack;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.track.StockAlphaTrackRegistry;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticePayloadReader;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * α继续持有通知服务测试，验证正式/影子轨道分流、幂等复用与正文固化。
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("α继续持有通知服务测试")
class StockAlphaHoldNoticeServiceTest {

    private static final LocalDate DECISION_DATE = LocalDate.of(2026, 9, 30);
    private static final LocalDateTime EXECUTION_BAR = LocalDateTime.of(2026, 10, 1, 8, 30);
    private static final long VIP_GROUP_ID = 909796613L;

    @Mock
    private TornStockNoticeAuditDAO noticeAuditDAO;
    @Mock
    private ProjectProperty projectProperty;

    private StockAlphaHoldNoticeService service;

    @BeforeEach
    void setUp() {
        lenient().when(projectProperty.getVipGroupId()).thenReturn(VIP_GROUP_ID);
        service = new StockAlphaHoldNoticeService(noticeAuditDAO, projectProperty, new StockMarketClock());
    }

    @Test
    @DisplayName("正式轨道_写PENDING自包含审计且正文为定稿继续持有文案")
    void recordHoldNotice_productionTrack_writesPendingSelfContainedAudit() {
        TornStockVirtualBatchDO batch = openBatch();
        when(noticeAuditDAO.selectBySummaryDateTypeAndTrack(
                DECISION_DATE, StockNoticeTypeEnum.ALPHA_HOLD.getCode(), StockAlphaTrackRegistry.VIP_ALPHA.trackCode()))
                .thenReturn(null);

        service.recordHoldNotice(StockAlphaTrackRegistry.VIP_ALPHA, DECISION_DATE, 1, EXECUTION_BAR, batch);

        TornStockNoticeAuditDO saved = captureSavedNotice();
        assertEquals(StockNoticeTypeEnum.ALPHA_HOLD.getCode(), saved.getNoticeType());
        assertEquals(DECISION_DATE, saved.getSummaryDate(), "幂等键第一分量必须为决策业务日");
        assertEquals(StockAlphaTrackRegistry.VIP_ALPHA.trackCode(), saved.getTrackCode(), "幂等键第二分量必须为轨道编码");
        assertEquals(batch.getId(), saved.getBatchId(), "应固化当前持仓批次");
        assertEquals(VIP_GROUP_ID, saved.getGroupId());
        assertEquals(StockNoticeStatusEnum.PENDING.getCode(), saved.getSendStatus(), "正式轨道必须进入可发送链");
        assertEquals(0, saved.getSendAttemptCount());
        assertEquals("1.0.0", saved.getMessageRuleVersion());
        assertEquals(EXECUTION_BAR, saved.getScheduledRoundTime());
        assertTrue(saved.getNoticeNo().startsWith("H"), "通知编号必须以H前缀区分批次N/日报D/年报A");
        assertTrue(saved.getNoticeNo().endsWith(StockAlphaTrackRegistry.VIP_ALPHA.trackCode()),
                "同一毫秒多轨道通知必须靠轨道后缀区分");
        assertNotNull(saved.getPayloadHash());
        assertEquals(64, saved.getPayloadHash().length());
        assertEquals(expectedHoldText(), StockNoticePayloadReader.readMessageText(saved),
                "载荷必须逐字固化方案定稿正文");
        assertTrue(saved.getPayloadSnapshot().contains("\"trackCode\""), "载荷必须可复核轨道事实");
        assertTrue(saved.getPayloadSnapshot().contains("\"decisionBusinessDate\""), "载荷必须可复核决策业务日");
        assertTrue(saved.getPayloadSnapshot().contains("\"phase\""), "载荷必须可复核相位");
    }

    @Test
    @DisplayName("影子轨道_写SHADOW_RECORDED审计只留痕不投递")
    void recordHoldNotice_shadowTrack_writesShadowRecordedAudit() {
        StockAlphaPhaseTrack shadowTrack = StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_FIRST;
        when(noticeAuditDAO.selectBySummaryDateTypeAndTrack(
                DECISION_DATE, StockNoticeTypeEnum.ALPHA_HOLD.getCode(), shadowTrack.trackCode())).thenReturn(null);

        service.recordHoldNotice(shadowTrack, DECISION_DATE, 0, EXECUTION_BAR, openBatch());

        TornStockNoticeAuditDO saved = captureSavedNotice();
        assertEquals(StockNoticeStatusEnum.SHADOW_RECORDED.getCode(), saved.getSendStatus(),
                "影子轨道只记录不投递");
        assertEquals(shadowTrack.trackCode(), saved.getTrackCode());
        assertEquals(expectedHoldText(), StockNoticePayloadReader.readMessageText(saved));
    }

    @Test
    @DisplayName("同决策日同轨道已有审计行_直接复用且不重复建行")
    void recordHoldNotice_existingAudit_reusesWithoutSaving() {
        TornStockNoticeAuditDO existing = new TornStockNoticeAuditDO();
        existing.setNoticeNo("H20261001083000000-VIP_ALPHA#1");
        when(noticeAuditDAO.selectBySummaryDateTypeAndTrack(
                DECISION_DATE, StockNoticeTypeEnum.ALPHA_HOLD.getCode(), StockAlphaTrackRegistry.VIP_ALPHA.trackCode()))
                .thenReturn(existing);

        service.recordHoldNotice(StockAlphaTrackRegistry.VIP_ALPHA, DECISION_DATE, 1, EXECUTION_BAR, openBatch());

        verify(noticeAuditDAO, never()).save(any());
        verify(noticeAuditDAO, never()).saveBatch(any());
    }

    private TornStockNoticeAuditDO captureSavedNotice() {
        ArgumentCaptor<TornStockNoticeAuditDO> captor = ArgumentCaptor.forClass(TornStockNoticeAuditDO.class);
        verify(noticeAuditDAO).save(captor.capture());
        return captor.getValue();
    }

    /**
     * 方案§13.6.2(2)定稿文案：继续持有通知必须逐字一致。
     *
     * @return 定稿通知全文
     */
    private String expectedHoldText() {
        return """
                【α股票提醒 · 继续持有】
                
                决策日：2026-09-30（08:00 决策 → 08:15 执行桶）
                当前持仓：CNC（仍在 Top3 内，Top1 未变化）
                处理：本期不换仓，继续持有原批次。
                
                未持有该标的的成员无需操作。""";
    }

    private TornStockVirtualBatchDO openBatch() {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(61L);
        batch.setBatchNo("AB-2026-09-20-5001");
        batch.setStocksId(5001);
        batch.setStocksShortname("CNC");
        return batch;
    }
}
