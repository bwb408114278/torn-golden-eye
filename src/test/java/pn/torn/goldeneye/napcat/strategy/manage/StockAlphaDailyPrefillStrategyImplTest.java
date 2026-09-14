package pn.torn.goldeneye.napcat.strategy.manage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgReqParam;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.market.StockAlphaDailyCloseService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.rebuild.StockHistoricalMaintenanceGate;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 预填股票α日线超管指令策略测试。
 * <p>
 * 只覆盖结束日期语义与异步受理路径:无参数使用最近已结束自然日、当前自然日与未来日期拒绝、
 * 无效日期不占用互斥门、维护门占用不受理、执行器拒绝必须释放互斥门、无日期可构建时回执区分。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.08
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("预填股票α日线指令策略测试")
class StockAlphaDailyPrefillStrategyImplTest {
    /**
     * 指令发起群号。
     */
    private static final long GROUP_ID = 12345L;
    /**
     * 预填结束日期(已结束自然日)。
     */
    private static final LocalDate END_DATE = LocalDate.of(2026, 9, 5);

    @Mock
    private StockAlphaDailyCloseService alphaDailyCloseService;
    @Mock
    private StockMarketClock marketClock;
    @Mock
    private ThreadPoolTaskExecutor stockBackfillExecutor;
    @Mock
    private StockHistoricalMaintenanceGate maintenanceGate;
    @Mock
    private Bot bot;

    @InjectMocks
    private StockAlphaDailyPrefillStrategyImpl strategy;

    @Test
    @DisplayName("策略声明_指令与超管权限正确")
    void strategyDeclaration_correct() {
        assertEquals(BotCommands.ALPHA_STOCK_DAILY_PREFILL, strategy.getCommand());
        assertTrue(strategy.isNeedSa(), "α日线预填必须为超管指令");
        assertNull(strategy.getRoleType());
    }

    @Test
    @DisplayName("无效结束日期_回复无效且不占用维护互斥门")
    void handle_invalidDate_doesNotAcquireGate() {
        String reply = handleMsg(BotCommands.ALPHA_STOCK_DAILY_PREFILL + "#not-a-date");

        assertTrue(reply.startsWith("结束日期无效"), "无效日期必须给出明确回执");
        verifyNoInteractions(maintenanceGate, stockBackfillExecutor, alphaDailyCloseService);
    }

    @Test
    @DisplayName("无结束日期参数_默认使用最近已结束自然日")
    void handle_withoutEndDate_usesLastEndedNaturalDay() {
        when(marketClock.lastEndedNaturalDay()).thenReturn(END_DATE);
        when(maintenanceGate.tryAcquire()).thenReturn(true);
        runAsync();

        String reply = handleMsg(BotCommands.ALPHA_STOCK_DAILY_PREFILL);

        assertTrue(reply.contains("已受理"), "无参数必须按最近已结束自然日受理");
        verify(alphaDailyCloseService).buildDailyCloses(END_DATE);
        verify(maintenanceGate).release();
    }

    @Test
    @DisplayName("当前自然日_未结束直接拒绝且不占用维护互斥门")
    void handle_currentNaturalDay_isRejected() {
        when(marketClock.lastEndedNaturalDay()).thenReturn(END_DATE);

        String reply = handleMsg(BotCommands.ALPHA_STOCK_DAILY_PREFILL + "#2026-09-06");

        assertTrue(reply.startsWith("结束日期无效"), "未结束自然日不得预填");
        verifyNoInteractions(maintenanceGate, stockBackfillExecutor, alphaDailyCloseService);
    }

    @Test
    @DisplayName("未来日期_直接拒绝且不占用维护互斥门")
    void handle_futureDate_isRejected() {
        when(marketClock.lastEndedNaturalDay()).thenReturn(END_DATE);

        String reply = handleMsg(BotCommands.ALPHA_STOCK_DAILY_PREFILL + "#2026-09-30");

        assertTrue(reply.startsWith("结束日期无效"), "未来自然日不得预填");
        verifyNoInteractions(maintenanceGate, stockBackfillExecutor, alphaDailyCloseService);
    }

    @Test
    @DisplayName("维护任务占用中_不受理且不投递执行器")
    void handle_maintenanceBusy_isNotSubmitted() {
        when(marketClock.lastEndedNaturalDay()).thenReturn(END_DATE);
        when(maintenanceGate.tryAcquire()).thenReturn(false);

        String reply = handleMsg(BotCommands.ALPHA_STOCK_DAILY_PREFILL + "#2026-09-05");

        assertTrue(reply.contains("已有历史数据维护任务在执行中"), "互斥门占用时必须回执未受理原因");
        verifyNoInteractions(stockBackfillExecutor, alphaDailyCloseService);
        verify(maintenanceGate, never()).release();
    }

