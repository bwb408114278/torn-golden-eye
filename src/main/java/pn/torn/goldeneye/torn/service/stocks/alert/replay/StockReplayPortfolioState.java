package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import java.util.ArrayList;
import java.util.List;

/**
 * 回放轨道的内存资金状态。
 *
 * @param track 轨道
 * @param slots 轨道槽位
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
public record StockReplayPortfolioState(StockReplayTrackEnum track, List<StockReplaySlotState> slots) {
    /**
     * 构造不可变槽位列表。
     */
    public StockReplayPortfolioState {
        slots = List.copyOf(slots == null ? List.of() : slots);
    }

    /**
     * 创建轨道的初始内存状态。
     *
     * @param track 轨道
     * @return 初始状态
     */
    public static StockReplayPortfolioState initial(StockReplayTrackEnum track) {
        int slotCount = track != null && track.isFormal() ? StockReplayRequest.FORMAL_SLOT_COUNT : 0;
        List<StockReplaySlotState> slots = new ArrayList<>(slotCount);
        for (int slotNo = 1; slotNo <= slotCount; slotNo++) {
            slots.add(StockReplaySlotState.available(slotNo, StockReplayRequest.FORMAL_SLOT_INITIAL_CASH));
        }
        return new StockReplayPortfolioState(track, slots);
    }
}
