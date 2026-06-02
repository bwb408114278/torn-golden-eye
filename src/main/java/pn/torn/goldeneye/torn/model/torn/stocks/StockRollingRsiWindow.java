package pn.torn.goldeneye.torn.model.torn.stocks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 股票回溯RSI窗口
 *
 * @author Bai
 * @version 1.1.6
 * @since 2026.06.02
 */
public class StockRollingRsiWindow {
    private static final int PERIOD = 60;
    private static final int SCALE = 12;
    private static final BigDecimal EPSILON = BigDecimal.valueOf(0.0000001);

    private final Deque<BigDecimal> gains = new ArrayDeque<>();
    private final Deque<BigDecimal> losses = new ArrayDeque<>();

    private BigDecimal gainSum = BigDecimal.ZERO;
    private BigDecimal lossSum = BigDecimal.ZERO;
    private BigDecimal previousPrice;

    /**
     * 添加价格
     */
    public void add(BigDecimal price) {
        if (previousPrice == null) {
            previousPrice = price;
            return;
        }

        BigDecimal change = price.subtract(previousPrice);
        BigDecimal gain = change.max(BigDecimal.ZERO);
        BigDecimal loss = change.negate().max(BigDecimal.ZERO);

        gains.addLast(gain);
        losses.addLast(loss);
        gainSum = gainSum.add(gain);
        lossSum = lossSum.add(loss);

        while (gains.size() > PERIOD) {
            gainSum = gainSum.subtract(gains.removeFirst());
            lossSum = lossSum.subtract(losses.removeFirst());
        }

        previousPrice = price;
    }

    /**
     * 计算RSI
     */
    public BigDecimal rsi() {
        if (gains.size() < PERIOD) {
            return BigDecimal.valueOf(50);
        }

        if (lossSum.compareTo(EPSILON) < 0) {
            return BigDecimal.valueOf(100);
        }

        BigDecimal relativeStrength = gainSum.divide(lossSum, SCALE, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(100).subtract(
                BigDecimal.valueOf(100).divide(
                        BigDecimal.ONE.add(relativeStrength),
                        SCALE, RoundingMode.HALF_UP));
    }
}