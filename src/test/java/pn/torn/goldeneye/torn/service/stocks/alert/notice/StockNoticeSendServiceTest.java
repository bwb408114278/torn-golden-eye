package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.rebalance.StockRebalanceNoticeSender;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 股票通知发送服务测试,覆盖NapCat响应判定、开关门禁和无关联批次处理。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.07.28
 */
@DisplayName("股票通知发送服务测试")
@ExtendWith(MockitoExtension.class)
class StockNoticeSendServiceTest {

    /**
     * 测试用α换仓关联标识。
     */
    private static final String REBALANCE_ASSOCIATION = "ALPHA_REBALANCE:11";

    @Mock
    private Bot bot;

    @Mock
    private ProjectProperty projectProperty;

    @Mock
    private SysSettingManager sysSettingManager;

    @Mock
    private TornStockNoticeAuditDAO noticeAuditDAO;

    @Mock
    private TornStockVirtualBatchDAO virtualBatchDAO;

    @Mock
    private StockNoticeComposeService composeService;

    @Test
    @DisplayName("NapCat返回成功响应_sendSingleMessage返回true")
    void sendSingleMessage_successResponse_returnsTrue() {
        StockNoticeSendService service = service();
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        assertTrue(service.sendSingleMessage("测试通知"));
        verify(bot).sendRequest(any(BotHttpReqParam.class), eq(String.class));
    }

