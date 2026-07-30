package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
        notice.setId(11L);
        notice.setBatchId(21L);
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(notice));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(21L)));
        when(composeService.composeAndMergeNotices(any(), any()))
                .thenReturn(List.of(new StockNoticeComposeService.ComposedMessage(List.of(11L), "测试通知")));
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
        TornStockNoticeAuditDO valid = new TornStockNoticeAuditDO();
        valid.setId(12L);
        valid.setBatchId(22L);
        TornStockNoticeAuditDO missing = new TornStockNoticeAuditDO();
        missing.setId(13L);
        missing.setBatchId(23L);
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(valid, missing));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(22L)));
        when(composeService.composeAndMergeNotices(any(), any()))
                .thenReturn(List.of(new StockNoticeComposeService.ComposedMessage(List.of(12L), "测试通知")));
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
        TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
        notice.setId(14L);
        notice.setBatchId(24L);
        when(noticeAuditDAO.selectPendingNotices()).thenReturn(List.of(notice));
        when(virtualBatchDAO.listByIds(any())).thenReturn(List.of(batch(24L)));
        when(composeService.composeAndMergeNotices(any(), any()))
                .thenReturn(List.of(new StockNoticeComposeService.ComposedMessage(List.of(14L), "测试通知")));
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        service().sendPendingNotices();

        verify(noticeAuditDAO).markSentByIds(List.of(14L));
        verify(noticeAuditDAO, never()).markSendFailedByIds(any(), any());
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
