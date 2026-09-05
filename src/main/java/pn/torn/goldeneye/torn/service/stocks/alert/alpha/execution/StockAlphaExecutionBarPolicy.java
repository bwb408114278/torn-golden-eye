package pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution;

import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;

import java.time.LocalDateTime;

/**
 * α策略下一执行bar策略。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
public final class StockAlphaExecutionBarPolicy {
    private StockAlphaExecutionBarPolicy() {
    }

    /**
     * 计算决策时点对应的唯一下一根15分钟bar起点。
     *
     * @param decisionTime 决策时点
     * @return 执行bar起点
     */
    public static LocalDateTime expectedExecutionBarStart(LocalDateTime decisionTime) {
        return Stock15mBarBuildService.alignToBucket(decisionTime).plusMinutes(15);
    }

    /**
     * 校验执行bar是否为指定精确桶且已结束、可用、价格合法。
     *
     * @param decisionTime 决策时点
     * @param bar          执行bar
     * @param now          当前校验时点
     * @return 是否可执行
     */
    public static boolean isExecutable(LocalDateTime decisionTime, ExecutionBar bar, LocalDateTime now) {
        LocalDateTime expected = expectedExecutionBarStart(decisionTime);
        return bar != null && expected.equals(bar.barStart()) && now != null
                && !bar.barEnd().isAfter(now) && bar.usable() && bar.price() != null
                && bar.price().signum() > 0;
    }

    /**
     * 双腿必须使用同一执行桶且均合法。
     *
     * @param decisionTime 决策时点
     * @param sellBar      原仓bar
     * @param buyBar       新仓bar
     * @param now          当前校验时点
     * @return 是否允许配对换仓
     */
    public static boolean isAtomicRebalance(LocalDateTime decisionTime, ExecutionBar sellBar,
                                            ExecutionBar buyBar, LocalDateTime now) {
        return isExecutable(decisionTime, sellBar, now) && isExecutable(decisionTime, buyBar, now)
                && sellBar.barStart().equals(buyBar.barStart());
    }

    /**
     * 执行bar值。
     *
     * @param barStart 桶起点
     * @param barEnd   桶终点
     * @param usable   是否可用
     * @param price    参考价
     */
    public record ExecutionBar(LocalDateTime barStart, LocalDateTime barEnd, boolean usable,
                               java.math.BigDecimal price) {
    }
}