    @Test
    @DisplayName("NapCat返回业务失败_sendSingleMessage返回false")
    void sendSingleMessage_businessFailure_returnsFalse() {
        StockNoticeSendService service = service();
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"failed\",\"retcode\":100}"));

        assertFalse(service.sendSingleMessage("测试通知"));
    }

    @Test
    @DisplayName("HTTP响应非2xx_sendSingleMessage返回false")
    void sendSingleMessage_httpFailure_returnsFalse() {
        StockNoticeSendService service = service();
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("{}"));

        assertFalse(service.sendSingleMessage("测试通知"));
    }

    @Test
    @DisplayName("Bot发送异常_sendSingleMessage返回false")
    void sendSingleMessage_exception_returnsFalse() {
        StockNoticeSendService service = service();
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenThrow(new IllegalStateException("bot unavailable"));

        assertFalse(service.sendSingleMessage("测试通知"));
    }

    @Test
    @DisplayName("正式通知开关关闭_sendPendingNotices不查询通知")
    void sendPendingNotices_disabled_skipsNoticeQuery() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("false");

        service().sendPendingNotices();

        verify(noticeAuditDAO, never()).selectPendingNotices();
        verify(virtualBatchDAO, never()).listByIds(any());
    }

    @Test
    @DisplayName("通知缺少关联批次_批量标记FAILED且不发送")
    void sendPendingNotices_missingBatch_marksFailedWithoutSending() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
        notice.setId(10L);
        notice.setBatchId(null);
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(notice));

        service().sendPendingNotices();

        verify(noticeAuditDAO).markFailedByIds(List.of(10L), "关联虚拟交易批次不存在");
        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
        verify(composeService, never()).composeAndMergeNotices(any(), any());
    }

    @Test
    @DisplayName("通知发送HTTP失败_批量失败原因必须记录HTTP状态")
    void sendPendingNotices_httpFailure_recordsActualFailureReason() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO notice = notice(11L, 21L);
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(notice));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(21L)));
        when(composeService.composeAndMergeNotices(any(), any()))
                .thenReturn(List.of(new StockNoticeComposeService.ComposedMessage(List.of(11L), "测试通知")));
        when(noticeAuditDAO.finalizePayload(any())).thenReturn(1);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(noticeAuditDAO.markSendFailedByIds(eq(List.of(11L)), any())).thenReturn(1);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("{}"));

        service().sendPendingNotices();

        verify(noticeAuditDAO).markSendFailedByIds(eq(List.of(11L)), contains("HTTP状态非2xx"));
    }

    @Test
    @DisplayName("有效和缺失批次混合_缺失通知失败且有效通知继续发送")
    void sendPendingNotices_mixedBatchReferences_processesValidNoticeAndFailsMissingNotice() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO valid = notice(12L, 22L);
        TornStockNoticeAuditDO missing = new TornStockNoticeAuditDO();
        missing.setId(13L);
        missing.setBatchId(23L);
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(valid, missing));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(22L)));
        when(composeService.composeAndMergeNotices(any(), any()))
                .thenReturn(List.of(new StockNoticeComposeService.ComposedMessage(List.of(12L), "测试通知")));
        when(noticeAuditDAO.finalizePayload(any())).thenReturn(1);
        when(noticeAuditDAO.markSentByIds(List.of(12L))).thenReturn(1);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        service().sendPendingNotices();

        verify(noticeAuditDAO).markFailedByIds(List.of(13L), "关联虚拟交易批次不存在");
        verify(noticeAuditDAO).markSentByIds(List.of(12L));
        verify(bot).sendRequest(any(BotHttpReqParam.class), eq(String.class));
    }

    @Test
    @DisplayName("通知发送成功_调用成功状态批量更新")
    void sendPendingNotices_successfulResponse_marksNoticesSent() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO notice = notice(14L, 24L);
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(notice));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(24L)));
        when(composeService.composeAndMergeNotices(any(), any()))
                .thenReturn(List.of(new StockNoticeComposeService.ComposedMessage(List.of(14L), "测试通知")));
        when(noticeAuditDAO.finalizePayload(any())).thenReturn(1);
        when(noticeAuditDAO.markSentByIds(List.of(14L))).thenReturn(1);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        service().sendPendingNotices();

        verify(noticeAuditDAO).markSentByIds(List.of(14L));
        verify(noticeAuditDAO, never()).markSendFailedByIds(any(), any());
    }

    @Test
    @DisplayName("冻结命令_逐条保留业务字段且hash等于最终payload哈希")
    void sendPendingNotices_capturesFinalizeCommandPreservesFieldsAndHash() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO notice = notice(15L, 25L);
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(notice));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(25L)));
        when(composeService.composeAndMergeNotices(any(), any()))
                .thenReturn(List.of(new StockNoticeComposeService.ComposedMessage(List.of(15L), "灾难关闭文本")));
        when(noticeAuditDAO.finalizePayload(any())).thenReturn(1);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        service().sendPendingNotices();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NoticePayloadFinalizeCommand>> captor =
                ArgumentCaptor.forClass((Class<List<NoticePayloadFinalizeCommand>>) (Class<?>) List.class);
        verify(noticeAuditDAO).finalizePayload(captor.capture());
        List<NoticePayloadFinalizeCommand> commands = captor.getValue();
        assertEquals(1, commands.size(), "逐条冻结命令数必须等于通知数");
        NoticePayloadFinalizeCommand command = commands.getFirst();
        assertEquals(15L, command.noticeId());
        // 最终JSON必须保留创建时业务字段,不得被messageText/frozenAt覆盖
        assertTrue(command.payloadSnapshot().contains("\"noticeType\":\"SELL\""));
        assertTrue(command.payloadSnapshot().contains("\"batchId\":25"));
        assertTrue(command.payloadSnapshot().contains("\"batchNo\":\"B25\""));
        assertTrue(command.payloadSnapshot().contains("\"messageText\":\"灾难关闭文本\""));
        // hash必须基于最终完整payload计算,可复核
        assertEquals(StockNoticePayloadCanonicalizer.sha256(command.payloadSnapshot()), command.payloadHash(),
                "payloadHash必须等于最终完整payload的SHA-256");
    }

    @Test
    @DisplayName("冻结行数不符_部分更新禁止Bot发送")
    void sendPendingNotices_finalizePartialUpdate_stopsBotSending() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO notice = notice(16L, 26L);
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(notice));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(26L)));
        when(composeService.composeAndMergeNotices(any(), any()))
                .thenReturn(List.of(new StockNoticeComposeService.ComposedMessage(List.of(16L), "文本")));
        when(noticeAuditDAO.finalizePayload(any())).thenReturn(0);

        service().sendPendingNotices();

        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
        verify(noticeAuditDAO, never()).markSentByIds(any());
        verify(noticeAuditDAO, never()).markSendFailedByIds(any(), any());
    }

    @Test
    @DisplayName("已冻结PENDING通知_重启后复用冻结文本且不重复组合不重复冻结")
    void sendPendingNotices_frozenPendingNotice_reusesFrozenTextWithoutRecompose() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO frozen = new TornStockNoticeAuditDO();
        frozen.setId(17L);
        frozen.setBatchId(27L);
        frozen.setPayloadSnapshot("{\"noticeType\":\"SELL\",\"batchId\":27,\"batchNo\":\"B27\",\"stocksId\":1001,"
                + "\"messageText\":\"已冻结文本\",\"frozenAt\":\"2026-08-02T10:00:00\"}");
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(frozen));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(27L)));
        when(noticeAuditDAO.markSentByIds(List.of(17L))).thenReturn(1);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        service().sendPendingNotices();

        ArgumentCaptor<BotHttpReqParam> paramCaptor = ArgumentCaptor.forClass(BotHttpReqParam.class);
        verify(bot).sendRequest(paramCaptor.capture(), eq(String.class));
        assertTrue(String.valueOf(paramCaptor.getValue().body()).contains("已冻结文本"),
                "重启投递必须复用已冻结文本,而不是重新组合消息");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TornStockNoticeAuditDO>> composeCaptor =
                ArgumentCaptor.forClass((Class<List<TornStockNoticeAuditDO>>) (Class<?>) List.class);
        verify(composeService).composeAndMergeNotices(composeCaptor.capture(), any());
        assertTrue(composeCaptor.getValue().isEmpty(),
                "已冻结通知不得进入重新组合,必须复用已冻结文本");
        verify(noticeAuditDAO, never()).finalizePayload(any());
        verify(noticeAuditDAO).markSentByIds(List.of(17L));
    }

    @Test
    @DisplayName("已冻结PENDING通知_Bot失败不再次冻结payload且标记FAILED")
    void sendPendingNotices_frozenPendingNotice_botFailure_marksFailedWithoutRefinalize() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO frozen = new TornStockNoticeAuditDO();
        frozen.setId(18L);
        frozen.setBatchId(28L);
        frozen.setPayloadSnapshot("{\"noticeType\":\"SELL\",\"batchId\":28,\"batchNo\":\"B28\",\"stocksId\":1001,"
                + "\"messageText\":\"已冻结文本\",\"frozenAt\":\"2026-08-02T10:00:00\"}");
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(frozen));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(28L)));
        when(noticeAuditDAO.markSendFailedByIds(eq(List.of(18L)), any())).thenReturn(1);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("{}"));

        service().sendPendingNotices();

        verify(noticeAuditDAO, never()).finalizePayload(any());
        verify(noticeAuditDAO).markSendFailedByIds(eq(List.of(18L)), contains("HTTP状态非2xx"));
        verify(noticeAuditDAO, never()).markSentByIds(any());
    }

    @Test
    @DisplayName("α换仓两腿关联组_Bot成功时按完整关联组读取、两腿同时SENT且只调用一次Bot")
    void sendPendingNotices_alphaRebalanceBothLegs_bothMarkedSent() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO sellLeg = rebalanceLeg(31L, 501L, REBALANCE_ASSOCIATION, "SELL", 1);
        TornStockNoticeAuditDO buyLeg = rebalanceLeg(32L, 502L, REBALANCE_ASSOCIATION, "BUY", 2);
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(sellLeg, buyLeg));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(501L), batch(502L)));
        when(noticeAuditDAO.selectByRebalanceAssociationId(REBALANCE_ASSOCIATION))
                .thenReturn(List.of(sellLeg, buyLeg));
        when(composeService.composeAndMergeNotices(any(), any())).thenReturn(List.of(
                new StockNoticeComposeService.ComposedMessage(List.of(31L, 32L), "α换仓合并消息")));
        when(noticeAuditDAO.finalizePayload(any())).thenReturn(2);
        when(noticeAuditDAO.markSentByIds(List.of(31L, 32L))).thenReturn(2);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        service().sendPendingNotices();

        // 发送前必须按关联标识读取关联组完整通知集合,不得只依赖内存PENDING子集
        verify(noticeAuditDAO).selectByRebalanceAssociationId(REBALANCE_ASSOCIATION);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NoticePayloadFinalizeCommand>> captor =
                ArgumentCaptor.forClass((Class<List<NoticePayloadFinalizeCommand>>) (Class<?>) List.class);
        verify(noticeAuditDAO).finalizePayload(captor.capture());
        assertEquals(2, captor.getValue().size(), "完整两腿关联组必须同时冻结两条通知");
        verify(noticeAuditDAO).markSentByIds(List.of(31L, 32L));
        verify(noticeAuditDAO, never()).markSendFailedByIds(any(), any());
        verify(bot, times(1)).sendRequest(any(BotHttpReqParam.class), eq(String.class));
    }

    @Test
    @DisplayName("α换仓两腿关联组_Bot失败时两腿同时FAILED且不修改交易事实")
    void sendPendingNotices_alphaRebalanceBothLegs_bothMarkedFailed() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO sellLeg = rebalanceLeg(33L, 501L, REBALANCE_ASSOCIATION, "SELL", 1);
        TornStockNoticeAuditDO buyLeg = rebalanceLeg(34L, 502L, REBALANCE_ASSOCIATION, "BUY", 2);
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(sellLeg, buyLeg));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(501L), batch(502L)));
        when(noticeAuditDAO.selectByRebalanceAssociationId(REBALANCE_ASSOCIATION))
                .thenReturn(List.of(sellLeg, buyLeg));
        when(composeService.composeAndMergeNotices(any(), any())).thenReturn(List.of(
                new StockNoticeComposeService.ComposedMessage(List.of(33L, 34L), "α换仓合并消息")));
        when(noticeAuditDAO.finalizePayload(any())).thenReturn(2);
        when(noticeAuditDAO.markSendFailedByIds(eq(List.of(33L, 34L)), any())).thenReturn(2);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("{}"));

        service().sendPendingNotices();

        // 失败时两腿必须保持一致的失败终态,单腿结果不得被解释为完整换仓通知送达
        verify(noticeAuditDAO).markSendFailedByIds(eq(List.of(33L, 34L)), contains("HTTP状态非2xx"));
        verify(noticeAuditDAO, never()).markSentByIds(any());
        verify(virtualBatchDAO, never()).updateById(any(TornStockVirtualBatchDO.class));
    }

    @Test
    @DisplayName("α换仓一腿已冻结一腿未冻结_不拆成两条消息且只补齐未冻结腿")
    void sendPendingNotices_alphaRebalancePartiallyFrozen_completesOtherLegWithoutRefreeze() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO sellLeg = frozenRebalanceLeg(35L, 501L, REBALANCE_ASSOCIATION, "SELL", 1,
                "α换仓合并消息", "2026-09-05T11:00:00");
        TornStockNoticeAuditDO buyLeg = rebalanceLeg(36L, 502L, REBALANCE_ASSOCIATION, "BUY", 2);
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(sellLeg, buyLeg));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(501L), batch(502L)));
        when(noticeAuditDAO.selectByRebalanceAssociationId(REBALANCE_ASSOCIATION))
                .thenReturn(List.of(sellLeg, buyLeg));
        when(composeService.composeAndMergeNotices(any(), any())).thenReturn(List.of(
                new StockNoticeComposeService.ComposedMessage(List.of(35L, 36L), "α换仓合并消息")));
        when(noticeAuditDAO.finalizePayload(any())).thenReturn(1);
        when(noticeAuditDAO.markSentByIds(List.of(35L, 36L))).thenReturn(2);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        service().sendPendingNotices();

        // 只允许冻结未冻结腿,已冻结腿的messageText/frozenAt/payloadHash不得被覆盖
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NoticePayloadFinalizeCommand>> captor =
                ArgumentCaptor.forClass((Class<List<NoticePayloadFinalizeCommand>>) (Class<?>) List.class);
        verify(noticeAuditDAO).finalizePayload(captor.capture());
        List<NoticePayloadFinalizeCommand> commands = captor.getValue();
        assertEquals(1, commands.size(), "只允许补齐未冻结腿,禁止重复冻结已冻结腿");
        assertEquals(36L, commands.getFirst().noticeId(), "只有未冻结的BUY腿允许被冻结");
        assertTrue(commands.getFirst().payloadSnapshot().contains("\"messageText\":\"α换仓合并消息\""),
                "未冻结腿必须补齐到同一最终消息上下文");
        assertEquals(LocalDateTime.of(2026, 9, 5, 11, 0), commands.getFirst().attemptedAt(),
                "补齐腿必须沿用已冻结腿的冻结时间,保持同一最终消息上下文");
        verify(bot, times(1)).sendRequest(any(BotHttpReqParam.class), eq(String.class));
        verify(noticeAuditDAO).markSentByIds(List.of(35L, 36L));
    }

    @Test
    @DisplayName("α换仓两腿均已冻结_重启恢复复用同一冻结文本且不重新冻结")
    void sendPendingNotices_alphaRebalanceBothFrozen_reusesFrozenTextWithoutRefreeze() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO sellLeg = frozenRebalanceLeg(37L, 501L, REBALANCE_ASSOCIATION, "SELL", 1,
                "α换仓合并消息", "2026-09-05T11:00:00");
        TornStockNoticeAuditDO buyLeg = frozenRebalanceLeg(38L, 502L, REBALANCE_ASSOCIATION, "BUY", 2,
                "α换仓合并消息", "2026-09-05T11:00:00");
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(sellLeg, buyLeg));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(501L), batch(502L)));
        when(noticeAuditDAO.selectByRebalanceAssociationId(REBALANCE_ASSOCIATION))
                .thenReturn(List.of(sellLeg, buyLeg));
        when(noticeAuditDAO.markSentByIds(List.of(37L, 38L))).thenReturn(2);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        service().sendPendingNotices();

        ArgumentCaptor<BotHttpReqParam> paramCaptor = ArgumentCaptor.forClass(BotHttpReqParam.class);
        verify(bot, times(1)).sendRequest(paramCaptor.capture(), eq(String.class));
        assertTrue(String.valueOf(paramCaptor.getValue().body()).contains("α换仓合并消息"),
                "重启恢复必须复用关联组已冻结文本");
        verify(noticeAuditDAO, never()).finalizePayload(any());
        verify(composeService, never()).composeAndMergeNotices(any(), any());
        verify(noticeAuditDAO).markSentByIds(List.of(37L, 38L));
    }

    @Test
    @DisplayName("α换仓关联组缺腿_不调用Bot且剩余PENDING腿标记人工核验FAILED")
    void sendPendingNotices_alphaRebalanceMissingLeg_doesNotCallBot() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO sellLeg = rebalanceLeg(41L, 501L, REBALANCE_ASSOCIATION, "SELL", 1);
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(sellLeg));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(501L)));
        when(noticeAuditDAO.selectByRebalanceAssociationId(REBALANCE_ASSOCIATION)).thenReturn(List.of(sellLeg));
        when(noticeAuditDAO.markFailedByIds(any(), any())).thenReturn(1);

        service().sendPendingNotices();

        verify(noticeAuditDAO).markFailedByIds(eq(List.of(41L)), contains("关联组通知数不为2"));
        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
        verify(noticeAuditDAO, never()).markSentByIds(any());
        verify(noticeAuditDAO, never()).finalizePayload(any());
    }

    @Test
    @DisplayName("α换仓关联组重复同类腿_不调用Bot且两腿标记人工核验FAILED")
    void sendPendingNotices_alphaRebalanceDuplicateBuyLeg_doesNotCallBot() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO firstBuyLeg = rebalanceLeg(42L, 502L, REBALANCE_ASSOCIATION, "BUY", 2);
        TornStockNoticeAuditDO secondBuyLeg = rebalanceLeg(43L, 503L, REBALANCE_ASSOCIATION, "BUY", 2);
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(firstBuyLeg, secondBuyLeg));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(502L), batch(503L)));
        when(noticeAuditDAO.selectByRebalanceAssociationId(REBALANCE_ASSOCIATION))
                .thenReturn(List.of(firstBuyLeg, secondBuyLeg));
        when(noticeAuditDAO.markFailedByIds(any(), any())).thenReturn(2);

        service().sendPendingNotices();

        verify(noticeAuditDAO).markFailedByIds(eq(List.of(42L, 43L)), contains("重复BUY腿"));
        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
    }

    @Test
    @DisplayName("α换仓关联组换仓字段冲突_不调用Bot且PENDING腿标记人工核验FAILED")
    void sendPendingNotices_alphaRebalanceAssociationConflict_doesNotCallBot() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO sellLeg = rebalanceLeg(44L, 501L, REBALANCE_ASSOCIATION, "SELL", 1);
        TornStockNoticeAuditDO buyLeg = rebalanceLeg(45L, 502L, REBALANCE_ASSOCIATION, "BUY", 2);
        buyLeg.setPayloadSnapshot(rebalancePayload(502L, REBALANCE_ASSOCIATION, "BUY", 2, 12L, null, null));
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(sellLeg, buyLeg));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(501L), batch(502L)));
        when(noticeAuditDAO.selectByRebalanceAssociationId(REBALANCE_ASSOCIATION))
                .thenReturn(List.of(sellLeg, buyLeg));
        when(noticeAuditDAO.markFailedByIds(any(), any())).thenReturn(2);

        service().sendPendingNotices();

        verify(noticeAuditDAO).markFailedByIds(eq(List.of(44L, 45L)), contains("rebalanceDecisionId"));
        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
    }

    @Test
    @DisplayName("α换仓一腿已SENT一腿PENDING_不重复发送已SENT腿且剩余腿标记人工核验FAILED")
    void sendPendingNotices_alphaRebalancePartiallySent_doesNotResendSentLeg() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO sentSellLeg = rebalanceLeg(46L, 501L, REBALANCE_ASSOCIATION, "SELL", 1, "SENT");
        TornStockNoticeAuditDO pendingBuyLeg = rebalanceLeg(47L, 502L, REBALANCE_ASSOCIATION, "BUY", 2);
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(pendingBuyLeg));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(502L)));
        when(noticeAuditDAO.selectByRebalanceAssociationId(REBALANCE_ASSOCIATION))
                .thenReturn(List.of(sentSellLeg, pendingBuyLeg));
        when(noticeAuditDAO.markFailedByIds(any(), any())).thenReturn(1);

        service().sendPendingNotices();

        verify(noticeAuditDAO).markFailedByIds(eq(List.of(47L)), contains("部分完成"));
        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
        verify(noticeAuditDAO, never()).markSentByIds(any());
        verify(noticeAuditDAO, never()).finalizePayload(any());
    }

    @Test
    @DisplayName("α换仓通知缺少关联标识_不调用Bot且标记人工核验FAILED")
    void sendPendingNotices_alphaRebalanceNoticeWithoutAssociationId_doesNotCallBot() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO malformed = notice(48L, 501L);
        malformed.setNoticeType("ALPHA_REBALANCE");
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(malformed));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(501L)));
        when(noticeAuditDAO.markFailedByIds(any(), any())).thenReturn(1);

        service().sendPendingNotices();

        verify(noticeAuditDAO).markFailedByIds(eq(List.of(48L)), contains("缺少换仓关联标识"));
        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
        verify(composeService, never()).composeAndMergeNotices(any(), any());
    }

    @Test
    @DisplayName("FAILED终态通知不再被查询_再次调用发送入口不增加Bot调用")
    void sendPendingNotices_noPendingAfterFailure_doesNotCallBotAgain() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of());

        service().sendPendingNotices();

        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
        verify(noticeAuditDAO, never()).finalizePayload(any());
        verify(noticeAuditDAO, never()).markSentByIds(any());
        verify(noticeAuditDAO, never()).markSendFailedByIds(any(), any());
    }

    private TornStockNoticeAuditDO notice(Long id, Long batchId) {
        TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
        notice.setId(id);
        notice.setBatchId(batchId);
        notice.setPayloadSnapshot("{\"noticeType\":\"SELL\",\"batchId\":" + batchId
                + ",\"batchNo\":\"B" + batchId + "\",\"stocksId\":1001}");
        return notice;
    }

    /**
     * 构建PENDING状态的α换仓腿通知。
     *
     * @param id            通知ID
     * @param batchId       关联批次ID
     * @param associationId 换仓关联标识
     * @param leg           腿标识(SELL/BUY)
     * @param legOrder      腿顺序
     * @return 携带完整换仓关联事实的通知审计DO
     */
    private TornStockNoticeAuditDO rebalanceLeg(Long id, Long batchId, String associationId,
                                                String leg, int legOrder) {
        return rebalanceLeg(id, batchId, associationId, leg, legOrder, "PENDING");
    }

    /**
     * 构建指定发送状态的α换仓腿通知。
     *
     * @param id            通知ID
     * @param batchId       关联批次ID
     * @param associationId 换仓关联标识
     * @param leg           腿标识(SELL/BUY)
     * @param legOrder      腿顺序
     * @param sendStatus    发送状态
     * @return 通知审计DO
     */
    private TornStockNoticeAuditDO rebalanceLeg(Long id, Long batchId, String associationId,
                                                String leg, int legOrder, String sendStatus) {
        TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
        notice.setId(id);
        notice.setBatchId(batchId);
        notice.setNoticeType("ALPHA_REBALANCE");
        notice.setSendStatus(sendStatus);
        notice.setPayloadSnapshot(rebalancePayload(batchId, associationId, leg, legOrder, 11L, null, null));
        return notice;
    }

    /**
     * 构建已冻结最终文本的α换仓腿通知。
     *
     * @param id            通知ID
     * @param batchId       关联批次ID
     * @param associationId 换仓关联标识
     * @param leg           腿标识(SELL/BUY)
     * @param legOrder      腿顺序
     * @param messageText   已冻结最终文本
     * @param frozenAt      已冻结时间
     * @return 已冻结通知审计DO
     */
    private TornStockNoticeAuditDO frozenRebalanceLeg(Long id, Long batchId, String associationId,
                                                      String leg, int legOrder, String messageText,
                                                      String frozenAt) {
        TornStockNoticeAuditDO notice = rebalanceLeg(id, batchId, associationId, leg, legOrder);
        notice.setPayloadSnapshot(rebalancePayload(batchId, associationId, leg, legOrder, 11L,
                messageText, frozenAt));
        return notice;
    }

    /**
     * 构建α换仓通知payload,原仓批次固定501、新仓批次固定502。
     *
     * @param batchId       关联批次ID
     * @param associationId 换仓关联标识
     * @param leg           腿标识
     * @param legOrder      腿顺序
     * @param decisionId    换仓决策ID
     * @param messageText   已冻结最终文本;未冻结时为null
     * @param frozenAt      已冻结时间;未冻结时为null
     * @return 通知payload JSON
     */
    private String rebalancePayload(Long batchId, String associationId, String leg, int legOrder,
                                    Long decisionId, String messageText, String frozenAt) {
        StringBuilder json = new StringBuilder("{\"noticeType\":\"ALPHA_REBALANCE\",\"batchId\":")
                .append(batchId)
                .append(",\"rebalanceDecisionId\":").append(decisionId)
                .append(",\"rebalanceAssociationId\":\"").append(associationId).append("\"")
                .append(",\"originalBatchId\":501,\"replacementBatchId\":502")
                .append(",\"rebalanceLeg\":\"").append(leg).append("\",\"legOrder\":").append(legOrder);
        if (messageText != null) {
            json.append(",\"messageText\":\"").append(messageText).append("\"");
        }
        if (frozenAt != null) {
            json.append(",\"frozenAt\":\"").append(frozenAt).append("\"");
        }
        return json.append("}").toString();
    }

    private TornStockVirtualBatchDO batch(Long id) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(id);
        batch.setStocksId(1001);
        batch.setLedgerType("FORMAL");
        return batch;
    }

    /**
     * 组装被测发送服务及其协作对象,协作对象共用同一批Mock以便统一校验真实调用。
     *
     * @return 股票通知发送服务
     */
    private StockNoticeSendService service() {
        StockNoticeBotSender botSender = new StockNoticeBotSender(bot, projectProperty);
        StockNoticeSendRecorder sendRecorder = new StockNoticeSendRecorder(noticeAuditDAO);
        StockRebalanceNoticeSender rebalanceSender = new StockRebalanceNoticeSender(
                noticeAuditDAO, composeService, sendRecorder, botSender);
        return new StockNoticeSendService(sysSettingManager, noticeAuditDAO, virtualBatchDAO, composeService,
                botSender, sendRecorder, rebalanceSender);
    }
}
