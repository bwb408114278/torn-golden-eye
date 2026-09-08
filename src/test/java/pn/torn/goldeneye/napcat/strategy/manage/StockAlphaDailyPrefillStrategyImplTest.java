package pn.torn.goldeneye.napcat.strategy.manage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.constants.bot.BotCommands;
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
 * 只覆盖指令参数解析与异步受理失败路径:无效日期不占用互斥门、维护门占用不受理、
 * 执行器拒绝必须释放互斥门。
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
     * 预填结束日期。
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
    @DisplayName("维护任务占用中_不受理且不投递执行器")
    void handle_maintenanceBusy_isNotSubmitted() {
        when(maintenanceGate.tryAcquire()).thenReturn(false);

        String reply = handleMsg(BotCommands.ALPHA_STOCK_DAILY_PREFILL + "#2026-09-05");

        assertTrue(reply.contains("已有历史数据维护任务在执行中"), "互斥门占用时必须回执未受理原因");
        verifyNoInteractions(stockBackfillExecutor, alphaDailyCloseService);
        verify(maintenanceGate, never()).release();
    }

    @Test
    @DisplayName("执行器拒绝_回复未受理并释放互斥门")
    void handle_executorRejected_releasesGate() {
        when(maintenanceGate.tryAcquire()).thenReturn(true);
        doThrow(new RejectedExecutionException("queue full"))
                .when(stockBackfillExecutor).execute(any(Runnable.class));

        String reply = handleMsg(BotCommands.ALPHA_STOCK_DAILY_PREFILL + "#2026-09-05");

        assertTrue(reply.contains("历史数据维护执行器已满"), "执行器拒绝时必须回执未受理原因");
        verify(maintenanceGate).release();
        verifyNoInteractions(alphaDailyCloseService);
    }

    @Test
    @DisplayName("受理成功_异步构建日线快照并释放互斥门")
    void handle_accepted_buildsDailyClosesAndReleasesGate() {
        when(maintenanceGate.tryAcquire()).thenReturn(true);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(stockBackfillExecutor).execute(any(Runnable.class));

        String reply = handleMsg(BotCommands.ALPHA_STOCK_DAILY_PREFILL + "#2026-09-05");

        assertTrue(reply.contains("已受理"), "合法结束日期必须受理");
        verify(alphaDailyCloseService).buildDailyCloses(END_DATE);
        verify(maintenanceGate).release();
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
