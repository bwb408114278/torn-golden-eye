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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 股票通知发送服务测试,覆盖NapCat响应判定、开关门禁和无关联批次处理。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.28
 */
@DisplayName("股票通知发送服务测试")
@ExtendWith(MockitoExtension.class)
class StockNoticeSendServiceTest {

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
    @DisplayName("已冻结PENDING通知_重启后复用冻结文本且不重复组合")
    void sendPendingNotices_frozenPendingNotice_reusesFrozenTextWithoutRecompose() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO frozen = new TornStockNoticeAuditDO();
        frozen.setId(17L);
        frozen.setBatchId(27L);
        frozen.setPayloadSnapshot("{\"noticeType\":\"SELL\",\"batchId\":27,\"batchNo\":\"B27\",\"stocksId\":1001,"
                + "\"messageText\":\"已冻结文本\",\"frozenAt\":\"2026-08-02T10:00:00\"}");
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(frozen));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(27L)));
        when(noticeAuditDAO.finalizePayload(any())).thenReturn(1);
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
        verify(noticeAuditDAO).markSentByIds(List.of(17L));
    }

    private TornStockNoticeAuditDO notice(Long id, Long batchId) {
        TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
        notice.setId(id);
        notice.setBatchId(batchId);
        notice.setPayloadSnapshot("{\"noticeType\":\"SELL\",\"batchId\":" + batchId
                + ",\"batchNo\":\"B" + batchId + "\",\"stocksId\":1001}");
        return notice;
    }

    private TornStockVirtualBatchDO batch(Long id) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(id);
        batch.setStocksId(1001);
        batch.setLedgerType("FORMAL");
        return batch;
    }

    private StockNoticeSendService service() {
        return new StockNoticeSendService(
                bot, projectProperty, sysSettingManager, noticeAuditDAO, virtualBatchDAO, composeService);
    }
}
