package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 股票通知发送服务测试,覆盖NapCat响应判定、开关门禁、数据库级领取、冻结复用、
 * 失败自动重发与α换仓关联组闭包。
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
    private TornStockNoticeAuditDAO noticeAuditDao;

    @Mock
    private TornStockVirtualBatchDAO virtualBatchDao;

    @Mock
    private StockNoticeComposeService composeService;

    /**
     * 默认领取成功:领取行数等于请求通知数,单个用例可覆盖为领取失败。
     */
    @BeforeEach
    void stubClaimSuccess() {
        lenient().when(noticeAuditDao.claimByIds(anyList(), anyString()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
        lenient().when(noticeAuditDao.claimByRebalanceAssociationId(anyString(), anyString())).thenReturn(2);
        // 终态回写默认按真实数据库行为返回完整行数,避免掩盖"回写行数不足"的ERROR口径
        lenient().when(noticeAuditDao.markSentByIds(anyList(), anyString()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
        lenient().when(noticeAuditDao.markSendFailedByIds(anyList(), anyString(), anyString()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
        lenient().when(noticeAuditDao.markFinalByIds(anyList(), anyString()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
    }

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

        verify(noticeAuditDao, never()).selectSendableNotices();
        verify(virtualBatchDao, never()).listByIds(any());
    }

    @Test
    @DisplayName("发送入口先恢复领取超时通知_再查询可发送集合")
    void sendPendingNotices_recoversStaleClaimsBeforeQuery() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        when(noticeAuditDao.recoverStaleClaims(any(LocalDateTime.class))).thenReturn(1);
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of());

        service().sendPendingNotices();

        verify(noticeAuditDao).recoverStaleClaims(any(LocalDateTime.class));
        verify(noticeAuditDao).selectSendableNotices();
        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
    }

    @Test
    @DisplayName("通知缺少关联批次_标记人工核验终态且不发送")
    void sendPendingNotices_missingBatch_marksFinalWithoutSending() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO notice = notice(10L, null);
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(notice));

        service().sendPendingNotices();

        verify(noticeAuditDao).markFinalByIds(List.of(10L), "关联虚拟交易批次不存在");
        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
        verify(composeService, never()).composeAndMergeNotices(any(), any());
    }

    @Test
    @DisplayName("通知发送HTTP失败_失败原因必须记录且进入可重发状态")
    void sendPendingNotices_httpFailure_recordsActualFailureReason() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO notice = notice(11L, 21L);
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(notice));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(21L)));
        when(composeService.composeAndMergeNotices(any(), any()))
                .thenReturn(List.of(new StockNoticeComposeService.ComposedMessage(List.of(11L), "测试通知")));
        when(noticeAuditDao.finalizePayload(any())).thenReturn(1);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("{}"));

        service().sendPendingNotices();

        verify(noticeAuditDao).markSendFailedByIds(eq(List.of(11L)), anyString(), contains("HTTP状态非2xx"));
    }

    @Test
    @DisplayName("有效和缺失批次混合_缺失通知终态失败且有效通知继续发送")
    void sendPendingNotices_mixedBatchReferences_processesValidNoticeAndFinalizesMissingNotice() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO valid = notice(12L, 22L);
        TornStockNoticeAuditDO missing = notice(13L, 23L);
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(valid, missing));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(22L)));
        when(composeService.composeAndMergeNotices(any(), any()))
                .thenReturn(List.of(new StockNoticeComposeService.ComposedMessage(List.of(12L), "测试通知")));
        when(noticeAuditDao.finalizePayload(any())).thenReturn(1);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        service().sendPendingNotices();

        verify(noticeAuditDao).markFinalByIds(List.of(13L), "关联虚拟交易批次不存在");
        verify(noticeAuditDao).markSentByIds(eq(List.of(12L)), anyString());
        verify(bot).sendRequest(any(BotHttpReqParam.class), eq(String.class));
    }

    @Test
    @DisplayName("通知发送成功_回写必须绑定本次领取标识")
    void sendPendingNotices_successfulResponse_marksNoticesSentAndBindsClaimToken() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO notice = notice(14L, 24L);
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(notice));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(24L)));
        when(composeService.composeAndMergeNotices(any(), any()))
                .thenReturn(List.of(new StockNoticeComposeService.ComposedMessage(List.of(14L), "测试通知")));
        when(noticeAuditDao.finalizePayload(any())).thenReturn(1);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        service().sendPendingNotices();

        ArgumentCaptor<String> claimCaptor = ArgumentCaptor.forClass(String.class);
        verify(noticeAuditDao).claimByIds(eq(List.of(14L)), claimCaptor.capture());
        ArgumentCaptor<String> sentCaptor = ArgumentCaptor.forClass(String.class);
        verify(noticeAuditDao).markSentByIds(eq(List.of(14L)), sentCaptor.capture());
        assertEquals(claimCaptor.getValue(), sentCaptor.getValue(), "终态回写必须绑定同一领取标识");
        verify(noticeAuditDao, never()).markSendFailedByIds(any(), any(), any());
    }

    @Test
    @DisplayName("冻结命令_逐条保留业务字段且hash等于最终payload哈希")
    void sendPendingNotices_capturesFinalizeCommandPreservesFieldsAndHash() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO notice = notice(15L, 25L);
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(notice));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(25L)));
        when(composeService.composeAndMergeNotices(any(), any()))
                .thenReturn(List.of(new StockNoticeComposeService.ComposedMessage(List.of(15L), "灾难关闭文本")));
        when(noticeAuditDao.finalizePayload(any())).thenReturn(1);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        service().sendPendingNotices();

        List<NoticePayloadFinalizeCommand> commands = capturedFinalizeCommands();
        assertEquals(1, commands.size(), "逐条冻结命令数必须等于通知数");
        NoticePayloadFinalizeCommand command = commands.getFirst();
        assertEquals(15L, command.noticeId());
        assertNotNull(command.claimToken(), "冻结命令必须携带本次领取标识");
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
    @DisplayName("冻结行数不符_禁止Bot发送且进入可重发状态")
    void sendPendingNotices_finalizePartialUpdate_stopsBotSending() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO notice = notice(16L, 26L);
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(notice));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(26L)));
        when(composeService.composeAndMergeNotices(any(), any()))
                .thenReturn(List.of(new StockNoticeComposeService.ComposedMessage(List.of(16L), "文本")));
        when(noticeAuditDao.finalizePayload(any())).thenReturn(0);

        service().sendPendingNotices();

        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
        verify(noticeAuditDao, never()).markSentByIds(any(), any());
        verify(noticeAuditDao).markSendFailedByIds(eq(List.of(16L)), anyString(), contains("冻结行数不符"));
    }

    @Test
    @DisplayName("领取失败_禁止Bot发送并释放本次领取")
    void sendPendingNotices_claimNotAcquired_doesNotCallBotAndReleasesClaim() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO notice = notice(19L, 29L);
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(notice));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(29L)));
        when(composeService.composeAndMergeNotices(any(), any()))
                .thenReturn(List.of(new StockNoticeComposeService.ComposedMessage(List.of(19L), "测试通知")));
        when(noticeAuditDao.claimByIds(eq(List.of(19L)), anyString())).thenReturn(0);

        service().sendPendingNotices();

        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
        verify(noticeAuditDao, never()).finalizePayload(any());
        verify(noticeAuditDao, never()).markSentByIds(any(), any());
        verify(noticeAuditDao).releaseClaim(anyString());
    }

    @Test
    @DisplayName("已冻结通知_重启后复用冻结文本且不重复组合不重复冻结")
    void sendPendingNotices_frozenPendingNotice_reusesFrozenTextWithoutRecompose() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO frozen = frozenNotice(17L, 27L, "已冻结文本");
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(frozen));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(27L)));
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
        verify(noticeAuditDao, never()).finalizePayload(any());
        verify(noticeAuditDao).markSentByIds(eq(List.of(17L)), anyString());
    }

    @Test
    @DisplayName("已冻结通知_Bot失败不再次冻结payload且进入可重发状态")
    void sendPendingNotices_frozenPendingNotice_botFailure_marksRetryableWithoutRefinalize() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO frozen = frozenNotice(18L, 28L, "已冻结文本");
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(frozen));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(28L)));
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("{}"));

        service().sendPendingNotices();

        verify(noticeAuditDao, never()).finalizePayload(any());
        verify(noticeAuditDao).markSendFailedByIds(eq(List.of(18L)), anyString(), contains("HTTP状态非2xx"));
        verify(noticeAuditDao, never()).markSentByIds(any(), any());
    }

    @Test
    @DisplayName("FAILED_RETRYABLE通知_自动重发复用首次冻结文本")
    void sendPendingNotices_retryableFrozenNotice_autoResentWithSameFrozenText() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO retryable = frozenNotice(20L, 30L, "首次冻结文本");
        retryable.setSendStatus("FAILED_RETRYABLE");
        retryable.setSendAttemptCount(1);
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(retryable));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(30L)));
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        service().sendPendingNotices();

        verify(noticeAuditDao).claimByIds(eq(List.of(20L)), anyString());
        ArgumentCaptor<BotHttpReqParam> paramCaptor = ArgumentCaptor.forClass(BotHttpReqParam.class);
        verify(bot).sendRequest(paramCaptor.capture(), eq(String.class));
        assertTrue(String.valueOf(paramCaptor.getValue().body()).contains("首次冻结文本"),
                "自动重发必须复用首次冻结载荷,不得重新组合正文");
        verify(noticeAuditDao, never()).finalizePayload(any());
        verify(noticeAuditDao).markSentByIds(eq(List.of(20L)), anyString());
    }

    @Test
    @DisplayName("每日摘要无关联批次_按创建时正文发送且不因缺批次终态失败")
    void sendPendingNotices_dailySummaryWithoutBatch_sentFromPayloadMessageText() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO summary = new TornStockNoticeAuditDO();
        summary.setId(21L);
        summary.setBatchId(null);
        summary.setNoticeType("DAILY_SUMMARY");
        summary.setSendStatus("PENDING");
        summary.setPayloadSnapshot("{\"noticeType\":\"DAILY_SUMMARY\",\"summaryDate\":\"2026-09-05\","
                + "\"groupId\":10001,\"messageText\":\"每日摘要正文\"}");
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(summary));
        when(noticeAuditDao.finalizePayload(any())).thenReturn(1);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        service().sendPendingNotices();

        verify(noticeAuditDao, never()).markFinalByIds(any(), any());
        ArgumentCaptor<BotHttpReqParam> paramCaptor = ArgumentCaptor.forClass(BotHttpReqParam.class);
        verify(bot).sendRequest(paramCaptor.capture(), eq(String.class));
        assertTrue(String.valueOf(paramCaptor.getValue().body()).contains("每日摘要正文"),
                "无批次通知必须按创建时正文发送");
        verify(noticeAuditDao).markSentByIds(eq(List.of(21L)), anyString());
    }

    @Test
    @DisplayName("α换仓两腿关联组_Bot成功时按完整关联组领取、两腿同时SENT且只调用一次Bot")
    void sendPendingNotices_alphaRebalanceBothLegs_bothMarkedSent() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO sellLeg = rebalanceLeg(31L, 501L, REBALANCE_ASSOCIATION, "SELL", 1);
        TornStockNoticeAuditDO buyLeg = rebalanceLeg(32L, 502L, REBALANCE_ASSOCIATION, "BUY", 2);
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(sellLeg, buyLeg));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(501L), batch(502L)));
        when(noticeAuditDao.selectByRebalanceAssociationId(REBALANCE_ASSOCIATION))
                .thenReturn(List.of(sellLeg, buyLeg));
        when(composeService.composeAndMergeNotices(any(), any())).thenReturn(List.of(
                new StockNoticeComposeService.ComposedMessage(List.of(31L, 32L), "α换仓合并消息")));
        when(noticeAuditDao.finalizePayload(any())).thenReturn(2);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        service().sendPendingNotices();

        // 发送前必须按关联标识读取关联组完整通知集合,不得只依赖内存子集
        verify(noticeAuditDao).selectByRebalanceAssociationId(REBALANCE_ASSOCIATION);
        // 关联组必须以关联组为单位领取,不能只领取一条腿
        verify(noticeAuditDao).claimByRebalanceAssociationId(eq(REBALANCE_ASSOCIATION), anyString());
        assertEquals(2, capturedFinalizeCommands().size(), "完整两腿关联组必须同时冻结两条通知");
        verify(noticeAuditDao).markSentByIds(eq(List.of(31L, 32L)), anyString());
        verify(noticeAuditDao, never()).markSendFailedByIds(any(), any(), any());
        verify(bot, times(1)).sendRequest(any(BotHttpReqParam.class), eq(String.class));
    }

    @Test
    @DisplayName("α换仓两腿关联组_Bot失败时两腿同时进入失败终态且不修改交易事实")
    void sendPendingNotices_alphaRebalanceBothLegs_bothMarkedFailed() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO sellLeg = rebalanceLeg(33L, 501L, REBALANCE_ASSOCIATION, "SELL", 1);
        TornStockNoticeAuditDO buyLeg = rebalanceLeg(34L, 502L, REBALANCE_ASSOCIATION, "BUY", 2);
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(sellLeg, buyLeg));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(501L), batch(502L)));
        when(noticeAuditDao.selectByRebalanceAssociationId(REBALANCE_ASSOCIATION))
                .thenReturn(List.of(sellLeg, buyLeg));
        when(composeService.composeAndMergeNotices(any(), any())).thenReturn(List.of(
                new StockNoticeComposeService.ComposedMessage(List.of(33L, 34L), "α换仓合并消息")));
        when(noticeAuditDao.finalizePayload(any())).thenReturn(2);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("{}"));

        service().sendPendingNotices();

        // 失败时两腿必须保持一致的失败状态,单腿结果不得被解释为完整换仓通知送达
        verify(noticeAuditDao).markSendFailedByIds(eq(List.of(33L, 34L)), anyString(), contains("HTTP状态非2xx"));
        verify(noticeAuditDao, never()).markSentByIds(any(), any());
        verify(virtualBatchDao, never()).updateById(any(TornStockVirtualBatchDO.class));
    }

    @Test
    @DisplayName("α换仓两腿均为可重发失败_以关联组为单位自动重发且复用冻结文本")
    void sendPendingNotices_alphaRebalanceRetryableBothLegs_resentAsGroup() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO sellLeg = frozenRebalanceLeg(25L, 501L, REBALANCE_ASSOCIATION, "SELL", 1,
                "α换仓合并消息", "2026-09-05T11:00:00", "FAILED_RETRYABLE");
        TornStockNoticeAuditDO buyLeg = frozenRebalanceLeg(26L, 502L, REBALANCE_ASSOCIATION, "BUY", 2,
                "α换仓合并消息", "2026-09-05T11:00:00", "FAILED_RETRYABLE");
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(sellLeg, buyLeg));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(501L), batch(502L)));
        when(noticeAuditDao.selectByRebalanceAssociationId(REBALANCE_ASSOCIATION))
                .thenReturn(List.of(sellLeg, buyLeg));
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        service().sendPendingNotices();

        verify(noticeAuditDao).claimByRebalanceAssociationId(eq(REBALANCE_ASSOCIATION), anyString());
        ArgumentCaptor<BotHttpReqParam> paramCaptor = ArgumentCaptor.forClass(BotHttpReqParam.class);
        verify(bot, times(1)).sendRequest(paramCaptor.capture(), eq(String.class));
        assertTrue(String.valueOf(paramCaptor.getValue().body()).contains("α换仓合并消息"),
                "自动重发必须复用首次冻结文本");
        verify(noticeAuditDao, never()).finalizePayload(any());
        verify(noticeAuditDao).markSentByIds(eq(List.of(25L, 26L)), anyString());
    }

    @Test
    @DisplayName("α换仓一腿已冻结一腿未冻结_不拆成两条消息且只补齐未冻结腿")
    void sendPendingNotices_alphaRebalancePartiallyFrozen_completesOtherLegWithoutRefreeze() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO sellLeg = frozenRebalanceLeg(35L, 501L, REBALANCE_ASSOCIATION, "SELL", 1,
                "α换仓合并消息", "2026-09-05T11:00:00", "PENDING");
        TornStockNoticeAuditDO buyLeg = rebalanceLeg(36L, 502L, REBALANCE_ASSOCIATION, "BUY", 2);
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(sellLeg, buyLeg));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(501L), batch(502L)));
        when(noticeAuditDao.selectByRebalanceAssociationId(REBALANCE_ASSOCIATION))
                .thenReturn(List.of(sellLeg, buyLeg));
        when(composeService.composeAndMergeNotices(any(), any())).thenReturn(List.of(
                new StockNoticeComposeService.ComposedMessage(List.of(35L, 36L), "α换仓合并消息")));
        when(noticeAuditDao.finalizePayload(any())).thenReturn(1);
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        service().sendPendingNotices();

        // 只允许冻结未冻结腿,已冻结腿的messageText/frozenAt/payloadHash不得被覆盖
        List<NoticePayloadFinalizeCommand> commands = capturedFinalizeCommands();
        assertEquals(1, commands.size(), "只允许补齐未冻结腿,禁止重复冻结已冻结腿");
        assertEquals(36L, commands.getFirst().noticeId(), "只有未冻结的BUY腿允许被冻结");
        assertTrue(commands.getFirst().payloadSnapshot().contains("\"messageText\":\"α换仓合并消息\""),
                "未冻结腿必须补齐到同一最终消息上下文");
        assertTrue(commands.getFirst().payloadSnapshot().contains("\"frozenAt\":\"2026-09-05T11:00\""),
                "补齐腿必须沿用已冻结腿的冻结时间,保持同一最终消息上下文");
        verify(bot, times(1)).sendRequest(any(BotHttpReqParam.class), eq(String.class));
        verify(noticeAuditDao).markSentByIds(eq(List.of(35L, 36L)), anyString());
    }

    @Test
    @DisplayName("α换仓两腿均已冻结_重启恢复复用同一冻结文本且不重新冻结")
    void sendPendingNotices_alphaRebalanceBothFrozen_reusesFrozenTextWithoutRefreeze() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO sellLeg = frozenRebalanceLeg(37L, 501L, REBALANCE_ASSOCIATION, "SELL", 1,
                "α换仓合并消息", "2026-09-05T11:00:00", "PENDING");
        TornStockNoticeAuditDO buyLeg = frozenRebalanceLeg(38L, 502L, REBALANCE_ASSOCIATION, "BUY", 2,
                "α换仓合并消息", "2026-09-05T11:00:00", "PENDING");
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(sellLeg, buyLeg));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(501L), batch(502L)));
        when(noticeAuditDao.selectByRebalanceAssociationId(REBALANCE_ASSOCIATION))
                .thenReturn(List.of(sellLeg, buyLeg));
        when(projectProperty.getVipGroupId()).thenReturn(10001L);
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\",\"retcode\":0}"));

        service().sendPendingNotices();

        ArgumentCaptor<BotHttpReqParam> paramCaptor = ArgumentCaptor.forClass(BotHttpReqParam.class);
        verify(bot, times(1)).sendRequest(paramCaptor.capture(), eq(String.class));
        assertTrue(String.valueOf(paramCaptor.getValue().body()).contains("α换仓合并消息"),
                "重启恢复必须复用关联组已冻结文本");
        verify(noticeAuditDao, never()).finalizePayload(any());
        verify(composeService, never()).composeAndMergeNotices(any(), any());
        verify(noticeAuditDao).markSentByIds(eq(List.of(37L, 38L)), anyString());
    }

    @Test
    @DisplayName("α换仓关联组缺腿_不调用Bot且剩余腿标记人工核验终态")
    void sendPendingNotices_alphaRebalanceMissingLeg_doesNotCallBot() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO sellLeg = rebalanceLeg(41L, 501L, REBALANCE_ASSOCIATION, "SELL", 1);
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(sellLeg));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(501L)));
        when(noticeAuditDao.selectByRebalanceAssociationId(REBALANCE_ASSOCIATION)).thenReturn(List.of(sellLeg));

        service().sendPendingNotices();

        verify(noticeAuditDao).markFinalByIds(eq(List.of(41L)), contains("关联组通知数不为2"));
        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
        verify(noticeAuditDao, never()).markSentByIds(any(), any());
        verify(noticeAuditDao, never()).finalizePayload(any());
        verify(noticeAuditDao, never()).claimByRebalanceAssociationId(anyString(), anyString());
    }

    @Test
    @DisplayName("α换仓关联组重复同类腿_不调用Bot且两腿标记人工核验终态")
    void sendPendingNotices_alphaRebalanceDuplicateBuyLeg_doesNotCallBot() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO firstBuyLeg = rebalanceLeg(42L, 502L, REBALANCE_ASSOCIATION, "BUY", 2);
        TornStockNoticeAuditDO secondBuyLeg = rebalanceLeg(43L, 503L, REBALANCE_ASSOCIATION, "BUY", 2);
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(firstBuyLeg, secondBuyLeg));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(502L), batch(503L)));
        when(noticeAuditDao.selectByRebalanceAssociationId(REBALANCE_ASSOCIATION))
                .thenReturn(List.of(firstBuyLeg, secondBuyLeg));

        service().sendPendingNotices();

        verify(noticeAuditDao).markFinalByIds(eq(List.of(42L, 43L)), contains("重复BUY腿"));
        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
    }

    @Test
    @DisplayName("α换仓关联组换仓字段冲突_不调用Bot且腿标记人工核验终态")
    void sendPendingNotices_alphaRebalanceAssociationConflict_doesNotCallBot() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO sellLeg = rebalanceLeg(44L, 501L, REBALANCE_ASSOCIATION, "SELL", 1);
        TornStockNoticeAuditDO buyLeg = rebalanceLeg(45L, 502L, REBALANCE_ASSOCIATION, "BUY", 2);
        buyLeg.setPayloadSnapshot(rebalancePayload(502L, REBALANCE_ASSOCIATION, "BUY", 2, 12L, null, null));
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(sellLeg, buyLeg));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(501L), batch(502L)));
        when(noticeAuditDao.selectByRebalanceAssociationId(REBALANCE_ASSOCIATION))
                .thenReturn(List.of(sellLeg, buyLeg));

        service().sendPendingNotices();

        verify(noticeAuditDao).markFinalByIds(eq(List.of(44L, 45L)), contains("rebalanceDecisionId"));
        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
    }

    @Test
    @DisplayName("α换仓一腿已SENT一腿可重发_不重复发送已SENT腿且状态不一致fail-closed")
    void sendPendingNotices_alphaRebalanceSentLegNotResent_retryableLegGoesFinal() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO sentSellLeg = rebalanceLeg(46L, 501L, REBALANCE_ASSOCIATION, "SELL", 1, "SENT");
        TornStockNoticeAuditDO retryableBuyLeg =
                rebalanceLeg(47L, 502L, REBALANCE_ASSOCIATION, "BUY", 2, "FAILED_RETRYABLE");
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(retryableBuyLeg));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(502L)));
        when(noticeAuditDao.selectByRebalanceAssociationId(REBALANCE_ASSOCIATION))
                .thenReturn(List.of(sentSellLeg, retryableBuyLeg));

        service().sendPendingNotices();

        verify(noticeAuditDao).markFinalByIds(eq(List.of(47L)), contains("状态不一致"));
        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
        verify(noticeAuditDao, never()).markSentByIds(any(), any());
        verify(noticeAuditDao, never()).finalizePayload(any());
        verify(noticeAuditDao, never()).claimByRebalanceAssociationId(anyString(), anyString());
    }

    @Test
    @DisplayName("α换仓通知缺少关联标识_不调用Bot且标记人工核验终态")
    void sendPendingNotices_alphaRebalanceNoticeWithoutAssociationId_doesNotCallBot() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        TornStockNoticeAuditDO malformed = notice(48L, 501L);
        malformed.setNoticeType("ALPHA_REBALANCE");
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of(malformed));
        when(virtualBatchDao.listByIds(any())).thenReturn(List.of(batch(501L)));

        service().sendPendingNotices();

        verify(noticeAuditDao).markFinalByIds(eq(List.of(48L)), contains("缺少换仓关联标识"));
        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
        verify(composeService, never()).composeAndMergeNotices(any(), any());
    }

    @Test
    @DisplayName("无可发送通知_不调用Bot不冻结不回写")
    void sendPendingNotices_noSendableNotices_doesNotCallBot() {
        when(sysSettingManager.getSettingValue(any())).thenReturn("true");
        when(noticeAuditDao.selectSendableNotices()).thenReturn(List.of());

        service().sendPendingNotices();

        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
        verify(noticeAuditDao, never()).finalizePayload(any());
        verify(noticeAuditDao, never()).markSentByIds(any(), any());
        verify(noticeAuditDao, never()).markSendFailedByIds(any(), any(), any());
    }

    /**
     * 捕获发送前的逐条冻结命令。
     *
     * @return 冻结命令列表
     */
    @SuppressWarnings("unchecked")
    private List<NoticePayloadFinalizeCommand> capturedFinalizeCommands() {
        ArgumentCaptor<List<NoticePayloadFinalizeCommand>> captor =
                ArgumentCaptor.forClass((Class<List<NoticePayloadFinalizeCommand>>) (Class<?>) List.class);
        verify(noticeAuditDao).finalizePayload(captor.capture());
        return captor.getValue();
    }

    private TornStockNoticeAuditDO notice(Long id, Long batchId) {
        TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
        notice.setId(id);
        notice.setBatchId(batchId);
        notice.setNoticeType("SELL");
        notice.setSendStatus("PENDING");
        notice.setPayloadSnapshot("{\"noticeType\":\"SELL\",\"batchId\":" + batchId
                + ",\"batchNo\":\"B" + batchId + "\",\"stocksId\":1001}");
        return notice;
    }

    /**
     * 构建已冻结最终文本的普通通知。
     *
     * @param id          通知ID
     * @param batchId     关联批次ID
     * @param messageText 已冻结文本
     * @return 已冻结通知审计DO
     */
    private TornStockNoticeAuditDO frozenNotice(Long id, Long batchId, String messageText) {
        TornStockNoticeAuditDO notice = notice(id, batchId);
        notice.setPayloadSnapshot("{\"noticeType\":\"SELL\",\"batchId\":" + batchId
                + ",\"batchNo\":\"B" + batchId + "\",\"stocksId\":1001"
                + ",\"messageText\":\"" + messageText + "\""
                + ",\"frozenAt\":\"2026-08-02T10:00:00\"}");
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
     * @param sendStatus    发送状态
     * @return 已冻结通知审计DO
     */
    private TornStockNoticeAuditDO frozenRebalanceLeg(Long id, Long batchId, String associationId,
                                                      String leg, int legOrder, String messageText,
                                                      String frozenAt, String sendStatus) {
        TornStockNoticeAuditDO notice = rebalanceLeg(id, batchId, associationId, leg, legOrder, sendStatus);
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
        StockNoticeSendRecorder sendRecorder = new StockNoticeSendRecorder(noticeAuditDao);
        StockRebalanceNoticeSender rebalanceSender = new StockRebalanceNoticeSender(
                noticeAuditDao, composeService, sendRecorder, botSender);
        return new StockNoticeSendService(sysSettingManager, noticeAuditDao, virtualBatchDao, composeService,
                botSender, sendRecorder, rebalanceSender);
    }
}
