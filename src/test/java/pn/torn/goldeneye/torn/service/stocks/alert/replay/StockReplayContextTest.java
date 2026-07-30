package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票回放请求、上下文和内存组合状态测试。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@DisplayName("股票回放上下文测试")
class StockReplayContextTest {

    private static final LocalDateTime START_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime END_TIME = LocalDateTime.of(2026, 1, 2, 0, 0);

    @Test
    @DisplayName("有效请求_创建独立上下文并初始化正式五槽资金")
    void create_validRequest_initializesFormalFiveSlots() {
        StockReplayRequest request = request(EnumSet.of(StockReplayTrackEnum.FORMAL_5_SLOT));

        StockReplayContext first = StockReplayContext.create(request);
        StockReplayContext second = StockReplayContext.create(request);

        assertEquals("VIP_FORMAL", first.request().portfolioId());
        assertNotEquals(first.boundary().runId(), second.boundary().runId());
        assertEquals(5, first.portfolioState(StockReplayTrackEnum.FORMAL_5_SLOT).slots().size());
        assertTrue(first.portfolioState(StockReplayTrackEnum.FORMAL_5_SLOT).slots().stream()
                .allMatch(slot -> slot.availableCash().compareTo(new BigDecimal("2000000000.00")) == 0));
    }

    @Test
    @DisplayName("非正式轨道_不创建正式组合槽位")
    void create_shadowRequest_doesNotCreateFormalSlots() {
        StockReplayRequest request = request(EnumSet.of(StockReplayTrackEnum.UNLIMITED_SHADOW,
                StockReplayTrackEnum.REJECTED_OBSERVATION));

        StockReplayContext context = StockReplayContext.create(request);

        assertEquals(0, context.portfolioState(StockReplayTrackEnum.UNLIMITED_SHADOW).slots().size());
        assertEquals(0, context.portfolioState(StockReplayTrackEnum.REJECTED_OBSERVATION).slots().size());
    }

    @Test
    @DisplayName("回放请求_时间版本目录和轨道非法时拒绝")
    void request_invalidFields_rejectsInput() {
        assertThrows(IllegalArgumentException.class, () -> new StockReplayRequest(
                "VIP_FORMAL", END_TIME, START_TIME, "BAR_V1", "FEATURE_V1",
                "BUY_V1", "SELL_V1", "ALLOC_V1", "MSG_V1", Path.of("target/replay"),
                Set.of(StockReplayTrackEnum.FORMAL_5_SLOT)));
        assertThrows(IllegalArgumentException.class, () -> new StockReplayRequest(
                " ", START_TIME, END_TIME, "BAR_V1", "FEATURE_V1",
                "BUY_V1", "SELL_V1", "ALLOC_V1", "MSG_V1", Path.of("target/replay"),
                Set.of(StockReplayTrackEnum.FORMAL_5_SLOT)));
        assertThrows(IllegalArgumentException.class, () -> new StockReplayRequest(
                "VIP_FORMAL", START_TIME, END_TIME, "", "FEATURE_V1",
                "BUY_V1", "SELL_V1", "ALLOC_V1", "MSG_V1", Path.of("target/replay"),
                Set.of(StockReplayTrackEnum.FORMAL_5_SLOT)));
        assertThrows(IllegalArgumentException.class, () -> new StockReplayRequest(
                "VIP_FORMAL", START_TIME, END_TIME, "BAR_V1", "FEATURE_V1",
                "BUY_V1", "SELL_V1", "ALLOC_V1", "MSG_V1", Path.of("target/replay"),
                Set.of()));
    }

    private StockReplayRequest request(Set<StockReplayTrackEnum> tracks) {
        return new StockReplayRequest("VIP_FORMAL", START_TIME, END_TIME,
                "BAR_V1", "FEATURE_V1", "BUY_V1", "SELL_V1", "ALLOC_V1", "MSG_V1",
                Path.of("target/replay"), tracks);
    }
}