    @Test
    @DisplayName("执行器拒绝_回复未受理并释放互斥门")
    void handle_executorRejected_releasesGate() {
        when(marketClock.lastEndedNaturalDay()).thenReturn(END_DATE);
        when(maintenanceGate.tryAcquire()).thenReturn(true);
        doThrow(new RejectedExecutionException("queue full"))
                .when(stockBackfillExecutor).execute(any(Runnable.class));

        String reply = handleMsg(BotCommands.ALPHA_STOCK_DAILY_PREFILL + "#2026-09-05");

        assertTrue(reply.contains("历史数据维护执行器已满"), "执行器拒绝时必须回执未受理原因");
        verify(maintenanceGate).release();
        verifyNoInteractions(alphaDailyCloseService);
    }

    @Test
    @DisplayName("受理成功_异步构建日线快照并回执写入条数")
    void handle_accepted_buildsDailyClosesAndReleasesGate() {
        when(marketClock.lastEndedNaturalDay()).thenReturn(END_DATE);
        when(maintenanceGate.tryAcquire()).thenReturn(true);
        when(alphaDailyCloseService.buildDailyCloses(END_DATE)).thenReturn(35);
        runAsync();

        String reply = handleMsg(BotCommands.ALPHA_STOCK_DAILY_PREFILL + "#2026-09-05");

        assertTrue(reply.contains("已受理"), "合法结束日期必须受理");
        verify(alphaDailyCloseService).buildDailyCloses(END_DATE);
        verify(maintenanceGate).release();
        assertTrue(sentReceipts().stream().anyMatch(text -> text.contains("写入条数：35")),
                "有写入时必须回执写入条数");
    }

    @Test
    @DisplayName("无已结束有效日期可构建_回执区分无写入而不是空成功")
    void handle_acceptedWithoutBuildableDate_reportsNoEndedValidDate() {
        when(marketClock.lastEndedNaturalDay()).thenReturn(END_DATE);
        when(maintenanceGate.tryAcquire()).thenReturn(true);
        when(alphaDailyCloseService.buildDailyCloses(END_DATE)).thenReturn(0);
        runAsync();

        String reply = handleMsg(BotCommands.ALPHA_STOCK_DAILY_PREFILL + "#2026-09-05");

        assertTrue(reply.contains("已受理"), "无写入时仍须受理指令");
        assertTrue(sentReceipts().stream().anyMatch(text -> text.contains("无已结束有效日期可构建")),
                "无写入时必须明确回执无可构建日期");
        assertTrue(sentReceipts().stream().noneMatch(text -> text.contains("写入条数")),
                "无写入时不得回执写入条数");
        verify(maintenanceGate).release();
    }

    /**
     * 提取异步执行完成后回执到群内的文本内容。
     *
     * @return 回执文本
     */
    private List<String> sentReceipts() {
        ArgumentCaptor<BotHttpReqParam> paramCaptor = ArgumentCaptor.forClass(BotHttpReqParam.class);
        verify(bot, atLeastOnce()).sendRequest(paramCaptor.capture(), eq(String.class));
        return paramCaptor.getAllValues().stream()
                .map(BotHttpReqParam::body)
                .filter(GroupMsgReqParam.class::isInstance)
                .map(GroupMsgReqParam.class::cast)
                .flatMap(body -> body.getMessage().stream())
                .filter(TextQqMsg.class::isInstance)
                .map(TextQqMsg.class::cast)
                .map(msg -> msg.getData().text())
                .toList();
    }

    /**
     * 让指令投递的任务在调用线程内同步执行,用于验证回执内容。
     */
    private void runAsync() {
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(stockBackfillExecutor).execute(any(Runnable.class));
    }

    /**
     * 执行指令并返回单条文本回执。
     *
     * @param msg 指令原文
     * @return 回执文本
     */
    private String handleMsg(String msg) {
        List<? extends QqMsgParam<?>> replies = strategy.handle(GROUP_ID, null, msg);
        assertEquals(1, replies.size(), "应返回单条文本消息");
        assertInstanceOf(TextQqMsg.class, replies.getFirst());
        return ((TextQqMsg) replies.getFirst()).getData().text();
    }
}
