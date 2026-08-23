package pn.torn.goldeneye.napcat.strategy.manage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.torn.service.stocks.backfill.TornsyStockHistoryBackfillScheduler;
import pn.torn.goldeneye.torn.service.stocks.backfill.TornsyStockHistoryBackfillScheduler.BackfillSubmission;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tornsy 股票历史人工范围回填策略测试
 * <p>
 * 验证超管策略声明（指令、描述、{@code isNeedSa=true}）、合法 {@code start#end}
 * 提交调度器并回复已受理、参数错误走既有格式错误响应、调度器拒绝时回复可区分原因。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.15
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Tornsy股票历史人工范围回填策略测试")
class TornsyStockHistoryBackfillStrategyImplTest {

    @Mock
    private TornsyStockHistoryBackfillScheduler scheduler;

    @InjectMocks
    private TornsyStockHistoryBackfillStrategyImpl strategy;

    @Test
    @DisplayName("策略声明 -> 指令/描述/超管权限正确")
    void strategyDeclaration_correct() {
        assertEquals(BotCommands.TORNSY_STOCK_HISTORY_SYNC, strategy.getCommand());
        assertEquals("按指定时间范围同步Tornsy股票分钟数据", strategy.getCommandDescription());
        assertTrue(strategy.isNeedSa(), "人工范围回填必须为超管指令");
        assertNull(strategy.getRoleType());
    }

    @Test
    @DisplayName("合法范围 -> 提交调度器并回复已受理")
    void handle_validRange_submitsAndRepliesAccepted() {
        when(scheduler.submitManualBackfill(
                LocalDateTime.of(2026, 7, 1, 0, 0, 0),
                LocalDateTime.of(2026, 7, 2, 0, 0, 0),
                10000L))
                .thenReturn(BackfillSubmission.ACCEPTED);

        String reply = handleMsg("2026-07-01 00:00:00#2026-07-02 00:00:00");

        verify(scheduler, times(1)).submitManualBackfill(
                LocalDateTime.of(2026, 7, 1, 0, 0, 0),
                LocalDateTime.of(2026, 7, 2, 0, 0, 0),
                10000L);
        assertTrue(reply.startsWith("Tornsy股票数据同步任务已受理"), "合法范围必须回复已受理, 实际: " + reply);
        assertTrue(reply.contains("[2026-07-01 00:00:00, 2026-07-02 00:00:00)"), "已受理回复需包含固定范围: " + reply);
    }

    @ParameterizedTest
    @DisplayName("非法参数 -> 返回既有格式错误消息且不提交")
    @MethodSource("invalidFormatMsgCases")
    void handle_invalidParams_returnsFormatError(String msg) {
        assertEquals("参数有误", handleMsg(msg));
        verifyNoInteractions(scheduler);
    }

    @Test
    @DisplayName("调度器拒绝 -> 回复包含未受理与可区分原因")
    void handle_schedulerRejected_repliesWithReason() {
        assertRejectedReason(BackfillSubmission.TOO_RECENT, "结束时间过新");
        assertRejectedReason(BackfillSubmission.ALREADY_PROCESSING, "已有回填任务在执行中");
        assertRejectedReason(BackfillSubmission.EXECUTOR_REJECTED, "回填执行器已满");
    }

    /**
     * 非法参数格式错误测试数据
     * <p>
     * 覆盖参数数量不足/过多、时间格式错误、起始不早于结束。
     *
     * @return 测试参数流
     */
    private static Stream<Arguments> invalidFormatMsgCases() {
        return Stream.of(
                // 参数数量不足（仅1段）
                Arguments.of("2026-07-01 00:00:00"),
                // 参数数量过多（3段）
                Arguments.of("2026-07-01 00:00:00#2026-07-02 00:00:00#2026-07-03 00:00:00"),
                // 开始时间格式错误
                Arguments.of("2026-07-01#2026-07-02 00:00:00"),
                // 开始时间非时间文本
                Arguments.of("abc#2026-07-02 00:00:00"),
                // 起始晚于结束
                Arguments.of("2026-07-02 00:00:00#2026-07-01 00:00:00"),
                // 起始等于结束
                Arguments.of("2026-07-01 00:00:00#2026-07-01 00:00:00"));
    }

    /**
     * 断言指定拒绝结果时回复包含未受理前缀与原因关键字
     *
     * @param submission 调度器拒绝结果
     * @param reasonKey  原因关键字
     */
    private void assertRejectedReason(BackfillSubmission submission, String reasonKey) {
        when(scheduler.submitManualBackfill(any(LocalDateTime.class), any(LocalDateTime.class), anyLong()))
                .thenReturn(submission);
        String reply = handleMsg("2026-07-01 00:00:00#2026-07-02 00:00:00");
        assertTrue(reply.startsWith("Tornsy股票数据同步未受理"), "拒绝回复需以未受理开头, 实际: " + reply);
        assertTrue(reply.contains(reasonKey), "拒绝回复需包含原因[" + reasonKey + "], 实际: " + reply);
    }

    /**
     * 执行策略并提取文本回复
     *
     * @param msg 命令前缀剥离后的消息正文
     * @return 文本回复
     */
    private String handleMsg(String msg) {
        List<? extends pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam<?>> replies =
                strategy.handle(10000L, null, msg);
        assertEquals(1, replies.size(), "应返回单条文本消息");
        assertInstanceOf(TextQqMsg.class, replies.getFirst());
        return ((TextQqMsg) replies.getFirst()).getData().text();
    }
}
