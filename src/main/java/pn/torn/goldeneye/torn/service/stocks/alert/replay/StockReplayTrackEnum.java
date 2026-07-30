package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 回放研究轨道。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@Getter
@RequiredArgsConstructor
public enum StockReplayTrackEnum {
    /**
     * 正式五槽资金轨道。
     */
    FORMAL_5_SLOT("FORMAL_5_SLOT", true),
    /**
     * 无限资金影子轨道，仅在有明确理论单位时计算资金收益。
     */
    UNLIMITED_SHADOW("UNLIMITED_SHADOW", false),
    /**
     * 拒绝机会成本观察轨道。
     */
    REJECTED_OBSERVATION("REJECTED_OBSERVATION", false),
    /**
     * 动态卖出影子轨道。
     */
    DYNAMIC_SELL_SHADOW("DYNAMIC_SELL_SHADOW", false);

    private final String code;
    private final boolean formal;
}
