package pn.torn.goldeneye.torn.service.stocks.replay.model;

/**
 * 回放实际处理模式。
 *
 * <p>回放必须保持两种时间分离:{@code roundTime}为历史bar、特征、信号和理论成交锚点;
 * {@code actualProcessingTime}为模拟的实际执行/恢复时刻,仅用于ENTRY过期(entryStaleAt)判断。
 * 本轮只支持两种有限模式,不建设任意停机计划、每轮动态恢复脚本或定时任务模拟器。</p>
 *
 * <ul>
 *   <li>{@link #ONLINE_BASELINE}: 每个轮次的actualProcessingTime等于roundTime,
 *       模拟服务始终准时在线的理想基线;对应{@code recoveredAt}必须为空。</li>
 *   <li>{@link #RESTART_STRESS}: 停机积压轮次以请求指定的{@code recoveredAt}作为
 *       actualProcessingTime,验证晚恢复不得补发过期BUY;对应{@code recoveredAt}必填,
 *       且不得早于按该时刻处理的回放轮次。</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.11
 */
public enum StockReplayProcessingModeEnum {
    /**
     * 理想连续在线基线: actualProcessingTime等于每个roundTime。
     */
    ONLINE_BASELINE,
    /**
     * 停机晚恢复压力: 积压轮次以请求指定的recoveredAt作为actualProcessingTime。
     */
    RESTART_STRESS
}
