package pn.torn.goldeneye.napcat.strategy.manage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.torn.service.stocks.rebuild.StockDerivedDataRebuildScheduler;
import pn.torn.goldeneye.torn.service.stocks.rebuild.StockDerivedDataRebuildScheduler.DerivedRebuildSubmission;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 重建 VIP 股票派生数据指令策略测试。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("重建VIP股票派生数据指令策略测试")
class StockDerivedDataRebuildStrategyImplTest {

    @Mock
    private StockDerivedDataRebuildScheduler scheduler;

    @InjectMocks
    private StockDerivedDataRebuildStrategyImpl strategy;

    @Test
    @DisplayName("策略声明_指令/描述/超管权限正确")
    void strategyDeclaration_correct() {
        assertEquals(BotCommands.DERIVED_STOCK_DATA_REBUILD, strategy.getCommand());
        assertEquals("按指定时间范围重建VIP股票派生数据", strategy.getCommandDescription());
        assertTrue(strategy.isNeedSa(), "派生重建必须为超管指令");
        assertNull(strategy.getRoleType());
    }

    @Test
    @DisplayName("合法范围_提交原群号并回复已受理")
    void handle_validRange_submitsWithGroupId() {
        when(scheduler.submit(
                LocalDateTime.of(2026, 7, 1, 0, 0, 0),
                LocalDateTime.of(2026, 7, 2, 0, 0, 0),
                12345L))
                .thenReturn(DerivedRebuildSubmission.ACCEPTED);

        String reply = handleMsg(12345L, "2026-07-01 00:00:00#2026-07-02 00:00:00");

        verify(scheduler).submit(
                LocalDateTime.of(2026, 7, 1, 0, 0, 0),
                LocalDateTime.of(2026, 7, 2, 0, 0, 0),
                12345L);
        assertTrue(reply.startsWith("VIP股票派生数据重建任务已受理"));
        assertTrue(reply.contains("[2026-07-01 00:00:00, 2026-07-02 00:00:00)"));
    }

    @Test
    @DisplayName("调度器拒绝_回复未受理原因")
    void handle_rejected_repliesReason() {
        when(scheduler.submit(any(), any(), anyLong()))
                .thenReturn(DerivedRebuildSubmission.ALREADY_PROCESSING);

        String reply = handleMsg(12345L, "2026-07-01 00:00:00#2026-07-02 00:00:00");

        assertTrue(reply.startsWith("VIP股票派生数据重建未受理"));
        assertTrue(reply.contains("已有历史数据维护任务在执行中"));
    }

    private String handleMsg(long groupId, String msg) {
        List<? extends pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam<?>> replies =
                strategy.handle(groupId, null, msg);
        assertEquals(1, replies.size(), "应返回单条文本消息");
        assertInstanceOf(TextQqMsg.class, replies.getFirst());
        return ((TextQqMsg) replies.getFirst()).getData().text();
    }
}
