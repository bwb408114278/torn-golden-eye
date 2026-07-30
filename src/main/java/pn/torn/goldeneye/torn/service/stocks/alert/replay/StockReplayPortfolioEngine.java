package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 隔离回放正式资金轨道的纯内存资金引擎。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
public class StockReplayPortfolioEngine {
    /**
     * 卖出后实际保留比例。
     */
    public static final BigDecimal SELL_PROCEEDS_RATE = new BigDecimal("0.999");
    /**
     * 权益无法完整计算。
     */
    public static final String DATA_INSUFFICIENT = "DATA_INSUFFICIENT";
    private static final int SCALE = 18;
    private static final String STATE_REQUIRED_MESSAGE = "组合状态不能为空";

    /**
     * 在指定槽位建立内存持仓。
     *
     * @param state         当前状态
     * @param slotNo        槽位编号
     * @param stocksId      股票ID
     * @param entryPrice    入场价格
     * @param availableCash 用于本次分配的资金
     * @return 入场结果
     */
    public EntryResult enter(StockReplayPortfolioState state, int slotNo, Integer stocksId,
                             BigDecimal entryPrice, BigDecimal availableCash) {
        Objects.requireNonNull(state, STATE_REQUIRED_MESSAGE);
        Objects.requireNonNull(stocksId, "股票ID不能为空");
        validatePositive(entryPrice, "entryPrice");
        validatePositive(availableCash, "availableCash");
        StockReplaySlotState slot = findSlot(state, slotNo);
        if (slot.stocksId() != null) {
            throw new IllegalStateException("槽位已有持仓: " + slotNo);
        }
        long quantity = availableCash.divide(entryPrice, SCALE, RoundingMode.DOWN)
                .setScale(0, RoundingMode.DOWN).longValueExact();
        if (quantity <= 0) {
            throw new IllegalArgumentException("可用资金不足以买入一股");
        }
        BigDecimal actualCost = entryPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal remainingCash = availableCash.subtract(actualCost);
        StockReplaySlotState occupied = new StockReplaySlotState(slotNo, remainingCash,
                BigDecimal.ZERO, stocksId, quantity, entryPrice);
        return new EntryResult(replaceSlot(state, occupied), quantity, actualCost);
    }

    /**
     * 为待买入订单预留整个槽位预算。
     *
     * @param state  当前状态
     * @param slotNo 槽位编号
     * @return 预留后的状态
     */
    public StockReplayPortfolioState reserve(StockReplayPortfolioState state, int slotNo) {
        Objects.requireNonNull(state, STATE_REQUIRED_MESSAGE);
        StockReplaySlotState slot = findSlot(state, slotNo);
        if (slot.stocksId() != null || slot.reservedCash().signum() > 0) {
            throw new IllegalStateException("槽位不可预留: " + slotNo);
        }
        StockReplaySlotState reserved = new StockReplaySlotState(slotNo, BigDecimal.ZERO,
                slot.availableCash(), null, 0L, null);
        return replaceSlot(state, reserved);
    }

    /**
     * 使用已预留预算成交待买入订单。
     *
     * @param state      当前状态
     * @param slotNo     槽位编号
     * @param stocksId   股票ID
     * @param entryPrice 入场价格
     * @return 入场结果
     */
    public EntryResult enterReserved(StockReplayPortfolioState state, int slotNo,
                                     Integer stocksId, BigDecimal entryPrice) {
        Objects.requireNonNull(state, STATE_REQUIRED_MESSAGE);
        Objects.requireNonNull(stocksId, "股票ID不能为空");
        validatePositive(entryPrice, "entryPrice");
        StockReplaySlotState slot = findSlot(state, slotNo);
        validateReservedSlot(slot, slotNo);
        long quantity = slot.reservedCash().divide(entryPrice, SCALE, RoundingMode.DOWN)
                .setScale(0, RoundingMode.DOWN).longValueExact();
        if (quantity <= 0) {
            throw new IllegalArgumentException("预留资金不足以买入一股");
        }
        BigDecimal actualCost = entryPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal remainingCash = slot.reservedCash().subtract(actualCost);
        StockReplaySlotState occupied = new StockReplaySlotState(slotNo, remainingCash,
                BigDecimal.ZERO, stocksId, quantity, entryPrice);
        return new EntryResult(replaceSlot(state, occupied), quantity, actualCost);
    }

