package pn.torn.goldeneye.torn.model.torn.stocks.trade;

import pn.torn.goldeneye.repository.model.torn.stocks.StockPricePoint;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 股票回溯窗口
 *
 * @author Bai
 * @version 1.1.6
 * @since 2026.06.02
 */
public class StockRollingWindow {
    private static final int SCALE = 12;
    private static final MathContext SQRT_CONTEXT = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal EPSILON = BigDecimal.valueOf(0.0000001);

    private final Duration duration;
    private final Deque<StockPricePoint> points = new ArrayDeque<>();
    private final Deque<StockPricePoint> minQueue = new ArrayDeque<>();
    private final Deque<StockPricePoint> maxQueue = new ArrayDeque<>();

    private BigDecimal sum = BigDecimal.ZERO;
    private BigDecimal sumSquare = BigDecimal.ZERO;

    public StockRollingWindow(Duration duration) {
        this.duration = duration;
    }

    /**
     * 添加价格节点
     */
    public void add(StockPricePoint point) {
        points.addLast(point);
        sum = sum.add(point.price());
        sumSquare = sumSquare.add(point.price().multiply(point.price()));

        while (!minQueue.isEmpty() && minQueue.peekLast().price().compareTo(point.price()) >= 0) {
            minQueue.removeLast();
        }
        minQueue.addLast(point);

        while (!maxQueue.isEmpty() && maxQueue.peekLast().price().compareTo(point.price()) <= 0) {
            maxQueue.removeLast();
        }
        maxQueue.addLast(point);

        evictExpired(point.time());
    }

    /**
     * 追逐过期
     */
    public void evictExpired(LocalDateTime now) {
        LocalDateTime minTime = now.minus(duration);

        while (!points.isEmpty() && points.peekFirst().time().isBefore(minTime)) {
            StockPricePoint expired = points.removeFirst();
            sum = sum.subtract(expired.price());
            sumSquare = sumSquare.subtract(expired.price().multiply(expired.price()));

            if (!minQueue.isEmpty() && minQueue.peekFirst().time().equals(expired.time())) {
                minQueue.removeFirst();
            }

            if (!maxQueue.isEmpty() && maxQueue.peekFirst().time().equals(expired.time())) {
                maxQueue.removeFirst();
            }
        }
    }

    /**
     * 平均值
     */
    public BigDecimal average() {
        if (points.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return sum.divide(BigDecimal.valueOf(points.size()), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 标准差
     */
    public BigDecimal standardDeviation() {
        if (points.size() < 2) {
            return BigDecimal.ZERO;
        }

        BigDecimal average = average();
        BigDecimal meanSquare = sumSquare.divide(BigDecimal.valueOf(points.size()), SCALE, RoundingMode.HALF_UP);
        BigDecimal variance = meanSquare.subtract(average.multiply(average));

        if (variance.compareTo(EPSILON) < 0) {
            return BigDecimal.ZERO;
        }

        return variance.sqrt(SQRT_CONTEXT);
    }

    /**
     * 最小值
     */
    public BigDecimal min() {
        return minQueue.isEmpty() ? BigDecimal.ZERO : minQueue.peekFirst().price();
    }

    /**
     * 最大值
     */
    public BigDecimal max() {
        return maxQueue.isEmpty() ? BigDecimal.ZERO : maxQueue.peekFirst().price();
    }
}