package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import java.math.BigDecimal;

/**
 * 回放内存槽位状态。
 *
 * @param slotNo        槽位编号
 * @param availableCash 可用资金
 * @param reservedCash  预留资金
 * @param stocksId      当前股票
 * @param quantity      当前股数
 * @param entryPrice    入场参考价
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
public record StockReplaySlotState(
        int slotNo,
        BigDecimal availableCash,
        BigDecimal reservedCash,
        Integer stocksId,
        long quantity,
        BigDecimal entryPrice
) {
    /**
     * 创建空闲槽位。
     *
     * @param slotNo      槽位编号
     * @param initialCash 初始资金
     * @return 空闲槽位
     */
    public static StockReplaySlotState available(int slotNo, BigDecimal initialCash) {
        return new StockReplaySlotState(slotNo, initialCash, BigDecimal.ZERO,
                null, 0L, null);
    }
}