    /**
     * 取消待买入订单并释放预留预算。
     *
     * @param state  当前状态
     * @param slotNo 槽位编号
     * @return 释放后的状态
     */
    public StockReplayPortfolioState releaseReserved(StockReplayPortfolioState state, int slotNo) {
        Objects.requireNonNull(state, STATE_REQUIRED_MESSAGE);
        StockReplaySlotState slot = findSlot(state, slotNo);
        validateReservedSlot(slot, slotNo);
        StockReplaySlotState available = StockReplaySlotState.available(slotNo,
                slot.availableCash().add(slot.reservedCash()));
        return replaceSlot(state, available);
    }

    /**
     * 释放指定槽位持仓并结算卖出所得。
     *
     * @param state     当前状态
     * @param slotNo    槽位编号
     * @param exitPrice 卖出价格
     * @return 结算后状态
     */
    public StockReplayPortfolioState exit(StockReplayPortfolioState state, int slotNo,
                                          BigDecimal exitPrice) {
        Objects.requireNonNull(state, STATE_REQUIRED_MESSAGE);
        validatePositive(exitPrice, "exitPrice");
        StockReplaySlotState slot = findSlot(state, slotNo);
        if (slot.stocksId() == null || slot.quantity() <= 0) {
            throw new IllegalStateException("槽位没有可卖持仓: " + slotNo);
        }
        BigDecimal proceeds = exitPrice.multiply(BigDecimal.valueOf(slot.quantity()))
                .multiply(SELL_PROCEEDS_RATE);
        StockReplaySlotState available = StockReplaySlotState.available(slotNo,
                slot.availableCash().add(proceeds));
        return replaceSlot(state, available);
    }

    /**
     * 计算当前组合权益。只要任一持仓缺少对应价格，就返回数据不足。
     *
     * @param state  当前状态
     * @param prices 当前股票价格，顺序与持仓股票匹配
     * @return 权益结果
     */
    public EquityResult calculateEquity(StockReplayPortfolioState state,
                                        List<PricePoint> prices) {
        Objects.requireNonNull(state, STATE_REQUIRED_MESSAGE);
        List<PricePoint> points = prices == null ? List.of() : prices;
        BigDecimal equity = state.slots().stream()
                .map(slot -> slot.availableCash().add(slot.reservedCash()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        for (StockReplaySlotState slot : state.slots()) {
            if (slot.stocksId() == null) {
                continue;
            }
            PricePoint point = points.stream().filter(item -> slot.stocksId().equals(item.stocksId()))
                    .findFirst().orElse(null);
            if (point == null || point.price() == null) {
                return new EquityResult(null, DATA_INSUFFICIENT);
            }
            equity = equity.add(point.price().multiply(BigDecimal.valueOf(slot.quantity()))
                    .multiply(SELL_PROCEEDS_RATE));
        }
        return new EquityResult(equity, "COMPLETE");
    }

    private StockReplaySlotState findSlot(StockReplayPortfolioState state, int slotNo) {
        return state.slots().stream().filter(slot -> slot.slotNo() == slotNo).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("槽位不存在: " + slotNo));
    }

    private void validateReservedSlot(StockReplaySlotState slot, int slotNo) {
        if (slot.stocksId() != null || slot.reservedCash().signum() <= 0) {
            throw new IllegalStateException("槽位没有待成交预留: " + slotNo);
        }
    }

    private StockReplayPortfolioState replaceSlot(StockReplayPortfolioState state,
                                                  StockReplaySlotState replacement) {
        List<StockReplaySlotState> slots = new ArrayList<>(state.slots());
        for (int index = 0; index < slots.size(); index++) {
            if (slots.get(index).slotNo() == replacement.slotNo()) {
                slots.set(index, replacement);
                return new StockReplayPortfolioState(state.track(), slots);
            }
        }
        throw new IllegalArgumentException("槽位不存在: " + replacement.slotNo());
    }

    private void validatePositive(BigDecimal value, String fieldName) {
        Objects.requireNonNull(value, fieldName + "不能为空");
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + "必须为正数");
        }
    }

    /**
     * 入场结果。
     */
    public record EntryResult(StockReplayPortfolioState state, long quantity, BigDecimal actualCost) {
    }

    /**
     * 当前价格点。
     */
    public record PricePoint(Integer stocksId, BigDecimal price) {
    }

    /**
     * 权益结果。
     */
    public record EquityResult(BigDecimal equity, String status) {
    }
}
