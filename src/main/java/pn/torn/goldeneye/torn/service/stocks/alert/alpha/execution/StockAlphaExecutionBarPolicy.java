package pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;

import java.time.LocalDateTime;

/**
 * α策略决策bar与执行bar的连续性策略。
 *
 * <p>本类是"决策时点 → 下一根严格连续、已结束、可用且价格合法的15分钟bar"的唯一算法来源:
 * 决策bar质量、执行bar质量以及两者的严格相邻关系只在此判定,Entry、Rebalance和轮次编排
 * 只负责传递事实或调用本类,不得各自复制一套时间或质量判断。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StockAlphaExecutionBarPolicy {

    /**
     * 计算决策时点对应的唯一下一根15分钟bar起点。
     * <p>
     * 本方法定义"决策时点到执行桶"的映射规则;生产编排不再用轮次时间反推决策时点,
     * 而是直接写入轮次执行桶并经 {@link #requireExecutionBar(LocalDateTime)} 校验后持久化。
     *
     * @param decisionTime 决策时点
     * @return 执行bar起点
     */
    public static LocalDateTime expectedExecutionBarStart(LocalDateTime decisionTime) {
        return Stock15mBarBuildService.alignToBucket(decisionTime).plusMinutes(15);
    }

    /**
     * 校验并固定执行bar起点。
     * <p>
     * 生产编排以轮次执行桶为唯一事实写入{@code execution_bar_start_time},执行阶段只消费该持久化值,
     * 不允许再由轮次时间反推。未对齐到15分钟桶时直接拒绝。
     *
     * @param executionBarStart 执行bar起点
     * @return 校验通过的执行bar起点
     * @throws IllegalArgumentException 执行bar起点不是精确15分钟桶时抛出
     */
    public static LocalDateTime requireExecutionBar(LocalDateTime executionBarStart) {
        if (executionBarStart == null
                || !executionBarStart.equals(Stock15mBarBuildService.alignToBucket(executionBarStart))) {
            throw new IllegalArgumentException("α执行bar起点不是精确15分钟桶: " + executionBarStart);
        }
        return executionBarStart;
    }

    /**
     * 校验决策bar是否为决策时点对齐桶上可用且价格合法的事实。
     * <p>
     * 决策bar是{@code signal_reference_price}的唯一来源。不可用、桶不对齐或价格非正的bar
     * 不得固化为信号参考价,执行阶段也不得用执行bar价格、成本价或0补参考价。
     *
     * @param decisionTime 决策时点
     * @param bar          决策时点所在桶的行情bar事实
     * @return 决策bar可固化为信号参考价时返回true
     */
    public static boolean isUsableDecisionBar(LocalDateTime decisionTime, DecisionBar bar) {
        if (decisionTime == null || bar == null || bar.barStart() == null || bar.barEnd() == null) {
            return false;
        }
        return bar.barStart().equals(Stock15mBarBuildService.alignToBucket(decisionTime))
                && bar.usable()
                && bar.price() != null && bar.price().signum() > 0;
    }

    /**
     * 校验执行bar起点是否为决策bar起点的严格下一根15分钟桶。
     * <p>
     * 更晚的可用bar不能替代紧邻下一根;决策bar与执行bar不构成严格相邻关系时不得成交。
     *
     * @param decisionBarStart  决策bar起点
     * @param executionBarStart 执行bar起点
     * @return 执行桶为决策桶的严格下一根时返回true
     */
    public static boolean isStrictNextBar(LocalDateTime decisionBarStart, LocalDateTime executionBarStart) {
        return decisionBarStart != null && executionBarStart != null
                && executionBarStart.equals(decisionBarStart.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES));
    }

    /**
     * 返回执行bar的前一根决策桶起点。
     *
     * @param executionBarStart 执行bar起点
     * @return 前一根15分钟桶起点
     */
    public static LocalDateTime previousBucket(LocalDateTime executionBarStart) {
        return requireExecutionBar(executionBarStart).minusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);
    }

    /**
     * 校验执行bar是否为持久化执行桶且已结束、可用、价格合法。
     * <p>
     * 只比较持久化{@code execution_bar_start_time}与行情bar起点,不再由决策时点二次推导。
     *
     * @param executionBarStart 持久化执行bar起点
     * @param bar               执行bar
     * @param now               当前校验时点
     * @return 是否可执行
     */
    public static boolean isExecutable(LocalDateTime executionBarStart, ExecutionBar bar, LocalDateTime now) {
        return executionBarStart != null && bar != null && executionBarStart.equals(bar.barStart())
                && now != null && !bar.barEnd().isAfter(now) && bar.usable() && bar.price() != null
                && bar.price().signum() > 0;
    }

    /**
     * 双腿必须使用同一持久化执行桶且均合法。
     *
     * @param executionBarStart 持久化执行bar起点
     * @param sellBar           原仓bar
     * @param buyBar            新仓bar
     * @param now               当前校验时点
     * @return 是否允许配对换仓
     */
    public static boolean isAtomicRebalance(LocalDateTime executionBarStart, ExecutionBar sellBar,
                                            ExecutionBar buyBar, LocalDateTime now) {
        return isExecutable(executionBarStart, sellBar, now) && isExecutable(executionBarStart, buyBar, now)
                && sellBar.barStart().equals(buyBar.barStart());
    }

    /**
     * 决策bar值。
     *
     * @param barStart 决策桶起点
     * @param barEnd   决策桶终点
     * @param usable   是否满足正式bar可用标准
     * @param price    决策时点最后价
     */
    public record DecisionBar(
            LocalDateTime barStart,
            LocalDateTime barEnd,
            boolean usable,
            java.math.BigDecimal price) {
    }

    /**
     * 执行bar值。
     *
     * @param barStart 桶起点
     * @param barEnd   桶终点
     * @param usable   是否可用
     * @param price    参考价
     */
    public record ExecutionBar(
            LocalDateTime barStart,
            LocalDateTime barEnd,
            boolean usable,
            java.math.BigDecimal price) {
    }
}
